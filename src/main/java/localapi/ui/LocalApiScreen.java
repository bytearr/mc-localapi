package localapi.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import localapi.Config;
import localapi.Dispatcher;
import localapi.LocalApiMod;
import localapi.Request;
import localapi.Router;
import localapi.StreamConnection;
import localapi.api.OptionSpec;
import localapi.api.StreamSink;
import localapi.plugin.PluginFunction;
import localapi.plugin.PluginInfo;
import localapi.plugin.PluginManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-game settings screen (opened from Mod Menu): tab "API" (routes, console, access) and tab "Plugins".
 * Both tabs have their own fuzzy search. Calls from this screen go through {@link Dispatcher}, exactly like HTTP.
 */
public final class LocalApiScreen extends Screen {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final ExecutorService EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "localapi-ui");
        t.setDaemon(true);
        return t;
    });

    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, DIM = 0xFF777777, RED = 0xFFFF6B6B,
            GREEN = 0xFF7CFC7C, YELLOW = 0xFFFFE066, ORANGE = 0xFFFFA64D;

    private enum Tab { API, PLUGINS }
    private enum ApiPane { CONSOLE, ACCESS }
    private enum PluginPage { LIST, FUNCTION }

    // remembered between openings
    private static Tab lastTab = Tab.API;

    private final Screen parent;
    private Tab tab = lastTab;

    // ---- shared layout
    private int leftX, leftW, rightX, rightW, contentTop, contentBottom;
    private EditBox searchBox;
    private final List<Label> labels = new ArrayList<>();
    private record Label(String text, int x, int y, int color) {}

    // ---- API tab
    private ApiPane apiPane = ApiPane.CONSOLE;
    private String apiSearch = "";
    private final ListView<Router.RouteDoc> routeList = new ListView<>(
            d -> String.format("%-4s %s  -  %s", d.method(), d.path(), d.summary()), d -> d.method().equals("GET") ? WHITE : YELLOW);
    private Router.RouteDoc shownRoute;
    private String consolePath = "/state", consoleMethod = "GET", consoleBody = "";
    private EditBox pathBox;
    private MultiLineEditBox bodyBox;
    private Button methodButton;
    private volatile String consoleResult = "";
    private volatile boolean consoleBusy;
    private final ResultView consoleView = new ResultView();
    private String pwText = "", portText = "", accessMsg = "";
    private int accessMsgColor = GRAY;

    // ---- Plugins tab
    private PluginPage page = PluginPage.LIST;
    private String pluginSearch = "";
    private final ListView<PluginInfo> pluginList = new ListView<>(LocalApiScreen::pluginLabel, p -> statusColor(p.status));
    private final ListView<PluginFunction> fnList = new ListView<>(
            f -> (fnOn(f) ? "[on]  " : "[off] ") + f.id + (f.isStream() ? "  (stream)" : "") + "  " + PluginManager.pathOf(plugin(f), f.id),
            f -> fnOn(f) ? GREEN : GRAY);
    private final List<OptRow> optRows = new ArrayList<>();
    private final Map<String, String> optDraft = new HashMap<>();
    private final Map<String, String> optErr = new HashMap<>();
    private int optScroll, optBlockTop, optBlockBottom;
    private PluginInfo selPlugin;
    private PluginFunction selFn;
    private boolean fnEnabledDraft;
    private String fnPathDraft = "";
    private String fnMsg = "";
    private int fnMsgColor = GRAY;
    private EditBox fnPathBox;
    private final Map<String, String> paramValues = new LinkedHashMap<>();
    private final Map<String, EditBox> paramBoxes = new HashMap<>();
    private volatile String testResult = "";
    private volatile boolean testBusy;
    private final ResultView testView = new ResultView();
    private volatile TestSink streamSink;

    public LocalApiScreen(Screen parent) {
        super(Component.literal("Local API"));
        this.parent = parent;
    }

    // =================================================================== layout / widgets

    @Override
    protected void init() {
        labels.clear();
        paramBoxes.clear();
        pathBox = null;
        bodyBox = null;
        methodButton = null;
        fnPathBox = null;
        searchBox = null;
        routeList.visible = false;
        pluginList.visible = false;
        fnList.visible = false;

        leftX = 10;
        leftW = Math.max(120, this.width / 2 - 15);
        rightX = this.width / 2 + 5;
        rightW = this.width - rightX - 10;
        contentTop = 66;
        contentBottom = this.height - 32;

        Button apiTab = addRenderableWidget(Button.builder(Component.literal("API"), b -> switchTab(Tab.API)).bounds(10, 18, 70, 20).build());
        Button plTab = addRenderableWidget(Button.builder(Component.literal("Plugins"), b -> switchTab(Tab.PLUGINS)).bounds(84, 18, 70, 20).build());
        (tab == Tab.API ? apiTab : plTab).active = false;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(this.width / 2 - 50, this.height - 26, 100, 20).build());

        if (tab == Tab.API) initApi(); else initPlugins();
    }

    private void switchTab(Tab t) {
        tab = t;
        lastTab = t;
        rebuildWidgets();
    }

    private EditBox newSearch(String value, java.util.function.Consumer<String> onChange) {
        EditBox b = new EditBox(this.font, leftX, 44, leftW, 16, Component.literal("Search"));
        b.setHint(Component.literal("search (typos are ok)"));
        b.setMaxLength(60);
        b.setValue(value);
        b.setResponder(onChange);
        return addRenderableWidget(b);
    }

    // ------------------------------------------------------------------- API tab

    private void initApi() {
        searchBox = newSearch(apiSearch, v -> { apiSearch = v; refreshRoutes(); });
        routeList.visible = true;
        routeList.bounds(leftX, contentTop, leftW, contentBottom - contentTop);
        refreshRoutes();

        addRenderableWidget(Button.builder(Component.literal("Console"), b -> { apiPane = ApiPane.CONSOLE; rebuildWidgets(); })
                .bounds(rightX, 44, rightW / 2 - 2, 16).build()).active = apiPane != ApiPane.CONSOLE;
        addRenderableWidget(Button.builder(Component.literal("Access (password, port)"), b -> { apiPane = ApiPane.ACCESS; rebuildWidgets(); })
                .bounds(rightX + rightW / 2 + 2, 44, rightW / 2 - 2, 16).build()).active = apiPane != ApiPane.ACCESS;

        if (apiPane == ApiPane.CONSOLE) initConsole(); else initAccess();
    }

    private void refreshRoutes() {
        routeList.set(Fuzzy.filter(Router.docs(), apiSearch,
                d -> d.method() + " " + d.path() + " " + d.summary() + " " + String.join(" ", d.params())));
    }

    private void initConsole() {
        int y = contentTop + 38; // room for the route description drawn in render()
        pathBox = new EditBox(this.font, rightX, y, rightW - 46, 16, Component.literal("Path"));
        pathBox.setMaxLength(300);
        pathBox.setValue(consolePath);
        pathBox.setResponder(v -> consolePath = v);
        addRenderableWidget(pathBox);
        methodButton = addRenderableWidget(Button.builder(Component.literal(consoleMethod), b -> {
            consoleMethod = consoleMethod.equals("GET") ? "POST" : "GET";
            b.setMessage(Component.literal(consoleMethod));
        }).bounds(rightX + rightW - 42, y, 42, 16).build());
        y += 20;
        int bodyH = Math.max(34, Math.min(60, (contentBottom - y) / 3));
        bodyBox = MultiLineEditBox.builder().setPlaceholder(Component.literal("JSON body (POST), e.g. {\"key\":\"forward\"}"))
                .setX(rightX).setY(y).build(this.font, rightW, bodyH, Component.literal("Body"));
        bodyBox.setValue(consoleBody);
        bodyBox.setValueListener(v -> consoleBody = v);
        addRenderableWidget(bodyBox);
        y += bodyH + 3;
        addRenderableWidget(Button.builder(Component.literal("Send"), b -> runConsole()).bounds(rightX, y, 60, 16).build());
        labels.add(new Label("same dispatcher as HTTP, no password needed here", rightX + 66, y + 4, DIM));
        y += 20;
        consoleView.bounds(rightX, y, rightW, Math.max(20, contentBottom - y));
    }

    private void initAccess() {
        Config cfg = LocalApiMod.CONFIG;
        if (pwText.isEmpty() && cfg != null) pwText = cfg.password;
        if (portText.isEmpty() && cfg != null) portText = String.valueOf(cfg.port);
        int y = contentTop + 2;
        labels.add(new Label("Password (header X-Password, Bearer or ?password=)", rightX, y, GRAY));
        y += 11;
        EditBox pw = new EditBox(this.font, rightX, y, rightW - 74, 16, Component.literal("Password"));
        pw.setMaxLength(100);
        pw.setValue(pwText);
        pw.setResponder(v -> pwText = v);
        addRenderableWidget(pw);
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> savePassword()).bounds(rightX + rightW - 70, y, 70, 16).build());
        y += 24;
        labels.add(new Label("Port (127.0.0.1 only)", rightX, y, GRAY));
        y += 11;
        EditBox port = new EditBox(this.font, rightX, y, rightW - 74, 16, Component.literal("Port"));
        port.setMaxLength(5);
        port.setValue(portText);
        port.setResponder(v -> portText = v);
        addRenderableWidget(port);
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> savePort()).bounds(rightX + rightW - 70, y, 70, 16).build());
    }

    private void savePassword() {
        Config cfg = LocalApiMod.CONFIG;
        if (cfg == null) { accessMsg = "mod not initialised"; accessMsgColor = RED; return; }
        String p = pwText;
        if (p.isEmpty() || !p.matches("[\\x21-\\x7E]{1,100}")) {
            accessMsg = "password: 1-100 visible ASCII characters, no spaces (it travels in an HTTP header)";
            accessMsgColor = RED;
            return;
        }
        cfg.password = p;
        String err = cfg.save();
        if (err != null) { accessMsg = err; accessMsgColor = RED; return; }
        if (LocalApiMod.SERVER != null) LocalApiMod.SERVER.setPassword(p);
        accessMsg = "password saved to config/localapi.json and active now";
        accessMsgColor = GREEN;
    }

    private void savePort() {
        Config cfg = LocalApiMod.CONFIG;
        if (cfg == null) { accessMsg = "mod not initialised"; accessMsgColor = RED; return; }
        int p;
        try { p = Integer.parseInt(portText.trim()); } catch (NumberFormatException e) { p = -1; }
        if (p < 1024 || p > 65535) { accessMsg = "port must be a number from 1024 to 65535"; accessMsgColor = RED; return; }
        cfg.port = p;
        String err = cfg.save();
        if (err != null) { accessMsg = err; accessMsgColor = RED; return; }
        accessMsg = "port " + p + " saved. It applies after you restart the client.";
        accessMsgColor = YELLOW;
    }

    // ------------------------------------------------------------------- console

    private void selectRoute(Router.RouteDoc d) {
        shownRoute = d;
        consolePath = d.path();
        consoleMethod = d.method();
        StringBuilder q = new StringBuilder();
        JsonObject body = new JsonObject();
        for (String p : d.params()) {
            if (!p.endsWith("!")) continue;
            String name = p.substring(0, p.indexOf(':'));
            String type = p.substring(p.indexOf(':') + 1, p.length() - 1);
            if (d.method().equals("GET")) q.append(q.length() == 0 ? "?" : "&").append(name).append('=').append(sample(type));
            else body.add(name, sampleJson(type));
        }
        consolePath = d.path() + q;
        consoleBody = d.method().equals("POST") && body.size() > 0 ? PRETTY.toJson(body) : "";
        if (pathBox != null) {
            pathBox.setValue(consolePath);
            bodyBox.setValue(consoleBody);
            methodButton.setMessage(Component.literal(consoleMethod));
        }
    }

    private static String sample(String type) {
        return type.startsWith("boolean") ? "false" : type.startsWith("string") ? "text" : "0";
    }

    private static JsonElement sampleJson(String type) {
        if (type.startsWith("boolean")) return new com.google.gson.JsonPrimitive(false);
        if (type.startsWith("string")) return new com.google.gson.JsonPrimitive("");
        if (type.startsWith("json")) return new com.google.gson.JsonArray();
        return new com.google.gson.JsonPrimitive(0);
    }

    private void runConsole() {
        if (consoleBusy) return;
        Request req;
        try {
            String raw = consolePath.trim();
            String path = raw;
            Map<String, String> query = new HashMap<>();
            int q = raw.indexOf('?');
            if (q >= 0) {
                path = raw.substring(0, q);
                for (String kv : raw.substring(q + 1).split("&")) {
                    if (kv.isEmpty()) continue;
                    int i = kv.indexOf('=');
                    query.put(URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8),
                            i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
                }
            }
            if (!path.startsWith("/")) throw new IllegalArgumentException("path must start with /");
            JsonObject body = new JsonObject();
            if (!consoleBody.isBlank()) {
                JsonElement el = JsonParser.parseString(consoleBody);
                if (!el.isJsonObject()) throw new IllegalArgumentException("body must be a JSON object");
                body = el.getAsJsonObject();
            }
            req = new Request(consoleMethod, path, query, body);
        } catch (Exception e) {
            consoleResult = "Cannot send: " + e.getMessage();
            return;
        }
        consoleBusy = true;
        consoleResult = "waiting for the game thread ...";
        final Request r = req;
        EXEC.execute(() -> {
            try {
                consoleResult = render(Dispatcher.call(r, timeout()));
            } catch (Throwable t) {
                consoleResult = "Error: " + t;
            } finally {
                consoleBusy = false;
            }
        });
    }

    private static int timeout() {
        return LocalApiMod.CONFIG != null ? LocalApiMod.CONFIG.timeoutMs : 5000;
    }

    private static String render(Dispatcher.Outcome o) {
        JsonObject body = localapi.ApiServer.toJson(o);
        return "HTTP " + o.status() + "\n" + PRETTY.toJson(body);
    }

    // ------------------------------------------------------------------- Plugins tab

    private static PluginInfo plugin(PluginFunction f) {
        for (PluginInfo p : PluginManager.plugins()) if (p.functions.get(f.id) == f) return p;
        return null;
    }

    private static boolean fnOn(PluginFunction f) {
        PluginInfo p = plugin(f);
        return p != null && PluginManager.enabled(p, f.id);
    }

    private static int statusColor(PluginInfo.Status s) {
        return switch (s) {
            case LOADED -> GREEN;
            case NOT_APPROVED -> YELLOW;
            case MISSING_DEPENDENCY -> ORANGE;
            default -> RED;
        };
    }

    private static String pluginLabel(PluginInfo p) {
        return p.displayName() + (p.version != null ? "  " + p.version : "") + "   [" + p.status.name().toLowerCase().replace('_', ' ') + "]";
    }

    private void initPlugins() {
        if (page == PluginPage.FUNCTION && (selPlugin == null || selFn == null)) page = PluginPage.LIST;
        if (page == PluginPage.LIST) initPluginList(); else initFunctionPage();
    }

    private void initPluginList() {
        searchBox = newSearch(pluginSearch, v -> { pluginSearch = v; refreshPlugins(); });
        pluginList.visible = true;
        pluginList.bounds(leftX, contentTop, leftW, contentBottom - contentTop);
        refreshPlugins();

        addRenderableWidget(Button.builder(Component.literal("Rescan folder"), b -> { PluginManager.rescan(); refreshPlugins(); })
                .bounds(rightX, 44, rightW / 2 - 2, 16).build());
        PluginInfo p = selPlugin;
        if (p != null && p.status == PluginInfo.Status.NOT_APPROVED) {
            addRenderableWidget(Button.builder(Component.literal("Approve and load"), b -> {
                String err = PluginManager.approve(p);
                if (err != null) fnMsg = err;
                refreshPlugins();
                rebuildWidgets();
            }).bounds(rightX + rightW / 2 + 2, 44, rightW / 2 - 2, 16).build());
        }
        optRows.clear();
        optBlockTop = optBlockBottom = 0;
        if (p != null && p.isLoaded()) {
            int y = statusTop(p) + (p.statusLines != null ? STATUS_ROWS * 10 + 4 : 0);
            boolean hasFns = !p.functions.isEmpty();
            boolean hasOpts = !p.optionSpecs.isEmpty();
            if (hasFns) {
                int rows = hasOpts ? Math.min(p.functions.size(), 3) : Math.max(2, (contentBottom - 20 - y) / ListView.ROW_H);
                int h = Math.max(2, rows) * ListView.ROW_H;
                fnList.visible = true;
                fnList.bounds(rightX, y, rightW, h);
                refreshFunctions();
                y += h + 3;
                addRenderableWidget(Button.builder(Component.literal("Open function page"), b -> openFunction(fnList.selected()))
                        .bounds(rightX, y, rightW, 16).build());
                y += 20;
            }
            if (hasOpts) initOptions(p, y + 12);
        }
    }

    private static final int STATUS_ROWS = 5;
    private static final int OPT_ROW_H = 18;

    private int statusTop(PluginInfo p) {
        return contentTop + 11 + 10 + p.notes.size() * 10 + 4;
    }

    private record OptRow(OptionSpec spec, AbstractWidget widget, Button apply) {}

    private void initOptions(PluginInfo p, int top) {
        optBlockTop = top;
        optBlockBottom = contentBottom;
        int labelW = rightW * 2 / 5;
        int cx = rightX + labelW + 4;
        int cw = rightW - labelW - 4;
        for (OptionSpec s : p.optionSpecs) {
            final String key = p.id + ":" + s.id();
            AbstractWidget w;
            Button apply = null;
            if (s.kind() == OptionSpec.Kind.CHOICE) {
                w = Button.builder(Component.literal(p.optionValues.get(s.id())), b -> {
                    List<String> ch = s.choices();
                    int i = ch.indexOf(p.optionValues.get(s.id()));
                    String err = PluginManager.setOption(p, s.id(), ch.get((i + 1) % ch.size()));
                    if (err != null) optErr.put(key, err); else optErr.remove(key);
                    b.setMessage(Component.literal(p.optionValues.get(s.id())));
                }).bounds(cx, 0, cw, 16).build();
            } else {
                boolean text = s.kind() == OptionSpec.Kind.TEXT;
                int bw = text ? cw - 46 : cw;
                EditBox eb = new EditBox(this.font, cx, 0, bw, 16, Component.literal(s.label()));
                eb.setMaxLength(text ? 500 : 20);
                eb.setValue(optDraft.getOrDefault(key, p.optionValues.get(s.id())));
                if (text) {
                    eb.setResponder(v -> optDraft.put(key, v));
                    apply = Button.builder(Component.literal("Apply"), b -> {
                        String err = PluginManager.setOption(p, s.id(), eb.getValue());
                        if (err != null) optErr.put(key, err); else { optErr.remove(key); optDraft.remove(key); }
                    }).bounds(cx + bw + 2, 0, 44, 16).build();
                } else {
                    eb.setResponder(v -> {
                        optDraft.put(key, v);
                        String err = PluginManager.setOption(p, s.id(), v);
                        if (err != null) { optErr.put(key, err); eb.setTextColor(0xFFFF6B6B); }
                        else { optErr.remove(key); eb.setTextColor(0xFFE0E0E0); }
                    });
                }
                w = eb;
            }
            addRenderableWidget(w);
            if (apply != null) addRenderableWidget(apply);
            optRows.add(new OptRow(s, w, apply));
        }
        layoutOptions();
    }

    private int optVisibleRows() { return Math.max(1, (optBlockBottom - optBlockTop) / OPT_ROW_H); }

    private void layoutOptions() {
        int vis = optVisibleRows();
        optScroll = Math.max(0, Math.min(optScroll, Math.max(0, optRows.size() - vis)));
        for (int i = 0; i < optRows.size(); i++) {
            OptRow r = optRows.get(i);
            int rel = i - optScroll;
            boolean show = rel >= 0 && rel < vis;
            int y = optBlockTop + rel * OPT_ROW_H;
            r.widget().setY(y);
            r.widget().visible = show;
            r.widget().active = show;
            if (r.apply() != null) {
                r.apply().setY(y);
                r.apply().visible = show;
                r.apply().active = show;
            }
        }
    }

    private void refreshPlugins() {
        pluginList.set(Fuzzy.filter(PluginManager.plugins(), pluginSearch, p -> {
            StringBuilder sb = new StringBuilder();
            sb.append(p.displayName()).append(' ').append(p.id).append(' ').append(p.fileName).append(' ')
                    .append(p.status.label).append(' ').append(String.join(" ", p.problems)).append(' ').append(p.description);
            for (PluginFunction f : p.functions.values())
                sb.append(' ').append(f.id).append(' ').append(f.title).append(' ').append(PluginManager.pathOf(p, f.id));
            return sb.toString();
        }));
        refreshFunctions();
    }

    private void refreshFunctions() {
        PluginInfo p = selPlugin;
        if (p == null || !p.isLoaded()) { fnList.set(new ArrayList<>()); return; }
        fnList.set(Fuzzy.filter(new ArrayList<>(p.functions.values()), pluginSearch,
                f -> f.id + " " + f.title + " " + f.description + " " + PluginManager.pathOf(p, f.id) + " " + f.method));
    }

    private void openFunction(PluginFunction f) {
        if (f == null) return;
        selFn = f;
        PluginInfo p = selPlugin;
        fnEnabledDraft = PluginManager.enabled(p, f.id);
        String saved = PluginManager.pathOf(p, f.id);
        fnPathDraft = saved.isEmpty() ? f.suggestedPath : saved;
        fnMsg = "";
        paramValues.clear();
        testResult = "";
        closeStream();
        page = PluginPage.FUNCTION;
        rebuildWidgets();
    }

    private void initFunctionPage() {
        PluginInfo p = selPlugin;
        PluginFunction f = selFn;
        addRenderableWidget(Button.builder(Component.literal("< Back"), b -> { closeStream(); page = PluginPage.LIST; rebuildWidgets(); })
                .bounds(this.width - 80, 18, 70, 20).build());

        int y = contentTop - 8;
        // left column: switch, path, save
        y += 62; // title, kind/method, description lines are drawn in render()
        addRenderableWidget(Button.builder(Component.literal("Function: " + (fnEnabledDraft ? "ON" : "OFF")), b -> {
            fnEnabledDraft = !fnEnabledDraft;
            b.setMessage(Component.literal("Function: " + (fnEnabledDraft ? "ON" : "OFF")));
        }).bounds(leftX, y, leftW, 16).build());
        y += 22;
        labels.add(new Label("Public path (suggestion: " + (f.suggestedPath.isEmpty() ? "none" : f.suggestedPath) + ")", leftX, y, GRAY));
        y += 11;
        fnPathBox = new EditBox(this.font, leftX, y, leftW - 56, 16, Component.literal("Path"));
        fnPathBox.setMaxLength(120);
        fnPathBox.setValue(fnPathDraft);
        fnPathBox.setResponder(v -> fnPathDraft = v);
        addRenderableWidget(fnPathBox);
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> saveFunction()).bounds(leftX + leftW - 52, y, 52, 16).build());

        // right column: parameters + test
        int ry = contentTop;
        labels.add(new Label("Test with parameters", rightX, ry - 10, GRAY));
        int shown = 0;
        for (PluginFunction.Param prm : f.params) {
            if (shown >= 6) break;
            EditBox eb = new EditBox(this.font, rightX, ry, rightW, 16, Component.literal(prm.name()));
            eb.setMaxLength(2000);
            eb.setHint(Component.literal(prm.name() + "  (" + prm.type().label + (prm.required() ? ", required" : prm.defaultValue() != null ? ", default " + prm.defaultValue() : ", optional") + ")"));
            eb.setValue(paramValues.getOrDefault(prm.name(), ""));
            String name = prm.name();
            eb.setResponder(v -> paramValues.put(name, v));
            addRenderableWidget(eb);
            paramBoxes.put(prm.name(), eb);
            ry += 19;
            shown++;
        }
        if (f.params.size() > shown) {
            labels.add(new Label("+" + (f.params.size() - shown) + " more parameter(s): use the API console", rightX, ry + 2, DIM));
            ry += 12;
        }
        if (f.params.isEmpty()) { labels.add(new Label("(no parameters)", rightX, ry + 2, DIM)); ry += 12; }
        if (f.isStream()) {
            addRenderableWidget(Button.builder(Component.literal("Start stream test"), b -> startStreamTest()).bounds(rightX, ry, rightW / 2 - 2, 16).build());
            addRenderableWidget(Button.builder(Component.literal("Stop"), b -> closeStream()).bounds(rightX + rightW / 2 + 2, ry, rightW / 2 - 2, 16).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Run test"), b -> runTest()).bounds(rightX, ry, rightW, 16).build());
        }
        ry += 20;
        testView.bounds(rightX, ry, rightW, Math.max(20, contentBottom - ry));
    }

    private void saveFunction() {
        String err = PluginManager.saveFunction(selPlugin, selFn.id, fnEnabledDraft, fnPathDraft);
        if (err != null) {
            fnMsg = err;
            fnMsgColor = RED;
        } else {
            fnMsg = "saved to config/localapi/plugins/" + selPlugin.id + ".json";
            fnMsgColor = GREEN;
        }
    }

    private Request testRequest() {
        PluginFunction f = selFn;
        String path = PluginManager.pathOf(selPlugin, f.id);
        if (path.isEmpty()) throw new IllegalStateException("save a path first, the test uses the public path");
        Map<String, String> query = new HashMap<>();
        JsonObject body = new JsonObject();
        for (PluginFunction.Param p : f.params) {
            String v = paramValues.getOrDefault(p.name(), "");
            if (v.isEmpty()) continue;
            if (f.method.equals("GET")) { query.put(p.name(), v); continue; }
            try {
                switch (p.type()) {
                    case INTEGER -> body.addProperty(p.name(), Long.parseLong(v.trim()));
                    case NUMBER -> body.addProperty(p.name(), Double.parseDouble(v.trim()));
                    case BOOLEAN -> body.addProperty(p.name(), Boolean.parseBoolean(v.trim()));
                    case JSON -> body.add(p.name(), JsonParser.parseString(v));
                    default -> body.addProperty(p.name(), v);
                }
            } catch (Exception e) {
                // send it as text: the dispatcher answers 400 with the exact reason, like a real caller would get
                body.addProperty(p.name(), v);
            }
        }
        return new Request(f.method, path, query, body);
    }

    private void runTest() {
        if (testBusy) return;
        Request req;
        try { req = testRequest(); } catch (Exception e) { testResult = "Cannot test: " + e.getMessage(); return; }
        testBusy = true;
        testResult = "waiting ...";
        EXEC.execute(() -> {
            try { testResult = render(Dispatcher.call(req, timeout())); }
            catch (Throwable t) { testResult = "Error: " + t; }
            finally { testBusy = false; }
        });
    }

    private void startStreamTest() {
        if (testBusy || (streamSink != null && streamSink.isOpen())) return;
        Request req;
        try { req = testRequest(); } catch (Exception e) { testResult = "Cannot test: " + e.getMessage(); return; }
        testBusy = true;
        testResult = "connecting ...";
        TestSink sink = new TestSink();
        streamSink = sink;
        EXEC.execute(() -> {
            try {
                Dispatcher.Outcome o = Dispatcher.openStream(req, sink);
                if (!o.ok()) { testResult = render(o); streamSink = null; }
                else sink.connected = true;
            } catch (Throwable t) {
                testResult = "Error: " + t;
                streamSink = null;
            } finally {
                testBusy = false;
            }
        });
    }

    private void closeStream() {
        TestSink s = streamSink;
        if (s != null) s.close();
    }

    /** Counts frames, never keeps their bytes. */
    private static final class TestSink implements StreamSink {
        final AtomicLong frames = new AtomicLong();
        volatile boolean open = true;
        volatile boolean connected;
        volatile String lastType = "";
        private final List<Runnable> closers = new ArrayList<>();
        private boolean closersRun;

        @Override public boolean send(JsonObject line) {
            if (!open) return false;
            frames.incrementAndGet();
            if (line.has("type")) lastType = line.get("type").getAsString();
            return true;
        }

        @Override public boolean isOpen() { return open; }

        @Override public void close() {
            open = false;
            List<Runnable> run;
            synchronized (closers) { if (closersRun) return; closersRun = true; run = new ArrayList<>(closers); }
            for (Runnable r : run) { try { r.run(); } catch (Throwable ignored) {} }
        }

        @Override public void onClose(Runnable r) {
            boolean now;
            synchronized (closers) { now = closersRun; if (!now) closers.add(r); }
            if (now) r.run();
        }
    }

    // =================================================================== input

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubled) {
        // 26.3: the primary (left) button is reported as 1, same check as AbstractWidget.isValidClickButton
        if (e.button() == 1) {
            if (tab == Tab.API && routeList.contains(e.x(), e.y())) {
                Router.RouteDoc d = routeList.click(e.x(), e.y());
                if (d != null) selectRoute(d);
                return true;
            }
            if (tab == Tab.PLUGINS && page == PluginPage.LIST) {
                if (pluginList.contains(e.x(), e.y())) {
                    PluginInfo p = pluginList.click(e.x(), e.y());
                    if (p != null && p != selPlugin) { selPlugin = p; fnMsg = ""; rebuildWidgets(); }
                    return true;
                }
                if (fnList.contains(e.x(), e.y())) {
                    PluginFunction f = fnList.click(e.x(), e.y());
                    if (f != null && doubled) openFunction(f);
                    return true;
                }
            }
        }
        return super.mouseClicked(e, doubled);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (tab == Tab.API) {
            if (routeList.scroll(x, y, sy)) return true;
            if (apiPane == ApiPane.CONSOLE && consoleView.scroll(x, y, sy)) return true;
        } else if (page == PluginPage.LIST) {
            if (!optRows.isEmpty() && x >= rightX && y >= optBlockTop && y < optBlockBottom) {
                optScroll -= (int) Math.signum(sy);
                layoutOptions();
                return true;
            }
            if (pluginList.scroll(x, y, sy) || fnList.scroll(x, y, sy)) return true;
        } else if (testView.scroll(x, y, sy)) {
            return true;
        }
        return super.mouseScrolled(x, y, sx, sy);
    }

    @Override
    public void onClose() {
        closeStream();
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void removed() {
        closeStream();
    }

    // =================================================================== render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        g.text(this.font, "Local API", 10, 6, WHITE);
        String port = LocalApiMod.CONFIG != null ? String.valueOf(LocalApiMod.CONFIG.port) : "?";
        g.text(this.font, "127.0.0.1:" + port + "  (port changes apply after a client restart)",
                10 + this.font.width("Local API") + 10, 6, GRAY);
        for (Label l : labels) g.text(this.font, l.text(), l.x(), l.y(), l.color());

        if (tab == Tab.API) renderApi(g, mx, my);
        else if (page == PluginPage.LIST) renderPluginList(g, mx, my);
        else renderFunction(g, mx, my);
    }

    private void renderApi(GuiGraphicsExtractor g, int mx, int my) {
        routeList.render(g, this.font, mx, my);
        if (apiPane == ApiPane.CONSOLE) {
            Router.RouteDoc d = shownRoute != null ? shownRoute : routeList.selected();
            int y = contentTop;
            if (d != null) {
                g.text(this.font, this.font.plainSubstrByWidth(d.method() + " " + d.path(), rightW), rightX, y, WHITE);
                g.text(this.font, this.font.plainSubstrByWidth(d.summary(), rightW), rightX, y + 11, GRAY);
                String ps = d.params().isEmpty() ? "no parameters" : "params: " + String.join(", ", d.params()) + "   (! = required)";
                g.text(this.font, this.font.plainSubstrByWidth(ps, rightW), rightX, y + 22, DIM);
            } else {
                g.text(this.font, "pick a route on the left, or type any path below", rightX, y, DIM);
            }
            consoleView.render(g, this.font, consoleResult);
        } else if (!accessMsg.isEmpty()) {
            drawWrapped(g, accessMsg, rightX, contentTop + 100, rightW, accessMsgColor, 5);
        }
        if (apiPane == ApiPane.ACCESS) {
            drawWrapped(g, "Port changes are written to config/localapi.json and apply only after you restart the Minecraft client. "
                    + "The password applies immediately.", rightX, contentTop + 62, rightW, DIM, 4);
        }
    }

    private void renderPluginList(GuiGraphicsExtractor g, int mx, int my) {
        pluginList.render(g, this.font, mx, my);
        PluginInfo p = selPlugin;
        int y = contentTop;
        if (p == null) {
            g.text(this.font, "Jars in config/localapi/plugins:  " + PluginManager.plugins().size(), rightX, y, GRAY);
            drawWrapped(g, "Select a plugin on the left. New jars wait for your approval because they run code in your client.",
                    rightX, y + 12, rightW, DIM, 4);
            return;
        }
        g.text(this.font, this.font.plainSubstrByWidth(p.displayName() + "  " + (p.version == null ? "" : p.version) + "   " + p.fileName, rightW), rightX, y - 0, WHITE);
        // status / problems
        int used = drawWrapped(g, p.reason(), rightX, y + 11, rightW, statusColor(p.status), 3);
        int ny = y + 11 + used * 10;
        for (String n : p.notes) { g.text(this.font, this.font.plainSubstrByWidth("note: " + n, rightW), rightX, ny, YELLOW); ny += 10; }
        if (p.isLoaded()) {
            renderStatus(g, p);
            fnList.render(g, this.font, mx, my);
            renderOptions(g, p);
        }
        // runtime errors at the bottom
        List<String> errs = p.recentErrors();
        int ey = contentBottom - 36;
        if (!errs.isEmpty() && !p.isLoaded()) ey = contentBottom - 36;
        if (!errs.isEmpty() && p.optionSpecs.isEmpty()) {
            g.text(this.font, "recent errors:", rightX, ey - 0, RED);
            for (int i = 0; i < Math.min(2, errs.size()); i++)
                g.text(this.font, this.font.plainSubstrByWidth(errs.get(i), rightW), rightX, ey + 10 + i * 10, GRAY);
        }
        if (!fnMsg.isEmpty()) g.text(this.font, this.font.plainSubstrByWidth(fnMsg, rightW), rightX, contentBottom - 2 - 10 - 20, RED);
    }

    private void renderStatus(GuiGraphicsExtractor g, PluginInfo p) {
        List<localapi.api.StatusLine> lines = new ArrayList<>();
        try {
            var sup = p.statusLines;
            if (sup != null) lines.addAll(sup.get());
        } catch (Throwable t) {
            lines.add(localapi.api.StatusLine.error("status failed: " + t));
        }
        List<String> errs = p.recentErrors();
        if (!errs.isEmpty() && p.statusLines != null) lines.add(localapi.api.StatusLine.error("last error: " + errs.get(0)));
        int y = statusTop(p);
        int used = 0;
        for (localapi.api.StatusLine l : lines) {
            if (used >= STATUS_ROWS) break;
            int color = switch (l.level()) { case OK -> GREEN; case WARN -> YELLOW; case ERROR -> RED; default -> GRAY; };
            used += drawWrapped(g, l.text(), rightX, y + used * 10, rightW, color, Math.min(2, STATUS_ROWS - used));
        }
    }

    private void renderOptions(GuiGraphicsExtractor g, PluginInfo p) {
        if (optRows.isEmpty()) return;
        int hy = optBlockTop - 11;
        g.text(this.font, "Settings (saved at once)", rightX, hy, WHITE);
        String firstErr = null;
        for (OptRow r : optRows) {
            String e = optErr.get(p.id + ":" + r.spec().id());
            if (e != null) { firstErr = r.spec().label() + " " + e; break; }
        }
        if (firstErr != null) {
            int sx = rightX + this.font.width("Settings (saved at once)") + 8;
            g.text(this.font, this.font.plainSubstrByWidth(firstErr, rightX + rightW - sx), sx, hy, RED);
        }
        int vis = optVisibleRows();
        int labelW = rightW * 2 / 5;
        for (int i = 0; i < optRows.size(); i++) {
            int rel = i - optScroll;
            if (rel < 0 || rel >= vis) continue;
            OptionSpec s = optRows.get(i).spec();
            g.text(this.font, this.font.plainSubstrByWidth(s.label(), labelW), rightX, optBlockTop + rel * OPT_ROW_H + 4, GRAY);
        }
        if (optRows.size() > vis) {
            int bh = Math.max(8, (optBlockBottom - optBlockTop) * vis / optRows.size());
            int by = optBlockTop + ((optBlockBottom - optBlockTop) - bh) * optScroll / Math.max(1, optRows.size() - vis);
            g.fill(rightX + rightW + 3, by, rightX + rightW + 5, by + bh, 0xFF888888);
        }
    }

    private void renderFunction(GuiGraphicsExtractor g, int mx, int my) {
        PluginFunction f = selFn;
        PluginInfo p = selPlugin;
        int y = contentTop - 8;
        g.text(this.font, this.font.plainSubstrByWidth(p.displayName() + "  >  " + f.displayTitle(), leftW), leftX, y, WHITE);
        g.text(this.font, this.font.plainSubstrByWidth(f.id + "   " + (f.isStream() ? "stream (open connection)" : "one-shot JSON call")
                + "   " + f.method, leftW), leftX, y + 11, GRAY);
        drawWrapped(g, f.description.isEmpty() ? "(no description)" : f.description, leftX, y + 24, leftW, DIM, 4);
        // message under the path box
        if (!fnMsg.isEmpty()) drawWrapped(g, fnMsg, leftX, contentTop + 92 + 40, leftW, fnMsgColor, 4);
        if (fnPathBox != null) {
            String shown = PluginManager.pathOf(p, f.id);
            String state = shown.isEmpty() ? "not saved yet" : "saved: " + f.method + " " + shown + (PluginManager.enabled(p, f.id) ? "  (on)" : "  (off)");
            g.text(this.font, this.font.plainSubstrByWidth(state, leftW), leftX, fnPathBox.getY() + 20, shown.isEmpty() ? DIM : GRAY);
        }

        // right: result
        TestSink s = streamSink;
        if (f.isStream() && s != null) {
            String line = s.connected
                    ? (s.isOpen() ? "connected, open" : "connection closed") + "   frames: " + s.frames.get()
                    : (s.isOpen() ? "connecting ..." : "closed");
            g.text(this.font, line, testView.x, testView.y, s.isOpen() ? GREEN : YELLOW);
            g.text(this.font, "last line type: " + (s.lastType.isEmpty() ? "-" : s.lastType) + "   (bytes are not shown)",
                    testView.x, testView.y + 11, DIM);
        } else {
            testView.render(g, this.font, testResult);
        }
    }

    /** @return number of lines drawn */
    private int drawWrapped(GuiGraphicsExtractor g, String text, int x, int y, int w, int color, int maxLines) {
        List<FormattedCharSequence> lines = this.font.split(Component.literal(text), w);
        int n = Math.min(maxLines, lines.size());
        for (int i = 0; i < n; i++) g.text(this.font, lines.get(i), x, y + i * 10, color);
        return n;
    }

    /** Scrollable, wrapped text box for JSON results. */
    private static final class ResultView {
        int x, y, w, h, scroll;
        private String cachedText = null;
        private int cachedW = -1;
        private List<FormattedCharSequence> lines = new ArrayList<>();

        void bounds(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }

        boolean scroll(double mx, double my, double dy) {
            if (mx < x || mx >= x + w || my < y || my >= y + h) return false;
            scroll = Math.max(0, scroll - (int) Math.signum(dy) * 3);
            return true;
        }

        void render(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, String text) {
            if (h <= 0) return;
            if (!text.equals(cachedText) || cachedW != w) {
                if (cachedText != null && !text.equals(cachedText)) scroll = 0;
                cachedText = text;
                cachedW = w;
                lines = new ArrayList<>();
                for (String l : text.split("\n")) lines.addAll(font.split(Component.literal(l.isEmpty() ? " " : l), w - 6));
            }
            g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
            g.fill(x, y, x + w, y + h, 0xFF141414);
            int rows = Math.max(1, h / 10);
            scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - rows)));
            g.enableScissor(x, y, x + w, y + h);
            for (int i = 0; i < rows && scroll + i < lines.size(); i++)
                g.text(font, lines.get(scroll + i), x + 3, y + 2 + i * 10, 0xFFDDDDDD);
            g.disableScissor();
            if (lines.size() > rows) g.text(font, (scroll + 1) + "/" + Math.max(1, lines.size() - rows + 1), x + w - 30, y + h - 10, DIM);
        }
    }
}
