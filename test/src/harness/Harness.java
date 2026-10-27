package harness;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import localapi.*;
import localapi.api.StreamSink;
import localapi.plugin.PluginInfo;
import localapi.plugin.PluginManager;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public class Harness {
    static int fails = 0;

    static String ov(PluginInfo p, String id) { return p.optionValues.get(id); }

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + (ok ? "" : "   -> " + detail));
        if (!ok) fails++;
    }

    static Dispatcher.Outcome call(String method, String path, String json, String query) {
        Map<String, String> q = new HashMap<>();
        if (query != null) for (String kv : query.split("&")) { String[] p = kv.split("=", 2); q.put(p[0], p.length > 1 ? p[1] : ""); }
        JsonObject body = json == null ? new JsonObject() : JsonParser.parseString(json).getAsJsonObject();
        return Dispatcher.call(new Request(method, path, q, body), 3000);
    }

    static PluginInfo plugin(String id) {
        for (PluginInfo p : PluginManager.plugins()) if (id.equals(p.id) || p.fileName.equals(id)) return p;
        throw new IllegalStateException("no plugin " + id);
    }

    public static void main(String[] a) throws Exception {
        Path root = Path.of(a[0]);                     // contains plugins/*.jar
        Set<String> mods = new HashSet<>(Arrays.asList(a[1].split(",")));
        // fake frame loop: this thread plays the "game thread"
        Thread game = new Thread(() -> { while (true) { GameQueue.drain(); try { Thread.sleep(16); } catch (Exception e) {} } }, "fake-game-thread");
        game.setDaemon(true); game.start();

        PluginManager.init(root.resolve("plugins"), mods::contains);
        PluginManager.ensureLoaded();

        System.out.println("---- plugin list");
        for (PluginInfo p : PluginManager.plugins()) System.out.println("  " + p.fileName + " -> " + p.reason());

        PluginInfo bad = plugin("nomanifest.jar");
        check("jar without manifest is INVALID and says what is missing", bad.status == PluginInfo.Status.INVALID && bad.reason().contains("missing localapi.plugin.json"), bad.reason());
        PluginInfo partial = plugin("partial.jar");
        check("manifest with missing fields lists them", partial.status == PluginInfo.Status.INVALID && partial.reason().contains("entrypoint") && partial.reason().contains("apiVersion"), partial.reason());
        PluginInfo mix = plugin("mixin.jar");
        check("jar with mixins is refused with reason", mix.status == PluginInfo.Status.INVALID && mix.reason().contains("mixin"), mix.reason());
        PluginInfo echo = plugin("echo");
        check("unknown jar waits for approval, code not run", echo.status == PluginInfo.Status.NOT_APPROVED && echo.functions.isEmpty(), echo.reason());

        check("approve loads it (hot-load)", PluginManager.approve(echo) == null && echo.isLoaded() && echo.functions.size() == 6, echo.reason() + " fns=" + echo.functions.keySet());

        PluginInfo dep = plugin("dep");
        PluginManager.approve(dep);
        check("missing mod -> MISSING_DEPENDENCY with name", dep.status == PluginInfo.Status.MISSING_DEPENDENCY && dep.reason().contains("nope"), dep.reason());
        PluginInfo mc = plugin("missingclass");
        PluginManager.approve(mc);
        check("missing class at load -> MISSING_DEPENDENCY, not a crash", mc.status == PluginInfo.Status.MISSING_DEPENDENCY, mc.reason());

        System.out.println("---- saving paths");
        String r;
        r = PluginManager.saveFunction(echo, "echo", true, "/state");
        check("core path rejected with reason", r != null && r.contains("core route"), String.valueOf(r));
        r = PluginManager.saveFunction(echo, "echo", true, "/ping");
        check("/ping rejected", r != null, String.valueOf(r));
        r = PluginManager.saveFunction(echo, "echo", true, "state");
        check("path without / rejected", r != null && r.contains("start with"), String.valueOf(r));
        r = PluginManager.saveFunction(echo, "echo", true, "/a/../b");
        check("dot-dot rejected", r != null, String.valueOf(r));
        r = PluginManager.saveFunction(echo, "echo", true, "");
        check("enable without path rejected", r != null, String.valueOf(r));
        check("echo path saved", PluginManager.saveFunction(echo, "echo", true, "/my/echo") == null, "");
        r = PluginManager.saveFunction(echo, "gt", true, "/my/echo");
        check("duplicate plugin path rejected naming the owner", r != null && r.contains("echo"), String.valueOf(r));
        PluginManager.saveFunction(echo, "boom", true, "/my/boom");
        PluginManager.saveFunction(echo, "gt", true, "/my/gt");
        PluginManager.saveFunction(echo, "needs", true, "/my/needs");
        PluginManager.saveFunction(echo, "ticker", true, "/my/ticker");
        PluginManager.saveFunction(echo, "badopen", true, "/my/badopen");

        System.out.println("---- calls");
        Dispatcher.Outcome o = call("GET", "/my/echo", null, "msg=hi&n=7");
        check("200 ok with typed params from query", o.ok() && o.data().getAsJsonObject().get("n").getAsLong() == 7, o.error());
        o = call("GET", "/my/echo", null, "n=7");
        check("400 missing param names it", o.status() == 400 && o.error().contains("'msg'") && o.error().contains("string"), o.status() + " " + o.error());
        o = call("GET", "/my/echo", null, "msg=a&n=abc");
        check("400 wrong type names param and type", o.status() == 400 && o.error().contains("'n'") && o.error().contains("integer"), o.status() + " " + o.error());
        o = call("GET", "/my/echo", "{\"msg\":5}", null);
        check("400 body number where string expected (handler did not run)", o.status() == 400, o.status() + " " + o.error());
        o = call("POST", "/my/echo", "{\"msg\":\"x\"}", null);
        check("405 wrong method", o.status() == 405, o.status() + " " + o.error());
        o = call("POST", "/my/boom", null, null);
        check("500 with the handler's message", o.status() == 500 && o.error().equals("kaputt"), o.status() + " " + o.error());
        o = call("POST", "/my/boom", "{\"payload\":3}", null);
        check("400 json param given a number", o.status() == 400 && o.error().contains("json"), o.status() + " " + o.error());
        o = call("GET", "/my/gt", null, null);
        check("game-thread function runs on the (fake) game thread", o.ok() && o.data().getAsJsonObject().get("thread").getAsString().equals("fake-game-thread"), String.valueOf(o.data()));
        o = call("GET", "/my/needs", null, null);
        check("missing class at call time -> 409", o.status() == 409, o.status() + " " + o.error());
        o = call("GET", "/nope/path", null, null);
        check("404 lists real paths", o.status() == 404 && o.extra() != null && o.extra().getAsJsonArray("paths").toString().contains("/my/echo") && o.extra().getAsJsonArray("paths").toString().contains("/state"), o.status() + " " + o.extra());
        PluginManager.saveFunction(echo, "echo", false, "/my/echo");
        o = call("GET", "/my/echo", null, "msg=a");
        check("function off -> 409 saying it is off", o.status() == 409 && o.error().contains("turned off"), o.status() + " " + o.error());
        o = call("GET", "/state", null, null);
        check("core route still answers through the same dispatcher (504 here: no game, queue only)", o.status() == 500 || o.status() == 409 || o.status() == 504, o.status() + " " + o.error());

        System.out.println("---- options");
        check("defaults applied before register returns", ov(echo, "mode").equals("A") && ov(echo, "width").equals("220") && ov(echo, "fade").equals("5.0") && ov(echo, "model").equals("x/y.bin"), "");
        check("status lines come from the plugin", echo.statusLines != null && echo.statusLines.get().get(0).text().equals("hello"), "");
        check("choice outside the list rejected", PluginManager.setOption(echo, "mode", "C") != null && ov(echo, "mode").equals("A"), "");
        check("integer below min rejected with range", String.valueOf(PluginManager.setOption(echo, "width", "79")).contains("80 to 1000"), "");
        check("integer not a number rejected", PluginManager.setOption(echo, "width", "abc") != null, "");
        check("valid integer saved, plugin told", PluginManager.setOption(echo, "width", "300") == null && ov(echo, "width").equals("300") && System.getProperty("echo.lastChange").equals("width"), System.getProperty("echo.lastChange"));
        check("number accepts comma", PluginManager.setOption(echo, "fade", "2,5") == null && ov(echo, "fade").equals("2.5"), "");
        check("choice saved", PluginManager.setOption(echo, "mode", "OFF") == null && ov(echo, "mode").equals("OFF"), "");
        check("unchanged value does not call plugin again", System.setProperty("echo.lastChange", "") != null && PluginManager.setOption(echo, "mode", "OFF") == null && System.getProperty("echo.lastChange").isEmpty(), "");
        check("unknown option rejected", PluginManager.setOption(echo, "nope", "1") != null, "");
        String saved = java.nio.file.Files.readString(root.resolve("plugins").resolve("echo.json"));
        check("options are in plugins/<id>.json next to functions", saved.contains("\"options\"") && saved.contains("\"width\": 300") && saved.contains("\"mode\": \"OFF\"") && saved.contains("\"functions\""), saved.replace("\n", " "));

        System.out.println("---- streams");
        AtomicInteger frames = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean();
        StreamSink s = new StreamSink() {
            volatile boolean open = true; final List<Runnable> cl = new ArrayList<>();
            public boolean send(JsonObject l) { frames.incrementAndGet(); return open; }
            public boolean isOpen() { return open; }
            public void close() { open = false; closed.set(true); cl.forEach(Runnable::run); }
            public void onClose(Runnable rr) { cl.add(rr); }
        };
        check("isActiveStream true for enabled stream path", Dispatcher.isActiveStream("/my/ticker"), "");
        o = Dispatcher.openStream(new Request("GET", "/my/ticker", Map.of("count", "5"), new JsonObject()), s);
        check("stream opens", o.ok(), o.error());
        Thread.sleep(500);
        check("5 frames arrived and stream closed by plugin", frames.get() == 5 && closed.get(), frames + " closed=" + closed);
        o = Dispatcher.openStream(new Request("GET", "/my/badopen", Map.of(), new JsonObject()), s);
        check("open() throwing -> 500 with message", o.status() == 500 && o.error().contains("nope"), o.status() + " " + o.error());
        o = Dispatcher.openStream(new Request("GET", "/my/ticker", Map.of("count", "x"), new JsonObject()), s);
        check("stream param type error -> 400", o.status() == 400, o.status() + " " + o.error());
        check("errors recorded in plugin UI list", !echo.recentErrors().isEmpty(), "");

        // leave the HTTP layer up for curl
        Config c = new Config(); c.port = 25581; c.maxStreams = 2;
        new ApiServer(c).start();
        System.out.println("---- HTTP up on 25581 (waiting)");
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        Thread.sleep(Long.parseLong(a[2]));
        System.exit(fails == 0 ? 0 : 1);
    }
}

