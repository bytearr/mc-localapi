package localapi.plugin;

import localapi.api.OnceHandler;
import localapi.api.ParamType;
import localapi.api.StreamHandler;

import java.util.ArrayList;
import java.util.List;

/** One declared function of a plugin. Immutable after register(). */
public final class PluginFunction {
    public record Param(String name, ParamType type, boolean required, String defaultValue, String description) {}

    public final String pluginId;
    public final String id;
    public String title = "";
    public String description = "";
    public String suggestedPath = "";
    public String method = "GET";
    public final List<Param> params = new ArrayList<>();
    public OnceHandler once;
    public StreamHandler stream;
    public boolean gameThread = true;

    PluginFunction(String pluginId, String id) {
        this.pluginId = pluginId;
        this.id = id;
    }

    public boolean isStream() { return stream != null; }

    public String displayTitle() { return title.isEmpty() ? id : title; }
}
