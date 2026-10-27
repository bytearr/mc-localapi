package localapi.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import localapi.Router;
import localapi.api.FunctionBuilder;
import localapi.api.LocalApiPlugin;
import localapi.api.OnceHandler;
import localapi.api.OptionSpec;
import localapi.api.Options;
import localapi.api.StatusLine;
import localapi.api.ParamType;
import localapi.api.PluginContext;
import localapi.api.StreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds, checks, loads and routes plugin jars from config/localapi/plugins.
 * No Fabric types here, so it can be tested without a running game.
 *
 * Hot-load: a new jar can be approved and loaded while the game runs (its functions get routes).
 * Unloading is not supported (the class loader stays), a replaced jar needs a client restart.
 */
public final class PluginManager {
    private static final Logger LOG = LoggerFactory.getLogger("localapi/plugins");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String MANIFEST = "localapi.plugin.json";
    public static final int API_VERSION = 1;

    private static final Pattern PLUGIN_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    private static final Pattern FUNCTION_ID = Pattern.compile("[A-Za-z0-9_.-]{1,60}");
    private static final Pattern PARAM_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,39}");
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9_.~-]+(/[A-Za-z0-9_.~-]+)*");

    /** What a public path points to. blocked != null: the path is reserved but the plugin is not active (409). */
    public record Binding(PluginInfo info, PluginFunction fn, String blocked) {}

    private static Path dir;
    private static Predicate<String> modLoaded = id -> false;
    private static final List<PluginInfo> PLUGINS = new CopyOnWriteArrayList<>();
    private static volatile Map<String, Binding> bindings = Map.of();
    private static boolean started;
    /** Set by the Voice Chat bridge once registerEvents() has been forwarded. Later plugins miss it. */
    public static volatile boolean voiceRegistrationDone;

    private PluginManager() {}

    // ------------------------------------------------------------------ lifecycle

    public static synchronized void init(Path pluginsDir, Predicate<String> isModLoaded) {
        dir = pluginsDir;
        modLoaded = isModLoaded;
    }

    /** Idempotent. Called by the mod initializer AND by the Voice Chat bridge, whichever comes first. */
    public static synchronized void ensureLoaded() {
        if (started || dir == null) return;
        started = true;
        try { Files.createDirectories(dir); } catch (IOException e) { LOG.warn("cannot create {}", dir, e); }
        scanLocked();
    }

    /** Looks for new or changed jars. Never unloads. */
    public static synchronized void rescan() {
        if (dir == null) return;
        started = true;
        scanLocked();
    }

    public static List<PluginInfo> plugins() { return Collections.unmodifiableList(PLUGINS); }

    public static Path pluginsDir() { return dir; }

    private static void scanLocked() {
        List<Path> jars = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar")).sorted().forEach(jars::add);
        } catch (IOException e) {
            LOG.warn("cannot list {}", dir, e);
        }
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            PluginInfo known = find(name);
            if (known != null) {
                String sha = sha256(jar);
                if (sha.equals(known.sha256)) continue;
                if (known.isLoaded() || known.loader != null) {
                    if (!known.notes.contains("jar changed on disk: restart the client to load the new version"))
                        known.notes.add("jar changed on disk: restart the client to load the new version");
                    continue;
                }
                PLUGINS.remove(known); // never loaded: re-inspect
            }
            PluginInfo info = inspect(jar);
            PLUGINS.add(info);
            if (info.status == PluginInfo.Status.NOT_APPROVED) continue;
            if (info.status == PluginInfo.Status.INVALID) continue;
            load(info);
        }
        rebuildBindings();
    }

    private static PluginInfo find(String fileName) {
        for (PluginInfo p : PLUGINS) if (p.fileName.equals(fileName)) return p;
        return null;
    }

    // ------------------------------------------------------------------ inspecting a jar

    private static PluginInfo inspect(Path jar) {
        PluginInfo info = new PluginInfo(jar);
        info.sha256 = sha256(jar);
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry me = zf.getEntry(MANIFEST);
            if (me == null) {
                info.problems.add("missing " + MANIFEST + " in the jar root");
                return info;
            }
            JsonObject m;
            try (InputStream in = zf.getInputStream(me)) {
                JsonElement el = JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                if (!el.isJsonObject()) { info.problems.add(MANIFEST + " is not a JSON object"); return info; }
                m = el.getAsJsonObject();
            } catch (Exception e) {
                info.problems.add(MANIFEST + " is not valid JSON: " + e.getMessage());
                return info;
            }
            List<String> missing = new ArrayList<>();
            for (String k : new String[]{"id", "version", "entrypoint", "apiVersion"})
                if (!m.has(k) || m.get(k).isJsonNull()) missing.add(k);
            if (!missing.isEmpty()) info.problems.add(MANIFEST + " is missing: " + String.join(", ", missing));
            info.id = str(m, "id");
            info.name = str(m, "name");
            info.version = str(m, "version");
            info.description = str(m, "description") == null ? "" : str(m, "description");
            info.entrypoint = str(m, "entrypoint");
            info.voicechatPlugin = str(m, "voicechatPlugin");
            if (m.has("requires") && m.get("requires").isJsonArray())
                m.getAsJsonArray("requires").forEach(e -> info.requires.add(e.getAsString()));
            if (info.id != null && !PLUGIN_ID.matcher(info.id).matches())
                info.problems.add("id '" + info.id + "' must match [a-z0-9][a-z0-9_-]{0,39}");
            if (m.has("apiVersion") && !m.get("apiVersion").isJsonNull()) {
                int v;
                try { v = m.get("apiVersion").getAsInt(); } catch (Exception e) { v = -1; }
                if (v != API_VERSION) info.problems.add("apiVersion " + m.get("apiVersion") + " not supported (this mod: " + API_VERSION + ")");
            }
            if (info.entrypoint != null && zf.getEntry(info.entrypoint.replace('.', '/') + ".class") == null)
                info.problems.add("entrypoint class " + info.entrypoint + " is not in the jar");
            if (info.voicechatPlugin != null && zf.getEntry(info.voicechatPlugin.replace('.', '/') + ".class") == null)
                info.problems.add("voicechatPlugin class " + info.voicechatPlugin + " is not in the jar");
            var en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.endsWith(".mixins.json") || n.equals("mixins.json")) {
                    info.problems.add("contains mixin config " + n + ": mixins do not work from this folder, "
                            + "install it as a normal mod in mods/");
                    break;
                }
            }
            if (info.id != null) {
                PluginInfo dup = null;
                for (PluginInfo o : PLUGINS) if (info.id.equals(o.id) && o != info) dup = o;
                if (dup != null) info.problems.add("id '" + info.id + "' is already used by " + dup.fileName);
            }
        } catch (Exception e) {
            info.problems.add("cannot read jar: " + e.getMessage());
            return info;
        }
        if (!info.problems.isEmpty()) return info; // stays INVALID

        if (info.id != null) info.settings = readSettings(info.id);
        info.status = isApproved(info) ? PluginInfo.Status.ERROR : PluginInfo.Status.NOT_APPROVED;
        if (info.status == PluginInfo.Status.NOT_APPROVED) info.problems.add("approve it in Local API > Plugins (it runs code in your client)");
        return info;
    }

    private static String str(JsonObject m, String k) {
        return m.has(k) && !m.get(k).isJsonNull() ? m.get(k).getAsString() : null;
    }

    // ------------------------------------------------------------------ approval

    private static Path approvalFile() { return dir.getParent().resolve("plugins.json"); }

    private static Map<String, String> readApprovals() {
        Map<String, String> out = new HashMap<>();
        try {
            if (Files.exists(approvalFile())) {
                JsonObject o = JsonParser.parseString(Files.readString(approvalFile())).getAsJsonObject();
                if (o.has("approved")) o.getAsJsonObject("approved").entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
            }
        } catch (Exception e) {
            LOG.warn("cannot read {}: {}", approvalFile(), e.toString());
        }
        return out;
    }

    private static boolean isApproved(PluginInfo info) {
        return info.sha256.equals(readApprovals().get(info.fileName));
    }

    /** User clicked "approve". Writes the hash of the current file, then loads it (hot-load). */
    public static synchronized String approve(PluginInfo info) {
        if (info.status != PluginInfo.Status.NOT_APPROVED) return "plugin is not waiting for approval";
        try {
            Map<String, String> a = readApprovals();
            a.put(info.fileName, info.sha256);
            JsonObject o = new JsonObject();
            JsonObject ap = new JsonObject();
            new TreeSet<>(a.keySet()).forEach(k -> ap.addProperty(k, a.get(k)));
            o.add("approved", ap);
            Files.createDirectories(approvalFile().getParent());
            Files.writeString(approvalFile(), GSON.toJson(o));
        } catch (IOException e) {
            return "cannot write " + approvalFile() + ": " + e.getMessage();
        }
        info.problems.clear();
        load(info);
        rebuildBindings();
        return null;
    }

    // ------------------------------------------------------------------ loading

    private static void load(PluginInfo info) {
        info.problems.clear();
        info.status = PluginInfo.Status.ERROR;

        List<String> missing = new ArrayList<>();
        for (String r : info.requires) if (!modLoaded.test(r)) missing.add(r);
        if (!missing.isEmpty()) {
            info.status = PluginInfo.Status.MISSING_DEPENDENCY;
            info.problems.add("requires mod(s) that are not installed: " + String.join(", ", missing));
            return;
        }

        URLClassLoader loader = null;
        try {
            // Parent = the mod class loader (Fabric's), so the plugin sees Minecraft, Fabric API,
            // and other mods (e.g. Voice Chat) exactly as they are. No second mod loader.
            loader = new URLClassLoader(new URL[]{info.file.toUri().toURL()}, PluginManager.class.getClassLoader());
            Class<?> c = Class.forName(info.entrypoint, true, loader);
            if (!LocalApiPlugin.class.isAssignableFrom(c)) {
                throw new IllegalStateException(info.entrypoint + " does not implement localapi.api.LocalApiPlugin");
            }
            LocalApiPlugin plugin = (LocalApiPlugin) c.getDeclaredConstructor().newInstance();
            info.functions.clear();
            clearOptions(info);
            plugin.register(new Ctx(info));
            info.instance = plugin;
            info.loader = loader;

            if (info.voicechatPlugin != null) {
                Class<?> vc = Class.forName(info.voicechatPlugin, true, loader);
                info.voicePlugin = vc.getDeclaredConstructor().newInstance();
                if (voiceRegistrationDone)
                    info.notes.add("voice listeners are registered when Voice Chat starts: restart the client to hear audio");
            }
            info.status = PluginInfo.Status.LOADED;
            LOG.info("loaded plugin {} {} ({} function(s))", info.id, info.version, info.functions.size());
        } catch (Throwable t) {
            closeQuietly(loader);
            info.loader = null;
            info.instance = null;
            info.voicePlugin = null;
            info.functions.clear();
            clearOptions(info);
            Throwable root = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
            if (root instanceof LinkageError || root instanceof ClassNotFoundException) {
                info.status = PluginInfo.Status.MISSING_DEPENDENCY;
                info.problems.add("a class it needs is missing: " + root.getMessage());
            } else {
                info.status = PluginInfo.Status.ERROR;
                info.problems.add(root.getClass().getSimpleName() + ": " + root.getMessage());
            }
            LOG.warn("plugin {} not loaded: {}", info.fileName, info.problems);
        }
    }

    private static void closeQuietly(URLClassLoader l) {
        if (l == null) return;
        try { l.close(); } catch (IOException ignored) {}
    }

    // ------------------------------------------------------------------ settings and paths

    private static Path settingsFile(String pluginId) { return dir.resolve(pluginId + ".json"); }

    private static PluginSettings readSettings(String pluginId) {
        try {
            Path f = settingsFile(pluginId);
            if (Files.exists(f)) {
                PluginSettings s = GSON.fromJson(Files.readString(f), PluginSettings.class);
                if (s != null && s.functions != null) {
                    if (s.options == null) s.options = new LinkedHashMap<>();
                    return s;
                }
            }
        } catch (Exception e) {
            LOG.warn("cannot read settings of {}: {}", pluginId, e.toString());
        }
        return new PluginSettings();
    }

    public static String validatePathSyntax(String p) {
        if (p.length() > 120) return "path is longer than 120 characters";
        if (!p.startsWith("/")) return "path must start with /";
        if (!PATH.matcher(p).matches())
            return "path may only contain letters, digits, _ . ~ - and single / between parts (no query, no trailing /)";
        for (String seg : p.substring(1).split("/")) if (seg.equals(".") || seg.equals("..")) return "path parts '.' and '..' are not allowed";
        return null;
    }

    /**
     * User pressed Save for one function. Returns null on success, otherwise the reason (shown in the UI).
     * Collisions (core routes, other functions) are rejected here, nothing is overwritten.
     */
    public static synchronized String saveFunction(PluginInfo info, String fnId, boolean enabled, String path) {
        path = path == null ? "" : path.trim();
        if (enabled && path.isEmpty()) return "enter a path before turning the function on";
        if (!path.isEmpty()) {
            String syn = validatePathSyntax(path);
            if (syn != null) return syn;
            if (Router.isCore(path) || path.equals("/ping"))
                return path + " is a core route of this mod and cannot be replaced by a plugin";
            for (PluginInfo other : PLUGINS) {
                if (other.id == null) continue;
                for (var e : other.settings.functions.entrySet()) {
                    if (other == info && e.getKey().equals(fnId)) continue;
                    if (path.equals(e.getValue().path))
                        return path + " is already used by " + other.id + " / " + e.getKey();
                }
            }
        }
        PluginSettings.FunctionSetting s = info.settings.functions.computeIfAbsent(fnId, k -> new PluginSettings.FunctionSetting());
        PluginSettings.FunctionSetting old = new PluginSettings.FunctionSetting();
        old.enabled = s.enabled;
        old.path = s.path;
        s.enabled = enabled;
        s.path = path;
        try {
            Files.createDirectories(dir);
            Files.writeString(settingsFile(info.id), GSON.toJson(info.settings));
        } catch (IOException e) {
            s.enabled = old.enabled;
            s.path = old.path;
            return "cannot write " + settingsFile(info.id) + ": " + e.getMessage();
        }
        rebuildBindings();
        return null;
    }

    private static void rebuildBindings() {
        Map<String, Binding> next = new LinkedHashMap<>();
        for (PluginInfo info : PLUGINS) {
            info.functionProblems.clear();
            if (info.id == null) continue;
            for (var e : info.settings.functions.entrySet()) {
                PluginSettings.FunctionSetting s = e.getValue();
                if (s.path == null || s.path.isEmpty()) continue;
                String syn = validatePathSyntax(s.path);
                if (syn != null || Router.isCore(s.path) || s.path.equals("/ping")) {
                    info.functionProblems.put(e.getKey(), syn != null ? syn : s.path + " is a core route");
                    continue;
                }
                if (next.containsKey(s.path)) {
                    info.functionProblems.put(e.getKey(), s.path + " is already bound by another function");
                    continue;
                }
                if (!s.enabled) {
                    // the path stays reserved: a call gets 409 "turned off" instead of looking like a typo
                    next.put(s.path, new Binding(info, null, "function '" + e.getKey() + "' of plugin '" + info.id
                            + "' is turned off (Local API > Plugins)"));
                } else if (info.isLoaded()) {
                    PluginFunction fn = info.functions.get(e.getKey());
                    if (fn == null) {
                        next.put(s.path, new Binding(info, null, "plugin '" + info.id + "' has no function '" + e.getKey() + "'"));
                    } else {
                        next.put(s.path, new Binding(info, fn, null));
                    }
                } else {
                    next.put(s.path, new Binding(info, null, "plugin '" + info.id + "' is not active - " + info.reason()));
                }
            }
        }
        bindings = next;
    }

    public static Binding binding(String path) { return bindings.get(path); }

    /** Paths that really exist: core routes plus enabled, active plugin functions. */
    public static List<String> allPaths() {
        TreeSet<String> s = new TreeSet<>(Router.paths());
        s.add("/ping");
        bindings.forEach((p, b) -> { if (b.blocked() == null) s.add(p); });
        return new ArrayList<>(s);
    }

    public static String pathOf(PluginInfo info, String fnId) {
        var s = info.settings.functions.get(fnId);
        return s == null ? "" : s.path;
    }

    public static boolean enabled(PluginInfo info, String fnId) {
        var s = info.settings.functions.get(fnId);
        return s != null && s.enabled;
    }

    // ------------------------------------------------------------------ voice bridge support

    /** Voice-hook instances of all loaded plugins. The bridge casts them; the core never links Voice Chat. */
    public static List<Object> voicePlugins() {
        List<Object> l = new ArrayList<>();
        for (PluginInfo p : PLUGINS) if (p.isLoaded() && p.voicePlugin != null) l.add(p.voicePlugin);
        return l;
    }

    public static PluginInfo infoOfVoicePlugin(Object o) {
        for (PluginInfo p : PLUGINS) if (p.voicePlugin == o) return p;
        return null;
    }

    // ------------------------------------------------------------------ util

    private static String sha256(Path p) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(p)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ plugin options

    private static void clearOptions(PluginInfo info) {
        info.optionSpecs.clear();
        info.optionValues.clear();
        info.onOptionChange = null;
        info.statusLines = null;
    }

    /** null when raw is valid for the spec, otherwise the reason. Canonical form goes to canon[0]. */
    static String validateOption(OptionSpec s, String raw, String[] canon) {
        raw = raw == null ? "" : raw.trim();
        switch (s.kind()) {
            case CHOICE -> {
                if (!s.choices().contains(raw)) return "must be one of: " + String.join(", ", s.choices());
                canon[0] = raw;
            }
            case INTEGER -> {
                long v;
                try { v = Long.parseLong(raw); } catch (NumberFormatException e) { return "must be a whole number"; }
                if (v < (long) s.min() || v > (long) s.max()) return "must be " + (long) s.min() + " to " + (long) s.max();
                canon[0] = Long.toString(v);
            }
            case NUMBER -> {
                double v;
                try { v = Double.parseDouble(raw.replace(',', '.')); } catch (NumberFormatException e) { return "must be a number"; }
                if (Double.isNaN(v) || Double.isInfinite(v) || v < s.min() || v > s.max()) return "must be " + s.min() + " to " + s.max();
                canon[0] = Double.toString(v);
            }
            case TEXT -> {
                if (raw.length() > 500) return "longer than 500 characters";
                canon[0] = raw;
            }
        }
        return null;
    }

    private static Options declareOptions(PluginInfo info, List<OptionSpec> specs, Consumer<String> onChange) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (OptionSpec s : specs) {
            if (s.id() == null || !FUNCTION_ID.matcher(s.id()).matches()) throw new IllegalArgumentException("option id '" + s.id() + "' must match [A-Za-z0-9_.-]{1,60}");
            if (!seen.add(s.id())) throw new IllegalArgumentException("option '" + s.id() + "' declared twice");
            String[] canon = new String[1];
            String err = validateOption(s, s.defaultValue(), canon);
            if (err != null) throw new IllegalArgumentException("default of option '" + s.id() + "' " + err);
        }
        info.optionSpecs.clear();
        info.optionSpecs.addAll(specs);
        info.optionValues.clear();
        for (OptionSpec s : specs) {
            String[] canon = new String[1];
            String value = null;
            JsonElement saved = info.settings.options.get(s.id());
            if (saved != null && saved.isJsonPrimitive() && validateOption(s, saved.getAsString(), canon) == null) value = canon[0];
            if (value == null) { validateOption(s, s.defaultValue(), canon); value = canon[0]; }
            info.optionValues.put(s.id(), value);
        }
        info.onOptionChange = onChange;
        final PluginInfo pi = info;
        return new Options() {
            @Override public String string(String id) { return get(id); }
            @Override public int integer(String id) { return (int) Long.parseLong(get(id)); }
            @Override public double number(String id) { return Double.parseDouble(get(id)); }
            private String get(String id) {
                String v = pi.optionValues.get(id);
                if (v == null) throw new IllegalArgumentException("unknown option " + id);
                return v;
            }
        };
    }

    /** UI changed one option. Returns null on success (saved, plugin informed) or the reason it was rejected. */
    public static synchronized String setOption(PluginInfo info, String optionId, String raw) {
        OptionSpec spec = null;
        for (OptionSpec s : info.optionSpecs) if (s.id().equals(optionId)) spec = s;
        if (spec == null) return "unknown option " + optionId;
        String[] canon = new String[1];
        String err = validateOption(spec, raw, canon);
        if (err != null) return err;
        String old = info.optionValues.get(optionId);
        if (canon[0].equals(old)) return null;
        JsonElement oldJson = info.settings.options.get(optionId);
        JsonElement js = switch (spec.kind()) {
            case INTEGER -> new com.google.gson.JsonPrimitive(Long.parseLong(canon[0]));
            case NUMBER -> new com.google.gson.JsonPrimitive(Double.parseDouble(canon[0]));
            default -> new com.google.gson.JsonPrimitive(canon[0]);
        };
        info.settings.options.put(optionId, js);
        try {
            Files.createDirectories(dir);
            Files.writeString(settingsFile(info.id), GSON.toJson(info.settings));
        } catch (IOException e) {
            if (oldJson == null) info.settings.options.remove(optionId); else info.settings.options.put(optionId, oldJson);
            return "cannot write " + settingsFile(info.id) + ": " + e.getMessage();
        }
        info.optionValues.put(optionId, canon[0]);
        Consumer<String> cb = info.onOptionChange;
        if (cb != null) {
            try { cb.accept(optionId); }
            catch (Throwable t) { info.recordError("option " + optionId + ": " + t); }
        }
        return null;
    }

    // ------------------------------------------------------------------ plugin-facing context

    private static final class Ctx implements PluginContext {
        private final PluginInfo info;
        private final Logger log;

        Ctx(PluginInfo info) {
            this.info = info;
            this.log = LoggerFactory.getLogger("localapi/" + info.id);
        }

        @Override public String pluginId() { return info.id; }
        @Override public Logger logger() { return log; }
        @Override public FunctionBuilder function(String id) { return new Builder(info, id); }

        @Override public Options options(List<OptionSpec> specs, Consumer<String> onChange) {
            return declareOptions(info, specs, onChange);
        }

        @Override public void status(Supplier<List<StatusLine>> lines) { info.statusLines = lines; }

        @Override public boolean pluginLoaded(String id) {
            for (PluginInfo p : PLUGINS) if (id.equals(p.id) && p.isLoaded()) return true;
            return false;
        }
    }

    private static final class Builder implements FunctionBuilder {
        private final PluginInfo info;
        private final PluginFunction fn;

        Builder(PluginInfo info, String id) {
            this.info = info;
            if (id == null || !FUNCTION_ID.matcher(id).matches())
                throw new IllegalArgumentException("function id '" + id + "' must match [A-Za-z0-9_.-]{1,60}");
            this.fn = new PluginFunction(info.id, id);
        }

        @Override public FunctionBuilder title(String t) { fn.title = t == null ? "" : t; return this; }
        @Override public FunctionBuilder description(String d) { fn.description = d == null ? "" : d; return this; }

        @Override public FunctionBuilder suggestedPath(String p) {
            if (p != null && !p.isEmpty()) {
                String err = validatePathSyntax(p);
                if (err != null) throw new IllegalArgumentException("suggestedPath '" + p + "': " + err);
            }
            fn.suggestedPath = p == null ? "" : p;
            return this;
        }

        @Override public FunctionBuilder method(String m) {
            if (!"GET".equals(m) && !"POST".equals(m)) throw new IllegalArgumentException("method must be GET or POST");
            fn.method = m;
            return this;
        }

        @Override public FunctionBuilder param(String name, ParamType type, boolean required, String description) {
            return addParam(name, type, required, null, description);
        }

        @Override public FunctionBuilder param(String name, ParamType type, String defaultValue, String description) {
            if (defaultValue == null) throw new IllegalArgumentException("defaultValue is null: use the 'required' overload");
            return addParam(name, type, false, defaultValue, description);
        }

        private FunctionBuilder addParam(String name, ParamType type, boolean required, String def, String desc) {
            if (name == null || !PARAM_NAME.matcher(name).matches())
                throw new IllegalArgumentException("parameter name '" + name + "' must match [A-Za-z_][A-Za-z0-9_]{0,39}");
            if (type == null) throw new IllegalArgumentException("parameter '" + name + "' has no type");
            for (var p : fn.params) if (p.name().equals(name)) throw new IllegalArgumentException("parameter '" + name + "' declared twice");
            if (def != null && Params.convert(type, def) == null)
                throw new IllegalArgumentException("default of '" + name + "' is not a valid " + type.label + ": " + def);
            fn.params.add(new PluginFunction.Param(name, type, required, def, desc == null ? "" : desc));
            return this;
        }

        @Override public FunctionBuilder once(OnceHandler h) { fn.once = h; fn.gameThread = true; return this; }
        @Override public FunctionBuilder onceOffGameThread(OnceHandler h) { fn.once = h; fn.gameThread = false; return this; }
        @Override public FunctionBuilder stream(StreamHandler h) { fn.stream = h; return this; }

        @Override public void register() {
            if ((fn.once == null) == (fn.stream == null))
                throw new IllegalStateException("function '" + fn.id + "' needs exactly one of once(...) or stream(...)");
            if (info.functions.containsKey(fn.id))
                throw new IllegalStateException("function id '" + fn.id + "' registered twice");
            info.functions.put(fn.id, fn);
        }
    }
}


