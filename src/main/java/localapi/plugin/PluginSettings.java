package localapi.plugin;

import com.google.gson.JsonElement;

import java.util.LinkedHashMap;
import java.util.Map;

/** config/localapi/plugins/<id>.json */
public final class PluginSettings {
    public static final class FunctionSetting {
        public boolean enabled = false;
        public String path = "";
    }

    public Map<String, FunctionSetting> functions = new LinkedHashMap<>();

    /** Plugin options (see localapi.api.OptionSpec): number, or string. */
    public Map<String, JsonElement> options = new LinkedHashMap<>();
}
