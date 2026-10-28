package localapi;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import localapi.api.Call;
import localapi.api.StreamSink;
import localapi.plugin.Params;
import localapi.plugin.PluginFunction;
import localapi.plugin.PluginInfo;
import localapi.plugin.PluginManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single entry for "call this path with these parameters". HTTP and the in-game UI both use it,
 * so errors (400/404/405/409/500/504) are identical. Blocks the CALLING thread only, never the game thread:
 * call it from an HTTP worker or a UI background thread, not from the render thread.
 */
public final class Dispatcher {
    private static final Gson GSON = new Gson();
    private static final AtomicInteger N = new AtomicInteger();
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "localapi-worker-" + N.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private Dispatcher() {}

    public record Outcome(int status, JsonElement data, String error, JsonObject extra, byte[] png) {
        public Outcome(int status, JsonElement data, String error, JsonObject extra) { this(status, data, error, extra, null); }
        public boolean ok() { return error == null; }

        static Outcome ok(JsonElement d) { return new Outcome(200, d == null ? JsonNull.INSTANCE : d, null, null); }

        static Outcome err(int status, String msg) { return new Outcome(status, null, msg, null); }

        static Outcome err(int status, String msg, JsonObject extra) { return new Outcome(status, null, msg, extra); }
    }

    // ------------------------------------------------------------------ one-shot

    public static Outcome call(Request req, int timeoutMs) {
        String path = Router.normalize(req.path());
        if (path.equals("/ping")) {
            JsonObject o = new JsonObject();
            o.addProperty("mod", "localapi");
            return Outcome.ok(o);
        }
        if (Router.isCore(path)) {
            return await(GameQueue.submit(() -> Router.dispatch(req)), timeoutMs, null, path);
        }
        PluginManager.Binding b = PluginManager.binding(path);
        if (b == null) return notFound(path);
        Outcome pre = precheck(b, req, path);
        if (pre != null) return pre;
        PluginFunction fn = b.fn();
        if (fn.isStream())
            return Outcome.err(400, path + " is a stream: open the connection and read lines (GET, response never ends)");
        Call call;
        try {
            call = Params.bind(fn, req);
        } catch (HttpError e) {
            return Outcome.err(e.status, e.getMessage());
        }
        if (fn.gameThread) {
            return await(GameQueue.submit(() -> fn.once.handle(call)), timeoutMs, b.info(), path);
        }
        CompletableFuture<JsonElement> f = CompletableFuture.supplyAsync(() -> {
            try {
                return fn.once.handle(call);
            } catch (Throwable t) {
                throw new CompletionException(t);
            }
        }, WORKERS);
        return await(f, timeoutMs, b.info(), path);
    }

    private static Outcome precheck(PluginManager.Binding b, Request req, String path) {
        if (b.blocked() != null) return Outcome.err(409, b.blocked());
        PluginFunction fn = b.fn();
        if (!fn.method.equals(req.method()))
            return Outcome.err(405, path + " needs " + fn.method + " (you sent " + req.method() + ")");
        return null;
    }

    private static Outcome notFound(String path) {
        JsonObject extra = new JsonObject();
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        PluginManager.allPaths().forEach(a::add);
        extra.add("paths", a);
        return Outcome.err(404, "no such path: " + path + ". These paths exist (see 'paths').", extra);
    }

    @SuppressWarnings("unchecked")
    private static Outcome await(CompletableFuture<?> f, int timeoutMs, PluginInfo info, String path) {
        try {
            Object r = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (r instanceof ItemIcons.Png png) return new Outcome(200, JsonNull.INSTANCE, null, null, png.bytes());
            JsonElement je = r == null ? JsonNull.INSTANCE : r instanceof JsonElement e ? e : GSON.toJsonTree(r);
            return Outcome.ok(je);
        } catch (TimeoutException te) {
            f.cancel(false); // queued work is skipped, an action must not run late
            return Outcome.err(504, "no answer within " + timeoutMs + " ms (loading screen, frozen client, or slow handler?)");
        } catch (InterruptedException ie) {
            f.cancel(false); // interrupted callers must not leave queued actions behind
            Thread.currentThread().interrupt();
            return Outcome.err(500, "interrupted");
        } catch (ExecutionException ee) {
            return fromThrowable(ee.getCause(), info, path);
        }
    }

    private static Outcome fromThrowable(Throwable t, PluginInfo info, String path) {
        while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
        if (t instanceof HttpError he) return Outcome.err(he.status, he.getMessage(), he.extra);
        if (t instanceof LinkageError || t instanceof ClassNotFoundException) {
            String m = "a class this function needs is not available: " + t.getMessage();
            if (info != null) info.recordError(path + " 409 " + m);
            return Outcome.err(409, m);
        }
        String m = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        LocalApiMod.LOG.warn("[localapi] {} failed", path, t);
        if (info != null) info.recordError(path + " 500 " + m);
        return Outcome.err(500, m);
    }

    // ------------------------------------------------------------------ stream

    public static boolean isActiveStream(String path) {
        if (Router.normalize(path).equals("/events")) return true;
        PluginManager.Binding b = PluginManager.binding(Router.normalize(path));
        return b != null && b.blocked() == null && b.fn() != null && b.fn().isStream();
    }

    /**
     * Validates and starts a stream function. Runs on the calling thread (never the game thread).
     * The handler keeps the sink and returns; frames arrive later.
     */
    public static Outcome openStream(Request req, StreamSink sink) {
        String path = Router.normalize(req.path());
        if (path.equals("/events")) {
            if (!req.method().equals("GET")) return Outcome.err(405, "/events needs GET");
            if (req.has("since")) return Outcome.err(400, "/events does not replay state; fetch live endpoints after connecting");
            Outcome result = await(GameQueue.submit(() -> { SessionEvents.INSTANCE.subscribe(sink); return null; }),
                    LocalApiMod.CONFIG == null ? 3000 : LocalApiMod.CONFIG.timeoutMs, null, path);
            if (!result.ok()) sink.close();
            return result;
        }
        PluginManager.Binding b = PluginManager.binding(path);
        if (b == null) return notFound(path);
        Outcome pre = precheck(b, req, path);
        if (pre != null) return pre;
        PluginFunction fn = b.fn();
        if (!fn.isStream()) return Outcome.err(400, path + " is not a stream");
        Call call;
        try {
            call = Params.bind(fn, req);
        } catch (HttpError e) {
            return Outcome.err(e.status, e.getMessage());
        }
        try {
            fn.stream.open(call, sink);
            return Outcome.ok(JsonNull.INSTANCE);
        } catch (Throwable t) {
            sink.close();
            return fromThrowable(t, b.info(), path);
        }
    }
}

