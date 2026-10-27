package captions;

import com.voicetranscript.stt.HallucinationFilter;
import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperContextParams;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperSamplingStrategy;
import localapi.api.AudioBus;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The one worker. It takes frames from the bus (queued by the Voice Chat callback, nothing else happens there),
 * cuts utterances per speaker and runs Whisper on each finished utterance, one after the other.
 * Neither the render thread nor Voice Chat's thread ever waits for it.
 */
final class Transcriber {
    enum ModelState { OFF, LOADING, READY, MISSING, ERROR }

    interface Output {
        void caption(UUID speaker, String text);
    }

    private static final int QUEUE_LIMIT = 50_000; // about 16 minutes of one speaker; only reached if the worker is stuck

    private final Logger log;
    private final Supplier<Path> modelPath;
    private final IntSupplier threads;
    private final Supplier<String> language;
    private final Output output;
    private final Assembler assembler;

    private final LinkedBlockingQueue<AudioBus.Frame> queue = new LinkedBlockingQueue<>(QUEUE_LIMIT);
    private Thread worker;
    private AutoCloseable subscription;
    private volatile boolean reloadRequested;

    private static boolean libraryLoaded;
    private static String libraryError;

    private WhisperJNI whisper;
    private WhisperContext context;

    volatile ModelState modelState = ModelState.OFF;
    volatile String modelMessage = "not started";
    final AtomicLong transcribed = new AtomicLong();
    final AtomicLong discardedNoise = new AtomicLong();
    final AtomicLong discardedFiltered = new AtomicLong();
    final AtomicLong failed = new AtomicLong();
    final AtomicLong droppedFrames = new AtomicLong();
    volatile String lastText = "";
    volatile long lastInferenceMs;
    /** True while an utterance is being recognized (tests wait for this before touching the context). */
    volatile boolean busy;

    Transcriber(Logger log, Supplier<Path> modelPath, IntSupplier threads, Supplier<String> language,
                IntSupplier silenceMs, java.util.function.DoubleSupplier rmsGate, Output output) {
        this.log = log;
        this.modelPath = modelPath;
        this.threads = threads;
        this.language = language;
        this.output = output;
        this.assembler = new Assembler(this::utterance, silenceMs, rmsGate);
    }

    boolean isRunning() { return worker != null; }

    int queued() { return queue.size(); }

    /** Starts or stops the worker. Stopping frees the model (it is hundreds of MB). */
    synchronized void setActive(boolean on) {
        if (on && worker == null) {
            subscription = AudioBus.subscribe(this::offer);
            reloadRequested = true;
            worker = new Thread(this::run, "localapi-captions");
            worker.setDaemon(true);
            worker.start();
        } else if (!on && worker != null) {
            try { subscription.close(); } catch (Exception ignored) {}
            subscription = null;
            worker.interrupt();
            worker = null;
            queue.clear();
        }
    }

    /** Model path changed: reload in the worker, no client restart. */
    synchronized void reloadModel() {
        reloadRequested = true;
    }

    /** Voice Chat's thread: only enqueue. */
    private void offer(AudioBus.Frame f) {
        if (!queue.offer(f)) droppedFrames.incrementAndGet();
    }

    // ------------------------------------------------------------------ worker

    private void run() {
        long lastFrameTs = 0;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (reloadRequested) {
                    reloadRequested = false;
                    loadModel();
                    queue.clear(); // audio heard while the model loaded is stale
                    assembler.clear();
                }
                AudioBus.Frame f = queue.poll(50, TimeUnit.MILLISECONDS);
                if (f != null) {
                    lastFrameTs = f.timeMs();
                    if (context != null) assembler.frame(f);
                }
                // With frames still queued the newest frame time is the clock; otherwise the wall clock.
                assembler.tick(queue.isEmpty() ? System.currentTimeMillis() : lastFrameTs);
            }
        } catch (InterruptedException ignored) {
            // stop requested
        } catch (Throwable t) {
            log.error("captions worker died", t);
            modelState = ModelState.ERROR;
            modelMessage = "worker stopped: " + t;
        } finally {
            freeModel();
            if (modelState != ModelState.ERROR) {
                modelState = ModelState.OFF;
                modelMessage = "stopped";
            }
        }
    }

    private void freeModel() {
        WhisperContext c = context;
        context = null;
        if (c != null && whisper != null) {
            try { whisper.free(c); } catch (Throwable t) { log.warn("free model: {}", t.toString()); }
        }
    }

    private void loadModel() {
        freeModel();
        Path p = modelPath.get();
        if (!Files.isRegularFile(p)) {
            modelState = ModelState.MISSING;
            modelMessage = "model file not found: " + p + " (set modelPath below; nothing is downloaded)";
            return;
        }
        modelState = ModelState.LOADING;
        modelMessage = "loading " + p.getFileName() + " ...";
        try {
            if (!libraryLoaded) {
                if (libraryError != null) throw new IllegalStateException(libraryError);
                try {
                    WhisperJNI.loadLibrary();
                    libraryLoaded = true;
                } catch (Throwable t) {
                    libraryError = "whisper library: " + t;
                    throw t;
                }
            }
            if (whisper == null) whisper = new WhisperJNI();
            WhisperContextParams cp = new WhisperContextParams();
            cp.useGPU = false; // the bundled whisper-jni DLL is CPU only
            long t0 = System.nanoTime();
            context = whisper.init(p, cp);
            modelState = ModelState.READY;
            modelMessage = p.getFileName() + " loaded in " + (System.nanoTime() - t0) / 1_000_000 + " ms";
            log.info("captions: {}", modelMessage);
        } catch (Throwable t) {
            context = null;
            modelState = ModelState.ERROR;
            modelMessage = "cannot load " + p.getFileName() + ": " + t;
            log.warn("captions: {}", modelMessage);
        }
    }

    // ------------------------------------------------------------------ one utterance

    private void utterance(UUID speaker, String kind, short[] pcm48) {
        busy = true;
        try {
            utterance0(speaker, kind, pcm48);
        } finally {
            busy = false;
        }
    }

    private void utterance0(UUID speaker, String kind, short[] pcm48) {
        WhisperContext ctx = context;
        if (ctx == null) return;
        float[] x = Audio.prepare(Audio.to16k(pcm48));
        if (x == null) {
            discardedNoise.incrementAndGet();
            return;
        }
        String text = recognize(ctx, x);
        if (text == null) return;
        if (text.isBlank() || HallucinationFilter.isHallucination(text)) {
            discardedFiltered.incrementAndGet();
            return;
        }
        String clean = HallucinationFilter.cleanText(text);
        if (clean == null || clean.isBlank()) {
            discardedFiltered.incrementAndGet();
            return;
        }
        transcribed.incrementAndGet();
        lastText = clean;
        output.caption(speaker, clean);
    }

    /** @return the joined text ("" for 0 segments), or null when Whisper failed */
    String recognize(WhisperContext ctx, float[] samples) {
        WhisperFullParams params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
        params.temperature = 0f;
        params.temperatureInc = 0f;
        // encoder frames for just this snippet (20 ms each) instead of the full 30 s window
        params.audioCtx = Math.clamp(samples.length / 320, 64, 1500);
        params.nThreads = Math.max(1, threads.getAsInt());
        params.noContext = true;
        params.translate = false;
        params.printRealtime = false;
        params.printProgress = false;
        params.printTimestamps = false;
        params.printSpecial = false;
        params.suppressBlank = true;
        params.suppressNonSpeechTokens = true;
        String lang = language.get();
        params.language = lang;                    // "auto", "de" or "en"
        params.detectLanguage = false;
        long t0 = System.nanoTime();
        try {
            int rc = whisper.full(ctx, params, samples, samples.length);
            lastInferenceMs = (System.nanoTime() - t0) / 1_000_000;
            if (rc != 0) {
                failed.incrementAndGet();
                log.warn("captions: whisper returned {}", rc);
                return null;
            }
            int n = whisper.fullNSegments(ctx);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                String s = whisper.fullGetSegmentText(ctx, i);
                if (s != null) sb.append(s);
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            failed.incrementAndGet();
            log.warn("captions: whisper failed: {}", t.toString());
            return null;
        }
    }

    /** For tests: run one prepared utterance without the queue. Needs loadModel() first (via setActive). */
    WhisperContext contextForTest() { return context; }
}
