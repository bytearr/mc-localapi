package localapi.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Minimal scrollable single-line list, drawn and hit-tested by the owning screen. */
final class ListView<T> {
    static final int ROW_H = 12;

    int x, y, w, h;
    private List<T> items = new ArrayList<>();
    private T selected;
    private int scrollRows;
    private final Function<T, String> label;
    private final Function<T, Integer> color;
    boolean visible = true;

    ListView(Function<T, String> label, Function<T, Integer> color) {
        this.label = label;
        this.color = color;
    }

    void bounds(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
        clamp();
    }

    void set(List<T> items) {
        this.items = items;
        if (selected != null && !items.contains(selected)) selected = null;
        clamp();
    }

    T selected() { return selected; }

    void select(T t) { selected = t; }

    private int visibleRows() { return Math.max(1, h / ROW_H); }

    private void clamp() {
        scrollRows = Math.max(0, Math.min(scrollRows, Math.max(0, items.size() - visibleRows())));
    }

    boolean contains(double mx, double my) {
        return visible && mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** @return the clicked item, or null when the click was outside / on empty space */
    T click(double mx, double my) {
        if (!contains(mx, my)) return null;
        int i = scrollRows + (int) ((my - y) / ROW_H);
        if (i >= 0 && i < items.size()) {
            selected = items.get(i);
            return selected;
        }
        return null;
    }

    boolean scroll(double mx, double my, double dy) {
        if (!contains(mx, my)) return false;
        scrollRows -= (int) Math.signum(dy) * 2;
        clamp();
        return true;
    }

    void render(GuiGraphicsExtractor g, Font font, int mx, int my) {
        if (!visible) return;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        g.fill(x, y, x + w, y + h, 0xFF1E1E1E);
        g.enableScissor(x, y, x + w, y + h);
        int rows = visibleRows();
        for (int r = 0; r < rows; r++) {
            int i = scrollRows + r;
            if (i >= items.size()) break;
            T it = items.get(i);
            int ry = y + r * ROW_H;
            if (it == selected) g.fill(x, ry, x + w, ry + ROW_H, 0xFF36607F);
            else if (mx >= x && mx < x + w && my >= ry && my < ry + ROW_H) g.fill(x, ry, x + w, ry + ROW_H, 0x30FFFFFF);
            String s = font.plainSubstrByWidth(label.apply(it), w - 8);
            g.text(font, s, x + 4, ry + 2, color.apply(it));
        }
        g.disableScissor();
        if (items.size() > rows) { // scroll bar
            int bh = Math.max(8, h * rows / items.size());
            int by = y + (h - bh) * scrollRows / Math.max(1, items.size() - rows);
            g.fill(x + w - 3, by, x + w - 1, by + bh, 0xFF888888);
        }
        if (items.isEmpty()) g.text(font, "(nothing found)", x + 4, y + 3, 0xFF777777);
    }
}
