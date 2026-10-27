package captions;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Copy of voicetranscript 1.5.0 com.voicetranscript.hud.TranscriptHudOverlay + TranscriptEntry (values taken from
 * the class files): same colors, spacing, font, fade and both layouts. Do not restyle.
 * The only difference: entries are created on the render thread from a pending queue the worker fills.
 */
final class CaptionsHud implements HudElement {
    enum Mode { RIGHT_WINDOW, TOP_LEFT_ICONS, OFF }

    /** TranscriptEntry */
    record Entry(UUID playerId, String playerName, PlayerSkin playerSkin, List<FormattedCharSequence> wrappedRightBoxLines,
                 FormattedCharSequence singleLineTag, int singleLinePixelWidth, int totalHeight, long creationTimestamp) {

        static Entry create(Font font, UUID playerId, String playerName, PlayerSkin skin, String text, int maxWidth, long ts) {
            String name = playerName != null ? playerName : "Player";
            String body = text != null ? text : "";
            UUID id = playerId != null ? playerId : Util.NIL_UUID;
            MutableComponent comp = Component.literal(name + ":").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(body).withStyle(ChatFormatting.WHITE));
            List<FormattedCharSequence> lines = font.split(comp, Math.max(20, maxWidth));
            if (lines.isEmpty()) lines = List.of(comp.getVisualOrderText());
            FormattedCharSequence tag = comp.getVisualOrderText();
            int tagWidth = font.width(tag);
            int height = lines.size() * 11 + 8;
            return new Entry(id, name, skin, lines, tag, tagWidth, height, ts);
        }
    }

    private record Pending(UUID speaker, String text) {}

    private final CopyOnWriteArrayList<Entry> queue = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Pending> pending = new ConcurrentLinkedQueue<>();

    private volatile int maxEntries = 5;
    private volatile int windowWidth = 220;
    private volatile float fadeTimeSeconds = 5.0f;
    private volatile Mode mode = Mode.RIGHT_WINDOW;

    void setMaxEntries(int v) {
        maxEntries = Math.max(1, v);
        while (queue.size() > maxEntries) queue.remove(0);
    }

    void setWindowWidth(int v) { windowWidth = Math.max(80, v); }

    void setFadeTimeSeconds(float v) { fadeTimeSeconds = Math.max(0.5f, v); }

    void setMode(Mode m) { mode = m != null ? m : Mode.RIGHT_WINDOW; }

    /** Screen open or HUD hidden: nothing is drawn, so nothing is created and nothing ages. */
    private static boolean blocked() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return true;
        if (mc.gui == null) return true;
        if (mc.gui.screen() != null) return true;
        return mc.gui.hud != null && mc.gui.hud.isHidden();
    }

    /** Any thread. Shown the next time the HUD draws; dropped when the HUD is not visible right now. */
    void add(UUID speaker, String text) {
        if (text == null || text.isBlank()) return;
        if (mode == Mode.OFF || blocked()) return;
        pending.add(new Pending(speaker, text));
    }

    /** Time that only runs while the HUD really draws; entry age is measured on it. */
    private long hudClock;
    private long lastCall;

    void clear() {
        pending.clear();
        queue.clear();
    }

    /** TranscriptHudOverlay.addTranscription, on the render thread. */
    private void create(Pending p, Font font) {
        String name = "Player";
        PlayerSkin skin = null;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && p.speaker() != null && p.speaker().equals(mc.player.getUUID())) {
                name = mc.player.getScoreboardName();
                skin = mc.player.getSkin();
            } else if (mc.getConnection() != null && p.speaker() != null) {
                ClientPacketListener conn = mc.getConnection();
                PlayerInfo pi = conn.getPlayerInfo(p.speaker());
                if (pi != null) {
                    if (pi.getProfile() != null && pi.getProfile().name() != null) name = pi.getProfile().name();
                    skin = pi.getSkin();
                }
            }
        } catch (Throwable ignored) {
            // fall through to defaults, like the original
        }
        if (skin == null && p.speaker() != null) {
            try { skin = DefaultPlayerSkin.get(p.speaker()); } catch (Throwable ignored) {}
        }
        int maxWidth = Math.max(50, windowWidth - 12);
        addEntry(Entry.create(font, p.speaker(), name, skin, p.text(), maxWidth, hudClock));
    }

    private void addEntry(Entry e) {
        queue.add(e);
        while (queue.size() > maxEntries) queue.remove(0);
    }

    /** Fade: full opacity until the last second, then linear to 0 over 1000 ms. */
    static float calculateAlpha(long ageMs, long fadeMs) {
        if (ageMs < 0 || ageMs >= fadeMs) return 0f;
        if (ageMs <= fadeMs - 1000L) return 1f;
        return Math.clamp((fadeMs - ageMs) / 1000.0f, 0f, 1f);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        long real = System.currentTimeMillis();
        long dt = lastCall == 0 ? 0 : Math.clamp(real - lastCall, 0L, 250L);
        lastCall = real;
        if (blocked()) {
            pending.clear();
            return;
        }
        hudClock += dt;
        Font font = mc != null ? mc.font : null;
        if (font != null) {
            Pending p;
            while ((p = pending.poll()) != null) create(p, font);
        }
        if (mode == Mode.OFF || queue.isEmpty() || g == null) return;

        long now = hudClock;
        long fadeMs = (long) (fadeTimeSeconds * 1000.0f);
        while (!queue.isEmpty()) {
            Entry first = queue.get(0);
            if (now - first.creationTimestamp() >= fadeMs) queue.remove(0);
            else break;
        }
        if (queue.isEmpty()) return;

        if (mode == Mode.RIGHT_WINDOW) renderRightWindow(g, font, now, fadeMs);
        else if (mode == Mode.TOP_LEFT_ICONS) renderTopLeftIcons(g, font, now, fadeMs);
    }

    private void renderRightWindow(GuiGraphicsExtractor g, Font font, long now, long fadeMs) {
        int x = g.guiWidth() - windowWidth - 10;
        int y = 30;
        int size = queue.size();
        for (int i = 0; i < size; i++) {
            Entry e = queue.get(i);
            long age = now - e.creationTimestamp();
            if (age >= fadeMs) continue;
            float fade = calculateAlpha(age, fadeMs);
            int textAlpha = (int) (fade * 255.0f);
            if (textAlpha <= 0) continue;
            int boxY = y;
            int h = e.totalHeight();
            int w = windowWidth;
            int bg = (int) (fade * 128.0f);
            g.fill(x, boxY, x + w, boxY + h, (bg << 24) | 0x101014);
            int border = (int) (fade * 64.0f);
            g.outline(x, boxY, w, h, (border << 24) | 0xFFFFFF);
            g.fill(x, boxY, x + 2, boxY + h, (textAlpha << 24) | 0x00E5FF);
            if (font != null) {
                int tx = x + 6;
                int ty = boxY + 4;
                List<FormattedCharSequence> lines = e.wrappedRightBoxLines();
                for (int k = 0; k < lines.size(); k++) {
                    g.text(font, lines.get(k), tx, ty, (textAlpha << 24) | 0xFFFFFF, true);
                    ty += 11;
                }
            }
            y = y + h + 3;
        }
    }

    private void renderTopLeftIcons(GuiGraphicsExtractor g, Font font, long now, long fadeMs) {
        int x = 36;
        int y = 16;
        int size = queue.size();
        for (int i = 0; i < size; i++) {
            Entry e = queue.get(i);
            boolean newest = true;
            for (int j = i + 1; j < size; j++) {
                if (Objects.equals(e.playerId(), queue.get(j).playerId())) { newest = false; break; }
            }
            if (!newest) continue;
            long age = now - e.creationTimestamp();
            if (age >= fadeMs) continue;
            float fade = calculateAlpha(age, fadeMs);
            int textAlpha = (int) (fade * 255.0f);
            if (textAlpha <= 0) continue;
            int h = 16;
            int w = e.singleLinePixelWidth() + 22;
            int bg = (int) (fade * 128.0f);
            g.fill(x, y, x + w, y + h, (bg << 24) | 0x101014);
            g.fill(x, y, x + 2, y + h, (textAlpha << 24) | 0x00E5FF);
            if (e.playerSkin() != null) {
                int color = (textAlpha << 24) | 0xFFFFFF;
                try {
                    PlayerFaceExtractor.extractRenderState(g, e.playerSkin(), x + 4, y + 3, 10, color);
                } catch (Throwable ignored) {}
            }
            if (font != null) g.text(font, e.singleLineTag(), x + 18, y + 4, (textAlpha << 24) | 0xFFFFFF, true);
            y += 20;
        }
    }
}
