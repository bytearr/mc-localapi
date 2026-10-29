package targethud;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import java.util.Locale;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/** Render-thread live reads only: no target history, timers, worker, HTTP calls or files. */
final class TargetHud implements HudElement {
    private final MiningProgress mining;
    TargetHud(MiningProgress mining) { this.mining = mining; }

    record Vitals(float health, float maximum, int armor) {}
    record View(Component name, String id, ItemStack icon, Boolean suitable, int accent, float progress, Vitals vitals) {}
    static final int NEUTRAL = 0xffa8bdcf;
    static final int GOOD = 0xff83d6a3;
    static final int BAD = 0xffef9b8f;

    View current(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null || mc.gameMode == null
                || mc.gui == null || mc.gui.hud == null || mc.gui.hud.isHidden()
                || mc.gui.screen() != null || mc.isPaused()) return null;
        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() == HitResult.Type.MISS) return null;
        if (hit instanceof EntityHitResult entityHit) {
            var entity = entityHit.getEntity();
            if (entity.isRemoved()) return null;
            Vitals vitals = entity instanceof LivingEntity living
                    ? new Vitals(living.getHealth(), living.getMaxHealth(), living.getArmorValue()) : null;
            return new View(entity.getDisplayName(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    ItemStack.EMPTY, null, NEUTRAL, -1, vitals);
        }
        if (!(hit instanceof BlockHitResult blockHit)) return null;
        var pos = blockHit.getBlockPos();
        if (!mc.level.hasChunkAt(pos)) return null;
        var state = mc.level.getBlockState(pos);
        if (state.isAir()) return null;
        Component name = state.getBlock().getName();
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        ItemStack icon = state.getBlock().asItem().getDefaultInstance();
        if (mc.player.isCreative() || mc.player.isSpectator()) return new View(name, id, icon, null, NEUTRAL, -1, null);
        boolean breakable = state.getDestroySpeed(mc.level, pos) >= 0;
        boolean correct = breakable && mc.player.hasCorrectToolForDrops(state);
        float progress = breakable ? mining.read(mc.gameMode, pos, mc.player.getMainHandItem(), false) : -1;
        return new View(name, id, icon, correct, correct ? GOOD : BAD, progress, null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        View view = current(mc);
        if (view == null || mc.font == null) return;
        var font = mc.font;
        int available = Math.min(260, graphics.guiWidth() - 24);
        if (available < 32) return;
        int contentLimit = available - 20;
        var lines = font.split(view.name(), contentLimit);
        int count = Math.min(2, lines.size());
        if (count == 0) return;
        int contentWidth = 0;
        for (int i = 0; i < count; i++) contentWidth = Math.max(contentWidth, font.width(lines.get(i)));
        String id = font.plainSubstrByWidth(view.id(), contentLimit);
        contentWidth = Math.max(contentWidth, font.width(id));
        String health = view.vitals() == null ? null : number(view.vitals().health()) + " / " + number(view.vitals().maximum());
        String armor = view.vitals() == null ? null : Integer.toString(view.vitals().armor());
        int vitalsWidth = health == null ? 0 : 12 + font.width(health) + 14 + 12 + font.width(armor);
        contentWidth = Math.max(contentWidth, vitalsWidth);
        boolean iconRow = !view.icon().isEmpty() || view.suitable() != null;
        int width = Math.min(available, Math.max(116, contentWidth + 20));
        int height = 14 + count * (font.lineHeight + 2) + font.lineHeight + 2
                + (iconRow ? 20 : 0) + (health == null ? 0 : font.lineHeight + 5) + (view.progress() >= 0 ? 8 : 0);
        int x = (graphics.guiWidth() - width) / 2;
        // Slightly above the original quarter-height placement, in scaled GUI coordinates.
        int y = Math.max(4, graphics.guiHeight() / 5 - height / 2);
        if (y + height >= graphics.guiHeight() / 2 - 4) return;
        graphics.fill(x, y, x + width, y + height, 0xd9161b23);
        graphics.outline(x, y, width, height, 0x70445261);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, view.accent());
        int textY = y + 8;
        for (int i = 0; i < count; i++) {
            var line = lines.get(i);
            graphics.text(font, line, x + (width - font.width(line)) / 2, textY, 0xffedf2f7, true);
            textY += font.lineHeight + 2;
        }
        graphics.text(font, id, x + (width - font.width(id)) / 2, textY, 0xff8b96a5, false);
        textY += font.lineHeight + 4;
        if (iconRow) {
            boolean hasIcon = !view.icon().isEmpty();
            int rowWidth = (hasIcon ? 16 : 0) + (view.suitable() != null ? (hasIcon ? 18 : 12) : 0);
            int left = x + (width - rowWidth) / 2;
            if (hasIcon) graphics.item(view.icon(), left, textY);
            if (view.suitable() != null) mark(graphics, left + (hasIcon ? 22 : 0), textY + 2, view.suitable());
        }
        if (health != null) {
            int left = x + (width - vitalsWidth) / 2;
            heart(graphics, left, textY);
            graphics.text(font, health, left + 12, textY, 0xffe99aa3, true);
            int shieldX = left + 12 + font.width(health) + 14;
            shield(graphics, shieldX, textY);
            graphics.text(font, armor, shieldX + 12, textY, 0xffa8bdcf, true);
        }
        if (view.progress() >= 0) {
            int left = x + 10, right = x + width - 10, top = y + height - 7;
            graphics.fill(left, top, right, top + 3, 0xff35404c);
            int filled = Math.round((right - left) * view.progress());
            if (filled > 0) graphics.fill(left, top, left + filled, top + 3, view.accent());
        }
    }

    private static String number(float value) {
        if (!Float.isFinite(value)) return "?";
        return value == (int) value ? Integer.toString((int) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private static void heart(GuiGraphicsExtractor g, int x, int y) {
        int c = 0xffe47782;
        g.fill(x + 1, y, x + 4, y + 1, c);
        g.fill(x + 5, y, x + 8, y + 1, c);
        g.fill(x, y + 1, x + 9, y + 4, c);
        g.fill(x + 1, y + 4, x + 8, y + 5, c);
        g.fill(x + 2, y + 5, x + 7, y + 6, c);
        g.fill(x + 3, y + 6, x + 6, y + 7, c);
        g.fill(x + 4, y + 7, x + 5, y + 8, c);
    }

    private static void shield(GuiGraphicsExtractor g, int x, int y) {
        int c = 0xffa8bdcf;
        g.fill(x, y, x + 9, y + 5, c);
        g.fill(x + 1, y + 5, x + 8, y + 6, c);
        g.fill(x + 2, y + 6, x + 7, y + 7, c);
        g.fill(x + 3, y + 7, x + 6, y + 8, c);
        g.fill(x + 2, y + 1, x + 7, y + 4, 0xff4d6075);
    }

    // Pixel strokes keep the check/X legible even if the selected font has no symbol glyphs.
    static void mark(GuiGraphicsExtractor graphics, int x, int y, boolean suitable) {
        int color = suitable ? GOOD : BAD;
        if (suitable) {
            for (int i = 0; i < 4; i++) graphics.fill(x + i, y + 5 + i, x + i + 2, y + 7 + i, color);
            for (int i = 0; i < 8; i++) graphics.fill(x + 3 + i, y + 8 - i, x + 5 + i, y + 10 - i, color);
        } else {
            for (int i = 0; i < 10; i++) {
                graphics.fill(x + i, y + 1 + i, x + i + 2, y + 3 + i, color);
                graphics.fill(x + 9 - i, y + 1 + i, x + 11 - i, y + 3 + i, color);
            }
        }
    }

}
