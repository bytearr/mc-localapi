package localapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import localapi.mixin.EditBoxAccessor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.StringUtil;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Game-thread-only references, not cached screen/text responses. */
public final class ScreenWidgets {
    static final ScreenWidgets INSTANCE = new ScreenWidgets();
    private WeakReference<Screen> screen = new WeakReference<>(null);
    private final List<WeakReference<GuiEventListener>> children = new ArrayList<>();
    private final List<String> references = new ArrayList<>();
    private String revision;
    private int width, height;
    private final java.util.function.Function<EditBox, EditBoxAccessor> textFields;

    ScreenWidgets() { this(edit -> (EditBoxAccessor) edit); }
    ScreenWidgets(java.util.function.Function<EditBox, EditBoxAccessor> textFields) { this.textFields = textFields; }

    public static void invalidate() { INSTANCE.reset(); }
    void reset() {
        screen.clear(); children.clear(); references.clear(); revision = null;
    }

    private boolean matches(Screen current) {
        if (current == null || revision == null || screen.get() != current || width != current.width || height != current.height)
            return false;
        var live = current.children();
        if (live.size() != children.size()) return false;
        for (int i = 0; i < live.size(); i++) if (children.get(i).get() != live.get(i)) return false;
        return true;
    }

    String revisionFor(Screen current) {
        if (!matches(current)) {
            reset();
            if (current == null) return null;
            screen = new WeakReference<>(current);
            revision = UUID.randomUUID().toString();
            width = current.width; height = current.height;
            for (var child : current.children()) {
                children.add(new WeakReference<>(child));
                references.add(UUID.randomUUID().toString());
            }
        }
        return revision;
    }

    void requireRevision(Screen current, String expected) {
        if (!matches(current) || !revision.equals(expected)) throw new HttpError(409, "screen revision is stale; read the live view again");
    }

    void consumeRevision(Screen current, String expected) { requireRevision(current, expected); reset(); }

    void describe(Screen current, JsonObject out, JsonArray widgets) {
        revisionFor(current);
        if (current == null) return;
        out.addProperty("revision", revision);
        int index = 0;
        for (int i = 0; i < current.children().size(); i++) {
            if (!(current.children().get(i) instanceof AbstractWidget widget)) continue;
            JsonObject entry = widgets.get(index++).getAsJsonObject();
            entry.addProperty("ref", references.get(i));
            String action = action(widget);
            entry.addProperty("supported", action != null);
            entry.addProperty("action", action);
            if (action == null) entry.addProperty("unsupported_reason", "unsupported_widget_type");
            if (widget.getClass() == EditBox.class) {
                EditBox edit = (EditBox) widget;
                EditBoxAccessor access = textFields.apply(edit);
                entry.addProperty("value", edit.getValue());
                entry.addProperty("max_length", access.localapi$maxLength());
                entry.addProperty("editable", access.localapi$isEditable());
                entry.addProperty("focused", edit.isFocused());
            }
        }
    }

    private static String action(AbstractWidget widget) {
        if (widget.getClass() == Button.Plain.class) return "activate";
        if (widget.getClass() == EditBox.class) return "set_text";
        return null;
    }

    private AbstractWidget resolve(Supplier<Screen> current, String expected, String ref) {
        Screen live = current.get();
        if (!matches(live) || !revision.equals(expected)) throw new HttpError(409, "screen revision is stale; read /screen again");
        int index = references.indexOf(ref);
        if (index < 0 || !(children.get(index).get() instanceof AbstractWidget widget))
            throw new HttpError(409, "widget reference is stale or unknown; read /screen again");
        if (!widget.active || !widget.visible) throw new HttpError(409, "widget is inactive or hidden");
        return widget;
    }

    JsonObject perform(Request request, Supplier<Screen> current, boolean textAction) {
        String expected = string(request, "revision");
        String ref = string(request, "ref");
        AbstractWidget widget = resolve(current, expected, ref);
        if (!(textAction ? "set_text" : "activate").equals(action(widget)))
            throw new HttpError(400, "action not supported for this widget");
        String text = textAction ? string(request, "text") : null;
        if (textAction) validateText((EditBox) widget, text);
        Screen target = current.get();
        target.setFocused(widget);
        // Focusing a control can run screen/mod code. Revalidate immediately before the action.
        resolve(current, expected, ref);
        if (textAction) validateText((EditBox) widget, text);
        // Consume before invoking user/widget callbacks, including callbacks which throw.
        reset();
        JsonObject result = new JsonObject();
        result.addProperty("performed", true);
        try {
            if (textAction) {
                EditBox edit = (EditBox) widget;
                // Normal responder behavior; prechecks prevent silent filtering/truncation.
                edit.setValue(text);
                result.addProperty("value", edit.getValue());
                result.addProperty("accepted", text.equals(edit.getValue()));
            } else {
                ((Button) widget).onPress(new KeyEvent(257, 0, 0)); // Enter, no modifiers.
            }
            result.addProperty("screen_changed", current.get() != target);
            return result;
        } finally {
            ClientEvents.invalidate("/screen", "/container", "/state");
        }
    }

    private void validateText(EditBox edit, String text) {
        var access = textFields.apply(edit);
        if (!access.localapi$isEditable()) throw new HttpError(409, "text field is not editable");
        if (text.length() > access.localapi$maxLength()) throw new HttpError(400, "text exceeds the field's max_length");
        if (!StringUtil.filterText(text).equals(text) || !wellFormed(text))
            throw new HttpError(400, "text contains characters this field cannot accept");
    }

    private static boolean wellFormed(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    private static String string(Request request, String name) {
        if (request.body().has(name)) {
            var value = request.body().get(name);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new HttpError(400, name + " must be a string");
        }
        return request.reqStr(name);
    }
}
