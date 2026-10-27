package localapi;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import localapi.api.StreamSink;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One open HTTP stream (NDJSON, chunked). Plugins push frames into a bounded queue from any thread;
 * a dedicated writer thread (not the game thread, not an HTTP pool thread) writes them out.
 */
public final class StreamConnection implements StreamSink {
    private static final Gson GSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final AtomicInteger NAMES = new AtomicInteger();
    private static final int QUEUE_CAP = 512;
    private static final long HEARTBEAT_MS = 5000;

    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAP);
    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final List<Runnable> closers = new ArrayList<>();
    private volatile boolean open = true;
    private volatile boolean aborted;
    private boolean closersRun;
    private volatile HttpExchange exchange;
    private volatile Thread writerThread;

    public static boolean tryAcquire(int max) {
        while (true) {
            int a = ACTIVE.get();
            if (a >= max) return false;
            if (ACTIVE.compareAndSet(a, a + 1)) return true;
        }
    }

    public static int active() { return ACTIVE.get(); }

    /** Gives back a slot taken with tryAcquire() when the stream never started. */
    public static void release() { ACTIVE.decrementAndGet(); }

    // ---- StreamSink

    @Override
    public boolean send(JsonObject line) {
        if (!open) return false;
        String s = GSON.toJson(line);
        while (!queue.offer(s)) {          // reader too slow: drop the OLDEST, keep the stream live
            if (queue.poll() != null) dropped.incrementAndGet();
        }
        frames.incrementAndGet();
        return true;
    }

    @Override public boolean isOpen() { return open; }

    @Override
    public void close() {
        open = false;
        List<Runnable> run;
        synchronized (closers) {
            if (closersRun) return;
            closersRun = true;
            run = new ArrayList<>(closers);
        }
        for (Runnable r : run) {
            try { r.run(); } catch (Throwable t) { LocalApiMod.LOG.warn("[localapi] stream close hook failed", t); }
        }
    }

    @Override
    public void onClose(Runnable r) {
        boolean runNow;
        synchronized (closers) {
            runNow = closersRun;
            if (!runNow) closers.add(r);
        }
        if (runNow) r.run();
    }

    /** Server shutdown: abandon pending frames and unblock a slow network writer. */
    void abort() {
        aborted = true;
        close();
        queue.clear();
        HttpExchange current = exchange;
        if (current != null) current.close();
        Thread writer = writerThread;
        if (writer != null) writer.interrupt();
    }

    // ---- HTTP side

    /** Sends headers and starts the writer thread. Caller must NOT close the exchange. */
    public void start(HttpExchange ex, String path) throws IOException {
        if (aborted) throw new IOException("stream aborted");
        exchange = ex;
        ex.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0); // length 0 = chunked, ends when the exchange closes
        Thread t = new Thread(() -> writer(ex, path), "localapi-stream-" + NAMES.incrementAndGet());
        t.setDaemon(true);
        writerThread = t;
        t.start();
    }

    private void writer(HttpExchange ex, String path) {
        try (OutputStream out = ex.getResponseBody()) {
            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            hello.addProperty("path", path);
            if (path.equals("/events")) hello.addProperty("resync", true);
            hello.addProperty("t", System.currentTimeMillis());
            write(out, GSON.toJson(hello));
            long lastBeat = System.currentTimeMillis();
            while (true) {
                String s = queue.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (s != null) {
                    write(out, s);
                }
                if (!open && queue.isEmpty()) break; // closed and drained
                long now = System.currentTimeMillis();
                if (now - lastBeat >= HEARTBEAT_MS) {
                    lastBeat = now;
                    JsonObject hb = new JsonObject();
                    hb.addProperty("type", "heartbeat");
                    hb.addProperty("t", now);
                    hb.addProperty("frames", frames.get());
                    hb.addProperty("dropped", dropped.get());
                    write(out, GSON.toJson(hb));   // also detects a vanished client
                }
            }
        } catch (IOException e) {
            // client went away: normal
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close();
            ACTIVE.decrementAndGet();
            ex.close();
        }
    }

    private static void write(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}


