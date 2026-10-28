package console;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import localapi.api.StatusLine;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The same console the Python server served: files from this jar, /skin from Mojang,
 * /api proxied to the mod without an Origin header and with the password from config/localapi.json.
 * The password never goes to the page. Browsers are still rejected by the mod itself.
 */
public final class ConsoleServer {
    static final int PORT = 8088;
    private static final int MAX_BODY = 1 << 20;
    private static final int MAX_RESPONSE = 4_000_000;
    private static final Duration CONNECT = Duration.ofSeconds(5);
    private static final long SKIN_TTL_MS = 600_000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final Map<String, byte[]> SKINS = new ConcurrentHashMap<>();
    private static final Map<String, Long> SKIN_AT = new ConcurrentHashMap<>();

    private static final Object LOCK = new Object();
    private static volatile Logger log = LoggerFactory.getLogger("localapi/console");
    private static volatile Map<String, Asset> files = Map.of();
    private static volatile List<StatusLine> lines = List.of(StatusLine.info("Konsole startet"));
    private static volatile HttpServer server;
    private static volatile ExecutorService workers;
    private static volatile Thread binder;
    private static volatile boolean stopped;

    private ConsoleServer() {}

    static void start(Logger logger) {
        log = logger == null ? log : logger;
        if (binder != null) return;
        try {
            files = loadFiles();
        } catch (IOException e) {
            setStatus(StatusLine.error("Seite fehlt im Jar: " + e.getMessage()));
            log.warn("console page missing: {}", e.toString());
            return;
        }
        Thread t = new Thread(ConsoleServer::bindLoop, "localapi-console");
        t.setDaemon(true);
        binder = t;
        Runtime.getRuntime().addShutdownHook(new Thread(ConsoleServer::stop, "localapi-console-stop"));
        t.start();
    }

    static void stop() {
        HttpServer s;
        ExecutorService ex;
        Thread t;
        synchronized (LOCK) {
            stopped = true;
            t = binder;
            s = server;
            server = null;
            ex = workers;
            workers = null;
        }
        if (t != null) t.interrupt();
        if (s != null) s.stop(0);
        if (ex != null) ex.shutdownNow();
    }

    static List<StatusLine> status() {
        return lines;
    }

    private static void bindLoop() {
        while (!stopped) {
            HttpServer created = null;
            ExecutorService pool = null;
            try {
                created = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 0);
                pool = Executors.newCachedThreadPool(r -> {
                    Thread w = new Thread(r, "localapi-console-http");
                    w.setDaemon(true);
                    return w;
                });
                created.setExecutor(pool);
                created.createContext("/", ConsoleServer::handle);
                synchronized (LOCK) {
                    if (stopped) {
                        created.stop(0);
                        pool.shutdownNow();
                        return;
                    }
                    created.start();
                    server = created;
                    workers = pool;
                }
                setStatus(StatusLine.ok("http://127.0.0.1:" + PORT + "/"));
                log.info("console http://127.0.0.1:{}/", PORT);
                return;
            } catch (Exception e) {
                if (created != null) created.stop(0);
                if (pool != null) pool.shutdownNow();
                if (stopped) return;
                setStatus(StatusLine.warn("8088 ist belegt, warte bis der alte Server weg ist"));
                log.warn("console port {} busy: {}", PORT, e.toString());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static void handle(HttpExchange ex) {
        try {
            if (ex.getRemoteAddress() == null || ex.getRemoteAddress().getAddress() == null
                    || !ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
                respond(ex, 403, "application/json; charset=utf-8", err("forbidden"));
                return;
            }
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if (path == null || path.isEmpty()) path = "/";
            if ("GET".equals(method) && files.containsKey(path)) {
                Asset asset = files.get(path);
                respond(ex, 200, asset.type, asset.data);
                return;
            }
            if (path.startsWith("/api/") && ("GET".equals(method) || "POST".equals(method))) {
                proxy(ex, method);
                return;
            }
            if ("GET".equals(method) && path.startsWith("/skin/")) {
                skin(ex, path.substring("/skin/".length()));
                return;
            }
            int status = "GET".equals(method) || "POST".equals(method) ? 404 : 405;
            respond(ex, status, "application/json; charset=utf-8", err(status == 404 ? "not found" : "method"));
        } catch (Exception e) {
            log.warn("console request failed: {}", e.toString());
            try {
                if (!headersSent(ex)) respond(ex, 500, "application/json; charset=utf-8", err("internal"));
            } catch (IOException ignored) {}
        } finally {
            ex.close();
        }
    }

    private static void proxy(HttpExchange ex, String method) throws IOException {
        String raw = ex.getRequestURI().getRawPath();
        if (raw == null || !raw.startsWith("/api/")) {
            respond(ex, 400, "application/json; charset=utf-8", err("bad path"));
            return;
        }
        String rest = raw.substring(4);
        String query = ex.getRequestURI().getRawQuery();
        if (!rest.startsWith("/") || rest.startsWith("//") || rest.indexOf('\\') >= 0 || rest.contains("..")) {
            respond(ex, 400, "application/json; charset=utf-8", err("bad path"));
            return;
        }
        byte[] body = null;
        if ("POST".equals(method)) {
            body = ex.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                respond(ex, 413, "application/json; charset=utf-8", err("body too large"));
                return;
            }
        }
        String route = rest;
        Api api = api();
        String url = "http://127.0.0.1:" + api.port + rest + (query == null || query.isEmpty() ? "" : "?" + query);
        HttpRequest.Builder b;
        try {
            b = HttpRequest.newBuilder(URI.create(url)).header("X-Password", api.password).header("Accept", "*/*");
        } catch (IllegalArgumentException e) {
            respond(ex, 400, "application/json; charset=utf-8", err("bad path"));
            return;
        }
        if ("/events".equals(route) && "GET".equals(method)) {
            stream(ex, b.GET().build());
            return;
        }
        int seconds = "/surface".equals(route) ? 20 : 8;
        if ("POST".equals(method)) {
            b.header("Content-Type", "application/json");
            b.POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
        } else {
            b.GET();
        }
        b.timeout(Duration.ofSeconds(seconds));
        try {
            HttpResponse<byte[]> res = HTTP.send(b.build(), limited(MAX_RESPONSE));
            respond(ex, res.statusCode(), contentType(res.headers().firstValue("Content-Type").orElse("application/json")), res.body());
        } catch (HttpTimeoutException e) {
            respond(ex, 200, "application/json; charset=utf-8",
                    ("{\"stream\":false,\"note\":\"Verbindung nach kurzer Zeit geschlossen, damit die Seite nicht haengt.\"}")
                            .getBytes(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            respond(ex, 502, "application/json; charset=utf-8", err("client nicht erreichbar"));
        } catch (Exception e) {
            respond(ex, 502, "application/json; charset=utf-8",
                    err("client nicht erreichbar", e.getMessage()));
        }
    }

    private static void stream(HttpExchange ex, HttpRequest req) throws IOException {
        HttpResponse<InputStream> res;
        try {
            res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            respond(ex, 502, "application/json; charset=utf-8", err("client nicht erreichbar"));
            return;
        } catch (Exception e) {
            respond(ex, 502, "application/json; charset=utf-8", err("client nicht erreichbar", e.getMessage()));
            return;
        }
        try (InputStream in = res.body()) {
            if (res.statusCode() != 200) {
                respond(ex, res.statusCode(), contentType(res.headers().firstValue("Content-Type").orElse("application/json")),
                        in.readNBytes(MAX_RESPONSE));
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            if (!headersSent(ex)) throw e;
        }
    }

    private static void skin(HttpExchange ex, String uuid) throws IOException {
        if (uuid.isEmpty() || uuid.indexOf('/') >= 0 || !uuid.chars().allMatch(c ->
                (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '-')) {
            respond(ex, 400, "application/json; charset=utf-8", err("bad uuid"));
            return;
        }
        String key = uuid.replace("-", "").toLowerCase();
        Long at = SKIN_AT.get(key);
        byte[] cached = SKINS.get(key);
        if (at != null && cached != null && System.currentTimeMillis() - at < SKIN_TTL_MS) {
            respond(ex, 200, "image/png", cached);
            return;
        }
        try {
            byte[] png = fetchSkin(key);
            SKINS.put(key, png);
            SKIN_AT.put(key, System.currentTimeMillis());
            respond(ex, 200, "image/png", png);
        } catch (Exception e) {
            respond(ex, 502, "application/json; charset=utf-8", err("skin nicht geladen", e.getMessage()));
        }
    }

    private static byte[] fetchSkin(String key) throws Exception {
        HttpRequest profileReq = HttpRequest.newBuilder(URI.create(
                        "https://sessionserver.mojang.com/session/minecraft/profile/" + key))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "localapi-console")
                .GET().build();
        HttpResponse<String> profileRes = HTTP.send(profileReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (profileRes.statusCode() != 200) throw new IOException("HTTP " + profileRes.statusCode());
        JsonObject profile = JsonParser.parseString(profileRes.body()).getAsJsonObject();
        String raw = null;
        JsonArray props = profile.getAsJsonArray("properties");
        if (props != null) {
            for (JsonElement el : props) {
                JsonObject p = el.getAsJsonObject();
                if (p.has("name") && "textures".equals(p.get("name").getAsString()) && p.has("value"))
                    raw = p.get("value").getAsString();
            }
        }
        if (raw == null) throw new IOException("no textures");
        int mod = raw.length() % 4;
        if (mod != 0) raw = raw + "=".repeat(4 - mod);
        JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8)).getAsJsonObject();
        String url = textures.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString().replace("http://", "https://");
        URI skinUri = URI.create(url);
        if (!"https".equalsIgnoreCase(skinUri.getScheme()) || skinUri.getHost() == null)
            throw new IOException("skin host refused");
        InetAddress addr = InetAddress.getByName(skinUri.getHost());
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress())
            throw new IOException("skin host refused");
        HttpRequest img = HttpRequest.newBuilder(skinUri).timeout(Duration.ofSeconds(8))
                .header("User-Agent", "localapi-console").GET().build();
        HttpResponse<byte[]> imgRes = HTTP.send(img, limited(MAX_BODY));
        if (imgRes.statusCode() != 200) throw new IOException("HTTP " + imgRes.statusCode());
        byte[] png = imgRes.body();
        if (png.length < 8 || (png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G')
            throw new IOException("not a png");
        return png;
    }

    private static HttpResponse.BodyHandler<byte[]> limited(int max) {
        return responseInfo -> HttpResponse.BodySubscribers.mapping(
                HttpResponse.BodySubscribers.ofInputStream(), in -> {
                    try (in) {
                        byte[] got = in.readNBytes(max + 1);
                        if (got.length > max) throw new IOException("response too large");
                        return got;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private static Api api() {
        int port = 25580;
        String password = "Test123";
        try {
            var path = FabricLoader.getInstance().getConfigDir().resolve("localapi.json");
            if (Files.exists(path)) {
                JsonObject o = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (o.has("port") && !o.get("port").isJsonNull()) port = o.get("port").getAsInt();
                if (o.has("password") && !o.get("password").isJsonNull()) password = o.get("password").getAsString();
            }
        } catch (Exception e) {
            log.warn("console config unreadable, using defaults: {}", e.toString());
        }
        if (port < 1 || port > 65535) port = 25580;
        if (password == null || password.isEmpty()) password = "Test123";
        return new Api(port, password);
    }

    private static Map<String, Asset> loadFiles() throws IOException {
        Map<String, Asset> out = new LinkedHashMap<>();
        Asset html = asset("index.html", "text/html; charset=utf-8");
        out.put("/", html);
        out.put("/index.html", html);
        out.put("/styles.css", asset("styles.css", "text/css; charset=utf-8"));
        out.put("/app.js", asset("app.js", "text/javascript; charset=utf-8"));
        out.put("/skinview3d.bundle.js", asset("skinview3d.bundle.js", "text/javascript; charset=utf-8"));
        out.put("/monocraft.ttf", asset("monocraft.ttf", "font/ttf"));
        return Map.copyOf(out);
    }

    private static Asset asset(String name, String type) throws IOException {
        try (InputStream in = ConsoleServer.class.getResourceAsStream("/web/" + name)) {
            if (in == null) throw new IOException(name);
            return new Asset(type, in.readAllBytes());
        }
    }

    private static String contentType(String header) {
        String kind = header.split(";", 2)[0].trim().toLowerCase();
        if (kind.equals("image/png")) return "image/png";
        if (!header.toLowerCase().contains("charset")) return kind + "; charset=utf-8";
        return header;
    }

    private static void respond(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        byte[] data = body == null ? new byte[0] : body;
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }

    private static boolean headersSent(HttpExchange ex) {
        try {
            return ex.getResponseCode() >= 0;
        } catch (NoSuchMethodError e) {
            return false;
        }
    }

    private static byte[] err(String message) {
        return err(message, null);
    }

    private static byte[] err(String message, String detail) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        if (detail != null && !detail.isEmpty()) o.addProperty("detail", detail);
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void setStatus(StatusLine line) {
        lines = List.of(line);
    }

    private record Asset(String type, byte[] data) {}

    private record Api(int port, String password) {}

}
