package localapi;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.MessageSignature;

import java.util.Base64;
import java.util.Objects;

/** Game-thread bridges. Stores comparison tokens only, never inventory/container snapshots. */
public final class ClientEvents {
    private static ClientPacketListener connection;
    private static PlayerStamp playerStamp;
    private static int screenToken;

    private ClientEvents() {}

    public static void join(ClientPacketListener handler, Minecraft mc) {
        if (connection == handler && SessionEvents.INSTANCE.active()) return;
        HudWidgets.INSTANCE.clear();
        connection = handler;
        StatsSync.begin(handler);
        playerStamp = null;
        screenToken = 0;
        SessionEvents.INSTANCE.begin(dimension(mc));
        invalidate("/state", "/world", "/player", "/inventory", "/container", "/screen", "/scoreboard", "/bossbars", "/advancements", "/stats");
    }

    public static void disconnect(ClientPacketListener handler) {
        if (connection != handler) return;
        HudWidgets.INSTANCE.clear();
        SessionEvents.INSTANCE.end();
        StatsSync.end(handler);
        connection = null;
        playerStamp = null;
    }

    public static void invalidate(String... paths) {
        SessionEvents.INSTANCE.invalidate(paths);
        for (String path : paths) if (path.equals("/inventory") || path.equals("/container") || path.equals("/screen")) {
            SessionEvents.INSTANCE.invalidate("/recipes");
            break;
        }
    }

    public static void respawn(Minecraft mc) {
        HudWidgets.INSTANCE.clear();
        SessionEvents.INSTANCE.respawn(dimension(mc));
        playerStamp = null;
        invalidate("/state", "/world", "/player", "/inventory", "/container", "/screen");
    }

    public static void death(Minecraft mc, int playerId, String cause) {
        if (mc.player == null || mc.level == null || mc.player.getId() != playerId) return;
        SessionEvents.INSTANCE.death(dimension(mc), mc.player.getX(), mc.player.getY(), mc.player.getZ(), cause);
        invalidate("/player", "/screen");
    }

    public static void chat(String kind, String sender, String senderUuid, String text, MessageSignature signature) {
        JsonObject data = new JsonObject();
        data.addProperty("kind", kind);
        data.addProperty("sender", sender);
        data.addProperty("sender_uuid", senderUuid);
        data.addProperty("text", text);
        data.addProperty("signature", signature(signature));
        SessionEvents.INSTANCE.record("chat_received", data);
    }

    public static void chatDeleted(MessageSignature signature) {
        JsonObject data = new JsonObject();
        data.addProperty("signature", signature(signature));
        SessionEvents.INSTANCE.record("chat_deletion_requested", data);
    }

    private static String signature(MessageSignature signature) {
        return signature == null ? null : Base64.getEncoder().encodeToString(signature.bytes());
    }

    private static String dimension(Minecraft mc) {
        return mc.level == null ? null : mc.level.dimension().identifier().toString();
    }

    public static void tick(Minecraft mc) {
        if (!SessionEvents.INSTANCE.active()) return;
        if (mc.player != null && mc.level != null) {
            var p = mc.player;
            var now = new PlayerStamp(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.getHealth(),
                    p.getFoodData().getFoodLevel(), p.getAirSupply(), p.experienceLevel, p.experienceProgress,
                    p.getInventory().getSelectedSlot(), p.isOnFire(), p.isShiftKeyDown(), p.isSprinting());
            if (!now.equals(playerStamp) || !p.getActiveEffects().isEmpty()) invalidate("/player", "/target", "/hand");
            playerStamp = now;
            // Client clocks advance without packets. This is an invalidation, not a stored world snapshot.
            invalidate("/world", "/entities", "/target");
            int screen = Objects.hash(System.identityHashCode(mc.gui.screen()), p.containerMenu.containerId);
            if (screen != screenToken) invalidate("/screen", "/container", "/state");
            screenToken = screen;
        }
        SessionEvents.INSTANCE.flush();
    }

    private record PlayerStamp(double x, double y, double z, float yaw, float pitch, float health,
                               int food, int air, int xpLevel, float xpProgress, int slot,
                               boolean burning, boolean sneaking, boolean sprinting) {}
}
