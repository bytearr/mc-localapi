package localapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import localapi.api.ParamType;
import localapi.plugin.Params;
import localapi.plugin.PluginFunction;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** No Minecraft instance required. Run with test/run-test.ps1 after build.ps1. */
public final class RegressionHarness {
    private static int checks;

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }

    private static void bad(Runnable action, String name) {
        try { action.run(); }
        catch (HttpError e) { check(e.status == 400, name); return; }
        throw new AssertionError("accepted " + name);
    }

    private static Request request(String json) {
        return new Request("POST", "/test", Map.of(), JsonParser.parseString(json).getAsJsonObject());
    }

    private static PluginFunction function(String id) throws Exception {
        var constructor = PluginFunction.class.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance("test", id);
    }

    private static void parameters() throws Exception {
        check(request("{\"x\":-1.2}").reqInt("x") == -2, "negative integer still floors");
        check(request("{\"x\":1.9}").reqInt("x") == 1, "positive integer still floors");
        check(request("{\"x\":2147483647}").reqInt("x") == Integer.MAX_VALUE, "int maximum");
        check(request("{\"x\":-2147483648}").reqInt("x") == Integer.MIN_VALUE, "int minimum");
        check(new Request("GET", "/test", Map.of("x", "4"), new JsonObject()).reqInt("x") == 4, "query parameter");
        check(new Request("GET", "/test", Map.of("x", "4"), request("{\"x\":5}").body()).reqInt("x") == 5, "body precedence");
        for (String value : new String[]{"\"NaN\"", "\"Infinity\"", "1e309", "{}", "[]"})
            bad(() -> request("{\"x\":" + value + "}").reqDbl("x"), "invalid number " + value);
        for (String value : new String[]{"2147483648", "-2147483649", "1e100"})
            bad(() -> request("{\"x\":" + value + "}").reqInt("x"), "int overflow " + value);

        PluginFunction integer = function("integer");
        integer.params.add(new PluginFunction.Param("x", ParamType.INTEGER, true, null, ""));
        check(Params.bind(integer, request("{\"x\":9223372036854775807}")).integer("x") == Long.MAX_VALUE, "long maximum exact");
        check(Params.bind(integer, request("{\"x\":-9223372036854775808}")).integer("x") == Long.MIN_VALUE, "long minimum exact");
        check(Params.bind(integer, request("{\"x\":1e3}")).integer("x") == 1000, "integral exponent");
        for (String value : new String[]{"9223372036854775808", "-9223372036854775809", "1.00000000000000001", "1e100", "\"5\""})
            bad(() -> Params.bind(integer, request("{\"x\":" + value + "}")), "invalid plugin integer " + value);
        PluginFunction number = function("number");
        number.params.add(new PluginFunction.Param("x", ParamType.NUMBER, true, null, ""));
        bad(() -> Params.bind(number, request("{\"x\":1e309}")), "plugin infinite body");
        bad(() -> Params.bind(number, new Request("GET", "/test", Map.of("x", "Infinity"), new JsonObject())), "plugin infinite query");
        check(Params.bind(number, request("{\"x\":0.125}")).number("x") == 0.125, "finite plugin number");
    }

    private static void regions() {
        check(new BlockRegion(0, 31, 0, 31, 0, 31).volume() == 32768, "region at limit");
        check(new BlockRegion(0, 0, 10, 9, 0, 0).volume() == 0, "empty height intersection");
        check(new BlockRegion(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0, 0, 0).volume() == 1, "max coordinate single block");
        bad(() -> new BlockRegion(0, 32, 0, 31, 0, 31).volume(), "oversize region");
        bad(() -> new BlockRegion(Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 1, 0, 1).volume(), "int span overflow");
        bad(() -> new BlockRegion(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE).volume(), "long product overflow");
    }

    private static void batches() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BatchRequests.Handler handler = r -> { calls.incrementAndGet(); return r.body(); };
        for (String path : new String[]{"/batch", "/batch/", "/screenshot", "/screenshot/"}) {
            JsonArray out = (JsonArray) BatchRequests.dispatch(request("{\"requests\":[{\"path\":\"" + path + "\"}]}"), handler);
            check(!out.get(0).getAsJsonObject().get("ok").getAsBoolean(), "blocked batch path " + path);
        }
        check(calls.get() == 0, "blocked routes never dispatch");
        JsonArray out = (JsonArray) BatchRequests.dispatch(request("{\"requests\":[null,3,{}, {\"path\":3}, {\"path\":\"/x\",\"body\":null}, {\"path\":\"/x\",\"body\":[]}, {\"path\":\"/x\",\"body\":{\"n\":7}}]}"), handler);
        check(out.size() == 7 && calls.get() == 1, "malformed items isolated; valid item still executes");
        for (int i = 0; i < 6; i++) check(!out.get(i).getAsJsonObject().get("ok").getAsBoolean(), "batch item error " + i);
        check(out.get(6).getAsJsonObject().getAsJsonObject("data").get("n").getAsInt() == 7, "batch result preserved");
    }

    private static void cancellation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<Integer> future = GameQueue.submit(calls::incrementAndGet);
        var await = Dispatcher.class.getDeclaredMethod("await", CompletableFuture.class, int.class, localapi.plugin.PluginInfo.class, String.class);
        await.setAccessible(true);
        Thread.currentThread().interrupt();
        try {
            Dispatcher.Outcome result = (Dispatcher.Outcome) await.invoke(null, future, 1000, null, "/test");
            check(result.status() == 500 && Thread.currentThread().isInterrupted(), "interruption preserved");
            check(future.isCancelled(), "interruption cancels future");
        } finally { Thread.interrupted(); }
        GameQueue.drain();
        check(calls.get() == 0, "cancelled action never executes");
    }

    private static void http() throws Exception {
        Config cfg = new Config();
        try (ServerSocket socket = new ServerSocket(0)) { cfg.port = socket.getLocalPort(); }
        ApiServer server = new ApiServer(cfg);
        HttpClient client = HttpClient.newHttpClient();
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + cfg.port + "/missing-regression-route");
            String atLimit = "{}" + " ".repeat((1 << 20) - 2);
            for (int extra = 0; extra <= 1; extra++) {
                var req = HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(10))
                        .header("X-Password", cfg.password)
                        .POST(HttpRequest.BodyPublishers.ofString(atLimit + " ".repeat(extra))).build();
                var response = client.send(req, HttpResponse.BodyHandlers.ofString());
                check(response.statusCode() == (extra == 0 ? 404 : 413), "HTTP body limit +" + extra);
                check(!JsonParser.parseString(response.body()).getAsJsonObject().get("ok").getAsBoolean(), "HTTP JSON error +" + extra);
            }
            var ping = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + cfg.port + "/ping")).build(), HttpResponse.BodyHandlers.ofString());
            check(ping.statusCode() == 200, "unauthenticated ping still works");
            int listeningPort = cfg.port;
            cfg.port = listeningPort == 65535 ? 25580 : listeningPort + 1;
            var afterPortEdit = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + listeningPort + "/ping")).build(), HttpResponse.BodyHandlers.ofString());
            check(afterPortEdit.statusCode() == 200, "editing next-start port does not reject current Host");
        } finally { server.stop(); }
        var field = ApiServer.class.getDeclaredField("executor");
        field.setAccessible(true);
        var executor = (java.util.concurrent.ExecutorService) field.get(server);
        check(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS), "HTTP executor terminates");
    }

    public static void main(String[] args) throws Exception {
        parameters(); regions(); batches(); cancellation(); http();
        System.out.println("PASS " + checks + " regression checks");
    }
}
