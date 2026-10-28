package localapi;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;

/** Only receipt state for the active connection; counters remain owned by vanilla. */
public final class StatsSync {
    private static ClientPacketListener connection;
    private static boolean received;
    static void begin(ClientPacketListener next) { if (connection != next) { connection = next; received = false; } }
    static void end(ClientPacketListener old) { if (connection == old) { connection = null; received = false; } }
    public static void awarded(ClientPacketListener source) { if (source == connection && source != null) received = true; }
    static boolean received(ClientPacketListener current) { return current != null && current == connection && received; }
    static JsonObject request(ClientPacketListener current) {
        if (current == null) throw new HttpError(409, "no server connection");
        current.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
        JsonObject out = new JsonObject(); out.addProperty("requested", true); return out;
    }
}
