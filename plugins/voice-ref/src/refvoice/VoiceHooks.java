package refvoice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;

/**
 * Voice Chat calls this (through the Local API core's bridge) at ITS start. The listeners are registered
 * here, in registerEvents(); registering later does not work.
 */
public final class VoiceHooks implements VoicechatPlugin {
    @Override
    public String getPluginId() {
        return "localapi-voice";
    }

    @Override
    public void initialize(VoicechatApi api) {
        // nothing to keep: initialize() gets the server api here; Hub fetches the client api when a function is called
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        // Runs on Voice Chat's audio thread, not the game thread.
        registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, Hub::onEntitySound);
        registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class, Hub::onLocationalSound);
        registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class, Hub::onStaticSound);
        registration.registerEvent(ClientSoundEvent.class, Hub::onMicrophone);
        // tells other plugins (captions) that audio frames will come from this plugin
        localapi.api.AudioBus.addSource("voice");
    }
}
