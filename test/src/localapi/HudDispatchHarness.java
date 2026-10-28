package localapi;

import com.google.gson.*;
import localapi.api.StreamSink;
import java.util.*;

/** Exercises the registered route policies, real dispatch, batch dispatch and event flushing.
 * World-dependent handlers are replaced with inert successes; no graphical client is needed. */
public final class HudDispatchHarness {
    static int checks, calls;
    static void check(boolean value, String name) { if (!value) throw new AssertionError(name); checks++; }
    static Request request(String method, String path, String body) {
        return new Request(method, path, Map.of(), JsonParser.parseString(body).getAsJsonObject());
    }
    static final class Sink implements StreamSink {
        final List<JsonObject> events = new ArrayList<>(); Runnable closer = () -> {};
        public boolean send(JsonObject e) { events.add(e.deepCopy()); return true; }
        public boolean isOpen() { return true; }
        public void close() { closer.run(); }
        public void onClose(Runnable r) { closer = r; }
    }
    static void silent(Sink sink, String name) { SessionEvents.INSTANCE.flush(); check(sink.events.isEmpty(), name); }
    static void changed(Sink sink, String name) {
        SessionEvents.INSTANCE.flush(); check(sink.events.size() == 1, name + " single coalesced event");
        var event = sink.events.get(0);
        check(event.get("type").getAsString().equals("state_changed") && event.get("resync").getAsBoolean(), name + " resync");
        var paths = event.getAsJsonArray("paths");
        for (String path : List.of("/state", "/player", "/inventory", "/container", "/screen", "/target", "/hand"))
            check(paths.contains(new JsonPrimitive(path)), name + " " + path);
        sink.events.clear();
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var f = Router.class.getDeclaredField("ROUTES"); f.setAccessible(true);
        Map<String,Object> routes = (Map<String,Object>)f.get(null);
        Map<String,Object> original = new LinkedHashMap<>(routes);
        var type = Class.forName("localapi.Router$Route");
        var ctor = type.getDeclaredConstructor(boolean.class, boolean.class, Router.Handler.class); ctor.setAccessible(true);
        var mutating = type.getDeclaredMethod("mutating"); mutating.setAccessible(true);
        var invalidates = type.getDeclaredMethod("invalidatesGameState"); invalidates.setAccessible(true);
        var sink = new Sink(); var events = SessionEvents.INSTANCE;
        try {
            // Preserve production registration flags; substitute only handlers needing Minecraft.
            for (String path : List.of("/hud/owner/create", "/hud/owner/renew", "/hud/widgets/put", "/hotbar")) {
                Object route = routes.get(path);
                Router.Handler success = r -> { calls++; return new JsonObject(); };
                routes.put(path, ctor.newInstance(mutating.invoke(route), invalidates.invoke(route), success));
            }
            events.begin("test"); events.subscribe(sink); sink.events.clear();
            for (String path : List.of("/hud/owner/create", "/hud/owner/renew", "/hud/widgets/put")) {
                Router.dispatch(request("POST", path, "{}")); silent(sink, path + " no invalidation");
                Router.dispatch(request("POST", path + "/", "{}")); silent(sink, path + " trailing slash no invalidation");
            }
            for (String path : List.of("/hud/owner/create", "/hud/owner/renew", "/hud/owner/release", "/hud/widgets/put", "/hud/widgets/remove", "/batch")) {
                int before = calls;
                try { Router.dispatch(request("GET", path, "{}")); throw new AssertionError("GET accepted"); }
                catch (HttpError e) { check(e.status == 405, path + " still POST-only"); }
                check(calls == before, "method rejection never invokes handler");
            }
            String owner = HudWidgets.INSTANCE.create(request("POST", "", "{}")).get("owner").getAsString();
            String body = "{\"owner\":\"" + owner + "\",\"id\":\"counter\"}";
            Router.dispatch(request("POST", "/hud/widgets/remove", body)); silent(sink, "real remove no invalidation");
            Router.dispatch(request("POST", "/hud/owner/release", body)); silent(sink, "real release no invalidation");
            try { Router.dispatch(request("POST", "/hud/owner/release", body)); throw new AssertionError(); }
            catch (HttpError e) { check(e.status == 404, "expired owner error preserved"); }
            silent(sink, "failed HUD operation silent");
            Router.dispatch(request("POST", "/batch", "{\"requests\":[{\"path\":\"/hud/owner/renew\"},{\"path\":\"/hud/widgets/put/\"}]}"));
            silent(sink, "HUD-only batch silent");
            Router.dispatch(request("POST", "/batch", "{\"requests\":[]}")); silent(sink, "empty batch silent");
            Router.dispatch(request("POST", "/batch", "{\"requests\":[{\"path\":\"/hud/owner/release\",\"body\":{\"owner\":\"missing\"}}]}"));
            silent(sink, "failed HUD-only batch silent");
            Router.dispatch(request("POST", "/hotbar", "{}")); changed(sink, "game action");
            Router.dispatch(request("POST", "/batch", "{\"requests\":[{\"path\":\"/hud/owner/renew\"},{\"path\":\"/hotbar\"},{\"path\":\"/hud/widgets/put\"}]}"));
            changed(sink, "mixed batch");
            ClientEvents.invalidate("/inventory");
            Router.dispatch(request("POST", "/hud/owner/renew", "{}")); events.flush();
            check(sink.events.size() == 1 && sink.events.get(0).getAsJsonArray("paths").contains(new JsonPrimitive("/inventory")), "HUD does not erase pending game changes");
        } finally { routes.clear(); routes.putAll(original); sink.close(); events.end(); HudWidgets.INSTANCE.clear(); }
        System.out.println("PASS " + checks + " HUD dispatch/event checks");
    }
}
