package captions;

import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperSamplingStrategy;
import localapi.api.AudioBus;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Headless check of the captions pipeline with a real model. Run: see test/run-test.ps1 */
public final class SelfTest {
    static int fails;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + (detail.isEmpty() ? "" : "   [" + detail + "]"));
        if (!ok) fails++;
    }

    static short[] readWav16k(Path p) throws Exception {
        byte[] b = Files.readAllBytes(p);
        int off = 44; // plain PCM header written by SAPI
        short[] s = new short[(b.length - off) / 2];
        ByteBuffer.wrap(b, off, b.length - off).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
        return s;
    }

    static short[] to48k(short[] s16) {
        short[] o = new short[s16.length * 3];
        for (int i = 0; i < s16.length; i++) o[i * 3] = o[i * 3 + 1] = o[i * 3 + 2] = s16[i];
        return o;
    }

    static short[] scale(short[] s, double f) {
        short[] o = new short[s.length];
        for (int i = 0; i < s.length; i++) o[i] = (short) Math.max(-32768, Math.min(32767, Math.round(s[i] * f)));
        return o;
    }

    /** Publishes 960-sample frames with synthetic receive times ending 1.5 s ago, so the silence rule fires at once. */
    static void play(short[] pcm48, UUID speaker, String kind) {
        int frames = pcm48.length / 960;
        long t = System.currentTimeMillis() - frames * 20L - 1500;
        for (int i = 0; i < frames; i++) {
            short[] f = new short[960];
            System.arraycopy(pcm48, i * 960, f, 0, 960);
            AudioBus.publish(new AudioBus.Frame(kind, speaker, t + i * 20L, f, false));
        }
        AudioBus.publish(new AudioBus.Frame(kind, speaker, t + frames * 20L, new short[0], false)); // end-of-speech marker
    }

    /** Waits until the worker has produced `outcomes` results in total and is idle again. */
    static void settle(Transcriber tr, long outcomes, int ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(100);
            long done = tr.transcribed.get() + tr.discardedNoise.get() + tr.discardedFiltered.get();
            if (done >= outcomes && tr.queued() == 0 && !tr.busy) {
                Thread.sleep(600); // let a late utterance show up if there were more than expected
                if (!tr.busy && tr.queued() == 0) return;
            }
        }
    }
    public static void main(String[] a) throws Exception {
        Path model = Path.of(a[0]);
        Path wav = Path.of(a[1]);

        // ---- pure functions
        check("alpha inside window", CaptionsHud.calculateAlpha(0, 5000) == 1f && CaptionsHud.calculateAlpha(4000, 5000) == 1f, "");
        check("alpha fade half", Math.abs(CaptionsHud.calculateAlpha(4500, 5000) - 0.5f) < 1e-6, "");
        check("alpha expired / negative", CaptionsHud.calculateAlpha(5000, 5000) == 0f && CaptionsHud.calculateAlpha(-1, 5000) == 0f, "");
        short[] quiet = new short[960];
        Random rnd = new Random(1);
        for (int i = 0; i < quiet.length; i++) quiet[i] = (short) (rnd.nextGaussian() * 300); // rms ~0.009
        check("rms of quiet frame below gate", Audio.rms(quiet) < 0.02f, "rms=" + Audio.rms(quiet));
        float[] x = new float[20000];
        for (int i = 0; i < x.length; i++) x[i] = 0.1f * (i % 2 == 0 ? 1 : -1);
        check("noise-only snippet is thrown away (peak 0.1 * 4 < 0.9)", Audio.prepare(x) == null, "");
        for (int i = 0; i < x.length; i++) x[i] = 0.394f * (i % 2 == 0 ? 1 : -1);
        float[] prep = Audio.prepare(x);
        check("peak 0.394 reaches 0.9, 100 ms pad each side",
                prep != null && prep.length == 20000 + 3200 && Math.abs(Audio.peak(prep) - 0.9f) < 1e-3f
                        && prep[0] == 0f && prep[prep.length - 1] == 0f, prep == null ? "null" : "len=" + prep.length + " peak=" + Audio.peak(prep));

        float[] shortSnip = new float[8000];
        for (int i = 0; i < shortSnip.length; i++) shortSnip[i] = 0.5f * (i % 2 == 0 ? 1 : -1);
        float[] shortPrep = Audio.prepare(shortSnip);
        check("snippet under 1 s is extended to the whisper minimum, speech starts after 100 ms",
                shortPrep != null && shortPrep.length == Audio.MIN_SAMPLES_16K && shortPrep[1599] == 0f && shortPrep[1600] != 0f,
                shortPrep == null ? "null" : "len=" + shortPrep.length);

        // ---- assembler without Whisper
        List<String> cut = new ArrayList<>();
        Assembler asm = new Assembler((sp, k, pcm) -> cut.add(k + ":" + pcm.length), () -> 380, () -> 0.02);
        short[] loud = new short[960];
        for (int i = 0; i < loud.length; i++) loud[i] = (short) (8000 * Math.sin(i * 0.2));
        UUID u = UUID.randomUUID();
        long t0 = 1_000_000;
        for (int i = 0; i < 10; i++) asm.frame(new AudioBus.Frame("entity", u, t0 + i * 20, loud, false));
        asm.frame(new AudioBus.Frame("entity", u, t0 + 200, quiet, false));         // quiet: dropped, not extended
        asm.frame(new AudioBus.Frame("entity", u, t0 + 220, new short[0], false));   // end marker: ignored
        asm.tick(t0 + 180 + 379);
        check("379 ms of silence: not cut yet", cut.isEmpty(), cut.toString());
        asm.tick(t0 + 180 + 380);
        check("380 ms of silence cuts exactly the loud frames", cut.equals(List.of("entity:9600")), cut.toString());
        cut.clear();
        asm.frame(new AudioBus.Frame("entity", u, t0 + 5000, loud, false));
        asm.frame(new AudioBus.Frame("entity", u, t0 + 5500, loud, false));          // gap of 500 ms: previous one is cut first
        check("a gap longer than silenceMs between frames splits", cut.equals(List.of("entity:960")), cut.toString());

        // ---- real pipeline
        List<String> lines = new CopyOnWriteArrayList<>();
        final String[] lang = {"auto"};
        Transcriber tr = new Transcriber(org.slf4j.LoggerFactory.getLogger("test"), () -> model, () -> 4, () -> lang[0],
                () -> 380, () -> 0.02, (sp, text) -> lines.add(text));
        tr.setActive(true);
        long end = System.currentTimeMillis() + 120_000;
        while (tr.modelState != Transcriber.ModelState.READY && System.currentTimeMillis() < end) {
            if (tr.modelState == Transcriber.ModelState.ERROR || tr.modelState == Transcriber.ModelState.MISSING) break;
            Thread.sleep(200);
        }
        check("model loads", tr.modelState == Transcriber.ModelState.READY, tr.modelState + " " + tr.modelMessage);
        if (tr.modelState != Transcriber.ModelState.READY) { System.exit(2); }

        short[] speech = to48k(readWav16k(wav));
        float pk = 0;
        for (short s : speech) pk = Math.max(pk, Math.abs(s) / 32768f);
        System.out.println("speech: " + speech.length / 48000.0 + " s, peak " + pk);

        UUID sp = UUID.randomUUID();
        play(speech, sp, "entity");
        settle(tr, 3, 180_000);
        String joined = String.join(" ", lines).toLowerCase();
        check("pauses over 380 ms give one line per utterance, short ones included (TTS pauses twice -> 3)", lines.size() == 3, lines.toString() + " last run " + tr.lastInferenceMs + " ms");
        check("all three parts are there", joined.contains("problem") && joined.contains("coffee") && joined.contains("please"), lines.toString());

        int before = lines.size();
        play(scale(speech, 0.05), sp, "microphone");
        settle(tr, 3, 5_000);
        check("very quiet speech (RMS under the gate) gives no line", lines.size() == before && tr.queued() == 0, lines.toString());

        long noiseBefore = tr.discardedNoise.get();
        short[] noise = new short[48000 * 2];
        for (int i = 0; i < noise.length; i++) noise[i] = (short) Math.max(-3000, Math.min(3000, rnd.nextGaussian() * 1000)); // rms ~0.03: passes the gate
        play(noise, sp, "entity");
        settle(tr, tr.transcribed.get() + tr.discardedFiltered.get() + noiseBefore + 1, 20_000);
        check("noise above the gate is dropped by the peak rule", lines.size() == before && tr.discardedNoise.get() == noiseBefore + 1,
                "noise=" + tr.discardedNoise.get() + " lines=" + lines);

        // ---- language handling, same audio, direct calls on the worker's context
        float[] prepared = Audio.prepare(Audio.to16k(speech));
        String auto = tr.recognize(tr.contextForTest(), prepared);
        System.out.println("auto -> '" + auto + "'");
        lang[0] = "en";
        String en = tr.recognize(tr.contextForTest(), prepared);
        System.out.println("en   -> '" + en + "'");
        lang[0] = "de";
        String de = tr.recognize(tr.contextForTest(), prepared);
        System.out.println("de   -> '" + de + "'");
        check("auto gives text", auto != null && !auto.isBlank(), "'" + auto + "'");

        // detectLanguage=true on whisper.cpp means "only detect the language", which yields no text
        io.github.givimad.whisperjni.WhisperJNI w = new io.github.givimad.whisperjni.WhisperJNI();
        WhisperFullParams p = new WhisperFullParams(WhisperSamplingStrategy.BEAN_SEARCH);
        p.language = "auto";
        p.detectLanguage = true;
        p.nThreads = 4;
        int rc = w.full(tr.contextForTest(), p, prepared, prepared.length);
        int segs = w.fullNSegments(tr.contextForTest());
        System.out.println("detectLanguage=true -> rc=" + rc + " segments=" + segs);

        tr.setActive(false);
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
