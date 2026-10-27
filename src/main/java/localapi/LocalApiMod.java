package localapi;

import localapi.plugin.PluginManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalApiMod implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("localapi");
    public static volatile Config CONFIG;
    public static volatile ApiServer SERVER;
    private ApiServer server;
    private static boolean pluginsInitialised;

    /** Idempotent. Voice Chat may call the bridge before this mod initializes, so both paths come here. */
    public static synchronized void initPlugins() {
        if (pluginsInitialised) return;
        pluginsInitialised = true;
        PluginManager.init(FabricLoader.getInstance().getConfigDir().resolve("localapi").resolve("plugins"),
                id -> FabricLoader.getInstance().isModLoaded(id));
        PluginManager.ensureLoaded();
    }

    @Override
    public void onInitializeClient() {
        Config cfg = Config.load();
        CONFIG = cfg;
        GameQueue.budgetNanos = cfg.frameBudgetMs * 1_000_000L;

        ClientTickEvents.START_CLIENT_TICK.register(Keys::onStartTick);
        ClientTickEvents.END_CLIENT_TICK.register(Keys::onEndTick);
        // Never leave a key stuck down when the world goes away.
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            Keys.releaseAll();
            if (server != null) server.stop();
        });
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) ->
                ChatLog.add("chat", sender == null ? null : sender.name(), msg.getString()));
        ClientReceiveMessageEvents.GAME.register((msg, overlay) ->
                ChatLog.add(overlay ? "actionbar" : "system", null, msg.getString()));

        initPlugins();

        try {
            server = new ApiServer(cfg);
            SERVER = server;
            server.start();
            LOG.info("[localapi] listening on http://127.0.0.1:{} (password from config/localapi.json)", cfg.port);
            if (cfg.password.equals("Test123"))
                LOG.warn("[localapi] password is the default 'Test123' - fine for testing, change it in config/localapi.json");
        } catch (Exception e) {
            LOG.error("[localapi] could not start on port {}: {}", cfg.port, e.toString());
        }
    }
}


