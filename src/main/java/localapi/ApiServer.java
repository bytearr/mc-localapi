package localapi;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** HTTP/1.1 + JSON on 127.0.0.1 only. Game access goes through {@link Dispatcher} and {@link GameQueue}. */
public final class ApiServer {
    private static final Gson GSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
    private final Config cfg;
    private volatile byte[] password;
    private HttpServer server;
    private int boundPort;
    private ExecutorService executor;
    private final java.util.Set<StreamConnection> streams = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int MAX_BODY_BYTES = 1 << 20;

    public ApiServer(Config cfg) {
        this.cfg = cfg;
        this.password = cfg.password.getBytes(StandardCharsets.UTF_8);
    }

    /** Takes effect immediately for the next request. */
    public void setPassword(String pw) {
        this.password = pw.getBytes(StandardCharsets.UTF_8);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), cfg.port), 64);
        boundPort = server.getAddress().getPort();
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "localapi-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        executor = Executors.newFixedThreadPool(cfg.httpThreads, tf);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public void stop() {
        streams.forEach(StreamConnection::abort);
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        boolean keepOpen = false; // true once a stream writer owns the exchange
        try {
            if (!ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
                send(ex, 403, error("forbidden")); return;
            }
            // DNS-rebinding / browser protection: only literal loopback hosts, and no Origin header.
            String host = ex.getRequestHeaders().getFirst("Host");
            if (host == null || !(host.equals("127.0.0.1:" + boundPort) || host.equals("localhost:" + boundPort)
                    || host.equals("127.0.0.1") || host.equals("localhost"))) {
                send(ex, 403, error("bad host")); return;
            }
            if (ex.getRequestHeaders().getFirst("Origin") != null) {
                send(ex, 403, error("browser requests are not allowed")); return;
            }

            String path = ex.getRequestURI().getPath();
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());

            if (path.equals("/ping")) { // unauthenticated liveness probe, no game data
                JsonObject o = new JsonObject();
                o.addProperty("ok", true);
                o.addProperty("mod", "localapi");
                send(ex, 200, o); return;
            }

            if (!authorized(ex, query)) {
                try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                send(ex, 401, error("missing or wrong password (header X-Password)")); return;
            }

            String method = ex.getRequestMethod();
            JsonObject body = new JsonObject();
            if (method.equals("POST") || method.equals("PUT")) {
                byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
                if (raw.length > MAX_BODY_BYTES) {
                    send(ex, 413, error("body exceeds 1 MiB")); return;
                }
                String s = new String(raw, StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) {
                    try {
                        JsonElement el = JsonParser.parseString(s);
                        if (!el.isJsonObject()) { send(ex, 400, error("body must be a JSON object")); return; }
                        body = el.getAsJsonObject();
                    } catch (Exception e) {
                        send(ex, 400, error("invalid JSON body")); return;
                    }
                }
            } else if (!method.equals("GET")) {
                send(ex, 405, error("use GET or POST")); return;
            }

            Request req = new Request(method, path, query, body);

            if (Dispatcher.isActiveStream(path)) {
                keepOpen = openStream(ex, req);
                return;
            }

            Dispatcher.Outcome out = Dispatcher.call(req, cfg.timeoutMs);
            if (out.png() != null) {
                ex.getResponseHeaders().set("Content-Type", "image/png");
                ex.getResponseHeaders().set("Cache-Control", "no-store");
                ex.sendResponseHeaders(200, out.png().length);
                ex.getResponseBody().write(out.png());
            } else send(ex, out.status(), toJson(out));
        } catch (Throwable t) {
            LocalApiMod.LOG.warn("[localapi] request error", t);
            try { send(ex, 500, error("internal error")); } catch (Throwable ignored) {}
        } finally {
            if (!keepOpen) ex.close();
        }
    }

    /** @return true if the stream started (the writer thread now owns the exchange) */
    private boolean openStream(HttpExchange ex, Request req) throws IOException {
        if (!StreamConnection.tryAcquire(cfg.maxStreams)) {
            send(ex, 429, error("too many open streams (max " + cfg.maxStreams + ", config maxStreams)"));
            return false;
        }
        StreamConnection sc = new StreamConnection();
        streams.add(sc);
        sc.onClose(() -> streams.remove(sc));
        Dispatcher.Outcome out;
        try {
            out = Dispatcher.openStream(req, sc);
        } catch (Throwable t) {
            sc.abort();
            StreamConnection.release();
            throw t;
        }
        if (!out.ok()) {
            sc.close();
            StreamConnection.release();
            send(ex, out.status(), toJson(out));
            return false;
        }
        try {
            sc.start(ex, Router.normalize(req.path())); // writer releases the slot when it ends
        } catch (IOException e) {
            sc.close();
            StreamConnection.release();
            throw e;
        }
        return true;
    }

    private boolean authorized(HttpExchange ex, Map<String, String> query) {
        String p = ex.getRequestHeaders().getFirst("X-Password");
        if (p == null) {
            String a = ex.getRequestHeaders().getFirst("Authorization");
            if (a != null && a.regionMatches(true, 0, "Bearer ", 0, 7)) p = a.substring(7).trim();
        }
        if (p == null) p = query.get("password");
        if (p == null) return false;
        return MessageDigest.isEqual(p.getBytes(StandardCharsets.UTF_8), password);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            String v = i < 0 ? "" : kv.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    public static JsonObject toJson(Dispatcher.Outcome out) {
        JsonObject o = new JsonObject();
        if (out.ok()) {
            o.addProperty("ok", true);
            o.add("data", out.data());
        } else {
            JsonObject err = error(out.error());
            if (out.extra() != null) for (var e : out.extra().entrySet()) err.add(e.getKey(), e.getValue());
            return err;
        }
        return o;
    }

    static JsonObject error(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", msg);
        return o;
    }

    private static void send(HttpExchange ex, int status, JsonElement body) throws IOException {
        byte[] b = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }
}


