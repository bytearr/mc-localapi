package console;

import localapi.api.LocalApiPlugin;
import localapi.api.PluginContext;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

/** Starts the bundled website. The mod only loads this jar; the server lives here. */
public final class ConsolePlugin implements LocalApiPlugin {
    @Override
    public void register(PluginContext ctx) {
        ConsoleServer.start(ctx.logger());
        ctx.status(ConsoleServer::status);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> ConsoleServer.stop());
    }
}
