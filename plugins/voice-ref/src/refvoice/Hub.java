package refvoice;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import localapi.api.AudioBus;
import localapi.api.Call;
import localapi.api.StreamSink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Shared state of the plugin. */
final class Hub {
    static final int SAMPLE_RATE = 48000; // AudioUtils.SAMPLE_RATE in Voice Chat 2.6.26

    /** Voice Chat hands plugins the SERVER api in initialize(), also on the client, so the client api is fetched here. */
    private static volatile VoicechatClientApi clientApi;

    private static VoicechatClientApi api() {
        VoicechatClientApi c = clientApi;
        if (c != null) return c;
        try {
            Object o = Class.forName("de.maxhenkel.voicechat.plugins.impl.VoicechatClientApiImpl").getMethod("instance").invoke(null);
            if (!(o instanceof VoicechatClientApi api)) throw new IllegalStateException("Voice Chat returned no client API");
            clientApi = api;
            return api;
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("cannot reach the Voice Chat client API: " + e, e);
        }
    }
    private static final CopyOnWriteArrayList<StreamSink> SINKS = new CopyOnWriteArrayList<>();
    private static final AtomicLong SEQ = new AtomicLong();

    private Hub() {}

    // ---------------------------------------------------------------- voice.speakers (game thread)

    static JsonElement speakers(Call call) {
        VoicechatClientApi api = api();
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        if (conn == null) throw new IllegalStateException("not connected to a world or server");
        boolean onlyTalking = call.bool("only_talking", false);
        JsonArray arr = new JsonArray();
        for (PlayerInfo pi : conn.getOnlinePlayers()) {
            UUID id = pi.getProfile().id();
            boolean talking = api.isTalking(id);
            if (onlyTalking && !talking) continue;
            JsonObject o = new JsonObject();
            o.addProperty("uuid", id.toString());
            o.addProperty("name", pi.getProfile().name());
            o.addProperty("talking", talking);
            o.addProperty("whispering", api.isWhispering(id));
            o.addProperty("disconnected", api.isDisconnected(id));
            arr.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("self_muted", api.isMuted());
        out.add("speakers", arr);
        return out;
    }

    // ---------------------------------------------------------------- voice.stream

    static void open(Call call, StreamSink sink) {
        api(); // fails with the real reason when Voice Chat is not reachable
        SINKS.add(sink);
        sink.onClose(() -> SINKS.remove(sink));
    }

    static void onEntitySound(ClientReceiveSoundEvent.EntitySound e) {
        if (!wanted()) return;
        // getEntityId() is the sender of the PlayerSoundPacket (checked in Voice Chat 2.6.26: AudioChannel passes getSender()).
        emit("entity", e.getEntityId(), String.valueOf(e.getId()), e.getRawAudio(), e.isWhispering(), o -> {
            o.addProperty("entity", String.valueOf(e.getEntityId()));
            o.addProperty("whispering", e.isWhispering());
            o.addProperty("distance", e.getDistance());
        });
    }

    static void onLocationalSound(ClientReceiveSoundEvent.LocationalSound e) {
        if (!wanted()) return;
        // Locational/static packets carry no sender; getId() is the channel id, which Voice Chat keys per sender.
        emit("locational", e.getId(), String.valueOf(e.getId()), e.getRawAudio(), false, o -> {
            o.addProperty("x", e.getPosition().getX());
            o.addProperty("y", e.getPosition().getY());
            o.addProperty("z", e.getPosition().getZ());
            o.addProperty("distance", e.getDistance());
        });
    }

    static void onStaticSound(ClientReceiveSoundEvent.StaticSound e) {
        if (!wanted()) return;
        emit("static", e.getId(), String.valueOf(e.getId()), e.getRawAudio(), false, null);
    }

    /** Local mic. ClientReceiveSoundEvent is other players only, so a solo test never sees your own voice. */
    static void onMicrophone(ClientSoundEvent e) {
        if (!wanted()) return;
        UUID me = localUuid();
        emit("microphone", me, me == null ? "local" : me.toString(), e.getRawAudio(), e.isWhispering(),
                o -> o.addProperty("whispering", e.isWhispering()));
    }

    private static UUID localUuid() {
        Minecraft mc = Minecraft.getInstance();
        var p = mc.player;
        if (p != null) return p.getUUID();
        return mc.getUser() == null ? null : mc.getUser().getProfileId();
    }

    private static boolean wanted() {
        return !SINKS.isEmpty() || AudioBus.hasListeners();
    }

    /**
     * getRawAudio() is reused by Voice Chat after the callback returns: copy it right here, once,
     * for both consumers (in-process bus and NDJSON stream).
     */
    private static void emit(String kind, UUID speaker, String id, short[] raw, boolean whispering, Consumer<JsonObject> extra) {
        short[] copy = raw == null ? new short[0] : raw.clone();
        if (AudioBus.hasListeners()) AudioBus.publish(new AudioBus.Frame(kind, speaker, System.currentTimeMillis(), copy, whispering));
        if (SINKS.isEmpty()) return;
        JsonObject o = frame(copy, kind, id);
        if (speaker != null) o.addProperty("speaker", speaker.toString());
        if (extra != null) extra.accept(o);
        push(o);
    }

    private static JsonObject frame(short[] copy, String kind, String id) {
        ByteBuffer bb = ByteBuffer.allocate(copy.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.asShortBuffer().put(copy);
        JsonObject o = new JsonObject();
        o.addProperty("type", "frame");
        o.addProperty("seq", SEQ.incrementAndGet());
        o.addProperty("t", System.currentTimeMillis());
        o.addProperty("kind", kind);
        o.addProperty("id", id);
        o.addProperty("rate", SAMPLE_RATE);
        o.addProperty("channels", 1);
        o.addProperty("format", "s16le");
        o.addProperty("samples", copy.length);
        o.addProperty("pcm_b64", Base64.getEncoder().encodeToString(bb.array()));
        return o;
    }
    private static void push(JsonObject frame) {
        for (StreamSink s : SINKS) s.send(frame);
    }
}
