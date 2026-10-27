package localapi.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import localapi.LocalApiMod;
import localapi.plugin.PluginInfo;
import localapi.plugin.PluginManager;

/**
 * The ONLY class in the core that knows Voice Chat. It has no audio code. Voice Chat finds it through
 * the "voicechat" entrypoint in fabric.mod.json and calls it once at its own start. It makes sure the
 * plugin jars are loaded, then hands the very same EventRegistration / VoicechatApi on to every plugin
 * that declared a "voicechatPlugin" class. The plugin registers its own listeners there.
 *
 * Nothing else references this class, and fabric.mod.json does not depend on Voice Chat, so without the
 * Voice Chat mod it is never loaded.
 */
public final class VoiceBridge implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "localapi";
    }

    @Override
    public void initialize(VoicechatApi api) {
        LocalApiMod.initPlugins();
        for (Object o : PluginManager.voicePlugins()) {
            try {
                ((VoicechatPlugin) o).initialize(api);
            } catch (Throwable t) {
                fail(o, "initialize", t);
            }
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        LocalApiMod.initPlugins();
        for (Object o : PluginManager.voicePlugins()) {
            try {
                ((VoicechatPlugin) o).registerEvents(registration);
            } catch (Throwable t) {
                fail(o, "registerEvents", t);
            }
        }
        // Plugins approved after this point cannot register listeners until the next client start.
        PluginManager.voiceRegistrationDone = true;
    }

    private static void fail(Object plugin, String what, Throwable t) {
        PluginInfo info = PluginManager.infoOfVoicePlugin(plugin);
        String msg = "voice hook " + what + " failed: " + t;
        LocalApiMod.LOG.warn("[localapi] {}", msg, t);
        if (info != null) info.recordError(msg);
    }
}
