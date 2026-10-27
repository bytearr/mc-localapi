package refvoice;

import localapi.api.LocalApiPlugin;
import localapi.api.ParamType;
import localapi.api.PluginContext;

public final class VoiceRefPlugin implements LocalApiPlugin {
    @Override
    public void register(PluginContext ctx) {
        ctx.function("voice.speakers")
                .title("Who is speaking")
                .description("For every player in the tab list: talking, whispering, disconnected (Voice Chat client API). "
                        + "self_muted is your own mute state (the API has no per-player mute).")
                .suggestedPath("/voice/speakers")
                .method("GET")
                .param("only_talking", ParamType.BOOLEAN, "false", "list only players that are talking right now")
                .once(Hub::speakers)
                .register();

        ctx.function("voice.stream")
                .title("Voice frame stream")
                .description("Open connection, one JSON line per voice frame: source id, time, copied 16-bit PCM "
                        + "(48000 Hz, 960 samples = 20 ms, little-endian, base64). Other players use kind entity, "
                        + "locational or static. Your own microphone is kind microphone. No text, no speech recognition.")
                .suggestedPath("/voice/stream")
                .method("GET")
                .stream(Hub::open)
                .register();
    }
}
