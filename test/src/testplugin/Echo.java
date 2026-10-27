package testplugin;

import com.google.gson.JsonObject;
import localapi.api.*;

public final class Echo implements LocalApiPlugin {
    public static volatile String lastChange = "";
    public static volatile Options opts;

    @Override
    public void register(PluginContext ctx) {
        opts = ctx.options(java.util.List.of(
                OptionSpec.choice("mode", "Mode", java.util.List.of("A", "B", "OFF"), "A"),
                OptionSpec.integer("width", "Width", 80, 1000, 220),
                OptionSpec.number("fade", "Fade", 0.5, 60, 5.0),
                OptionSpec.text("model", "Model", "x/y.bin")), id -> System.setProperty("echo.lastChange", id));
        ctx.status(() -> java.util.List.of(StatusLine.ok("hello")));
        ctx.function("echo").title("Echo").suggestedPath("/t/echo").method("GET")
                .param("msg", ParamType.STRING, true, "text")
                .param("n", ParamType.INTEGER, "1", "count")
                .param("flag", ParamType.BOOLEAN, false, "flag")
                .onceOffGameThread(c -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("msg", c.string("msg"));
                    o.addProperty("n", c.integer("n"));
                    o.addProperty("flag", c.has("flag") ? String.valueOf(c.bool("flag")) : "unset");
                    return o;
                }).register();
        ctx.function("boom").suggestedPath("/t/boom").method("POST")
                .param("payload", ParamType.JSON, false, "any json")
                .onceOffGameThread(c -> { throw new IllegalStateException("kaputt"); }).register();
        ctx.function("gt").suggestedPath("/t/gt")
                .once(c -> { JsonObject o = new JsonObject(); o.addProperty("thread", Thread.currentThread().getName()); return o; })
                .register();
        ctx.function("needs").suggestedPath("/t/needs")
                .onceOffGameThread(c -> { JsonObject o = new JsonObject(); o.addProperty("x", new Missing().x()); return o; })
                .register();
        ctx.function("ticker").suggestedPath("/t/ticker")
                .param("count", ParamType.INTEGER, "5", "frames")
                .stream((c, sink) -> {
                    long n = c.integer("count");
                    Thread t = new Thread(() -> {
                        try {
                            for (int i = 1; i <= n && sink.isOpen(); i++) {
                                JsonObject o = new JsonObject();
                                o.addProperty("type", "tick");
                                o.addProperty("i", i);
                                sink.send(o);
                                Thread.sleep(40);
                            }
                        } catch (InterruptedException ignored) {}
                        sink.close();
                    });
                    t.setDaemon(true);
                    t.start();
                }).register();
        ctx.function("badopen").suggestedPath("/t/badopen")
                .stream((c, sink) -> { throw new IllegalArgumentException("cannot open: nope"); }).register();
    }
}
