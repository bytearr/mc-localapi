package localapi.api;

import org.slf4j.Logger;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Handed to the plugin entry class. */
public interface PluginContext {
    String pluginId();

    /** function ids are unique within a plugin, e.g. "voice.speakers". */
    FunctionBuilder function(String id);

    Logger logger();

    /**
     * Declares settings shown on this plugin's page (a plugin without functions still gets its page).
     * Saved values are applied before this returns. onChange(optionId) runs on the render thread after a valid
     * new value was saved; keep it short and move real work to your own thread.
     */
    Options options(List<OptionSpec> specs, Consumer<String> onChange);

    /** Status lines on the plugin page. Called from the render thread every frame: return cached text, do not block. */
    void status(Supplier<List<StatusLine>> lines);

    /** True when the plugin with this manifest id is currently loaded. */
    boolean pluginLoaded(String id);
}
