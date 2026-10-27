package localapi.plugin;

import localapi.api.LocalApiPlugin;
import localapi.api.OptionSpec;
import localapi.api.StatusLine;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Everything known about one jar in the plugins folder. */
public final class PluginInfo {
    public enum Status {
        INVALID("not loaded: jar unusable"),
        NOT_APPROVED("not loaded: waiting for approval"),
        MISSING_DEPENDENCY("not loaded: dependency missing"),
        ERROR("not loaded: error"),
        LOADED("loaded");

        public final String label;

        Status(String label) { this.label = label; }
    }

    public final Path file;
    public final String fileName;
    public String sha256 = "";

    // manifest (null when invalid)
    public String id;
    public String name;
    public String version;
    public String description = "";
    public String entrypoint;
    public String voicechatPlugin;
    public final List<String> requires = new ArrayList<>();

    public volatile Status status = Status.INVALID;
    /** Why it is not loaded / what is missing. One line each. */
    public final List<String> problems = new ArrayList<>();
    /** Non-fatal remarks (e.g. "voice listener needs a restart"). */
    public final List<String> notes = new ArrayList<>();

    URLClassLoader loader;
    LocalApiPlugin instance;
    /** Instance of the plugin's own VoicechatPlugin class. Typed Object: the core never links Voice Chat. */
    volatile Object voicePlugin;

    public final Map<String, PluginFunction> functions = new LinkedHashMap<>();
    public final Map<String, String> functionProblems = new LinkedHashMap<>();
    public PluginSettings settings = new PluginSettings();

    /** Settings the plugin declared (empty = no settings block on its page). */
    public final List<OptionSpec> optionSpecs = new ArrayList<>();
    /** Current values, canonical text form. */
    public final Map<String, String> optionValues = new ConcurrentHashMap<>();
    volatile Consumer<String> onOptionChange;
    public volatile Supplier<List<StatusLine>> statusLines;

    private final ArrayDeque<String> errors = new ArrayDeque<>();

    PluginInfo(Path file) {
        this.file = file;
        this.fileName = file.getFileName().toString();
    }

    public String displayName() {
        if (name != null && !name.isBlank()) return name;
        return id != null ? id : fileName;
    }

    public boolean isLoaded() { return status == Status.LOADED; }

    public Object voicePlugin() { return voicePlugin; }

    public synchronized void recordError(String msg) {
        errors.addFirst(java.time.LocalTime.now().withNano(0) + "  " + msg);
        while (errors.size() > 20) errors.removeLast();
    }

    public synchronized List<String> recentErrors() { return new ArrayList<>(errors); }

    /** One line for the list view. */
    public String reason() {
        if (problems.isEmpty()) return status.label;
        return status.label + ": " + String.join("; ", problems);
    }
}
