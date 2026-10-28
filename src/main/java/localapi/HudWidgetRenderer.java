package localapi;

import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

final class HudWidgetRenderer {
    static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.gui.hud.isHidden()) return;
        long now = System.nanoTime();
        for (var widget : HudWidgets.INSTANCE.visible()) {
            var box = HudWidgets.layout(widget, graphics.guiWidth(), graphics.guiHeight());
            if (box.width() < 8 || box.height() < 8) continue;
            graphics.enableScissor(box.x(), box.y(), box.x() + box.width(), box.y() + box.height());
            try {
                graphics.fill(box.x(), box.y(), box.x() + box.width(), box.y() + box.height(), 0xb0000000);
                graphics.text(mc.font, mc.font.plainSubstrByWidth(widget.text(now), box.width() - 8), box.x() + 4, box.y() + 4, 0xffffffff);
                if (widget.type().equals("progress")) {
                    int length = box.width() - 8;
                    graphics.fill(box.x() + 4, box.y() + 17, box.x() + 4 + length, box.y() + 21, 0xff404040);
                    int filled = (int)Math.round(length * widget.value() / widget.max());
                    if (filled > 0) graphics.fill(box.x() + 4, box.y() + 17, box.x() + 4 + filled, box.y() + 21, 0xff55cc77);
                }
            } finally { graphics.disableScissor(); }
        }
    }
}
