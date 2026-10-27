package captions;

import localapi.api.AudioBus;
import localapi.api.LocalApiPlugin;
import localapi.api.OptionSpec;
import localapi.api.Options;
import localapi.api.PluginContext;
import localapi.api.StatusLine;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Live captions for Simple Voice Chat. No route, no mixin, no entrypoint of its own:
 * audio comes in-process from the voice plugin over {@link AudioBus}, the HUD is a Fabric HudElement,
 * the settings live on this plugin's page.
 */
public final class CaptionsPlugin implements LocalApiPlugin {
    private PluginContext ctx;
    private Options options;
    private final CaptionsHud hud = new CaptionsHud();
    private Transcriber transcriber;
    private volatile String hudError = "";

    private List<StatusLine> cachedStatus = List.of();
    private long cachedAt;

    @Override
    public void register(PluginContext ctx) {
        this.ctx = ctx;
        this.options = ctx.options(List.of(
                OptionSpec.choice("hudMode", "HUD mode", List.of("RIGHT_WINDOW", "TOP_LEFT_ICONS", "OFF"), "RIGHT_WINDOW"),
                OptionSpec.integer("windowWidth", "Window width", 80, 1000, 220),
                OptionSpec.integer("maxEntries", "Max lines", 1, 20, 5),
                OptionSpec.number("fadeTimeSeconds", "Fade time (s)", 0.5, 60, 5.0),
                OptionSpec.text("modelPath", "Model file", "config/voicetranscript/models/ggml-medium-q5_0.bin"),
                OptionSpec.choice("language", "Language", List.of("auto", "de", "en"), "auto"),
                OptionSpec.integer("silenceMs", "Silence ends line (ms)", 100, 3000, 380),
                OptionSpec.number("rmsGate", "Loudness gate (RMS)", 0.0, 1.0, 0.02),
                OptionSpec.integer("cpuThreads", "CPU threads", 1, 32, 4)
        ), this::changed);

        transcriber = new Transcriber(ctx.logger(), this::modelFile, () -> options.integer("cpuThreads"),
                () -> options.string("language"), () -> options.integer("silenceMs"), () -> options.number("rmsGate"),
                hud::add);

        try {
            HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                    Identifier.fromNamespaceAndPath("localapi", "captions_hud"), hud);
        } catch (Throwable t) {
            hudError = "cannot attach the HUD element: " + t;
            ctx.logger().warn(hudError);
        }

        ctx.status(this::status);
        applyHud();
        transcriber.setActive(mode() != CaptionsHud.Mode.OFF);
    }

    private CaptionsHud.Mode mode() {
        try { return CaptionsHud.Mode.valueOf(options.string("hudMode")); } catch (IllegalArgumentException e) { return CaptionsHud.Mode.RIGHT_WINDOW; }
    }

    private void applyHud() {
        hud.setMode(mode());
        hud.setWindowWidth(options.integer("windowWidth"));
        hud.setMaxEntries(options.integer("maxEntries"));
        hud.setFadeTimeSeconds((float) options.number("fadeTimeSeconds"));
    }

    /** Render thread, after a valid value was saved. Takes effect now; only the model reloads in the worker. */
    private void changed(String id) {
        switch (id) {
            case "hudMode" -> {
                applyHud();
                boolean on = mode() != CaptionsHud.Mode.OFF;
                if (!on) hud.clear();
                transcriber.setActive(on);
            }
            case "windowWidth", "maxEntries", "fadeTimeSeconds" -> applyHud();
            case "modelPath" -> transcriber.reloadModel();
            default -> { /* language, silenceMs, rmsGate, cpuThreads are read at use */ }
        }
    }

    private Path modelFile() {
        Path p = Path.of(options.string("modelPath"));
        return p.isAbsolute() ? p : FabricLoader.getInstance().getGameDir().resolve(p);
    }

    // ------------------------------------------------------------------ status page

    private synchronized List<StatusLine> status() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < 250) return cachedStatus;
        cachedAt = now;
        List<StatusLine> l = new ArrayList<>();
        CaptionsHud.Mode m = mode();
        if (!hudError.isEmpty()) l.add(StatusLine.error(hudError));
        if (m == CaptionsHud.Mode.OFF) {
            l.add(StatusLine.warn("Display is OFF: the worker is stopped and nothing is transcribed."));
        } else {
            if (AudioBus.sources().isEmpty()) {
                l.add(StatusLine.error(ctx.pluginLoaded("voice")
                        ? "Voice plugin is loaded, but Voice Chat has not registered its listeners yet. Restart the client."
                        : "No audio source: the voice plugin is not loaded. Put it in the plugins folder, approve it, restart."));
            } else {
                long last = AudioBus.lastFrameMs();
                l.add(StatusLine.ok("Audio source: " + String.join(", ", AudioBus.sources())
                        + (last == 0 ? "  (no audio received yet)" : "  (last audio " + (now - last) / 1000 + " s ago)")));
            }
            Transcriber t = transcriber;
            StatusLine.Level lv = switch (t.modelState) {
                case READY -> StatusLine.Level.OK;
                case MISSING, ERROR -> StatusLine.Level.ERROR;
                default -> StatusLine.Level.INFO;
            };
            l.add(new StatusLine("Model: " + t.modelMessage, lv));
            l.add(StatusLine.info("Lines: " + t.transcribed.get() + " shown, " + t.discardedNoise.get() + " noise, "
                    + t.discardedFiltered.get() + " blank/hallucination, " + t.failed.get() + " failed"
                    + (t.lastInferenceMs > 0 ? ", last run " + t.lastInferenceMs + " ms" : "")
                    + (t.queued() > 50 ? ", queue " + t.queued() : "")));
        }
        cachedStatus = l;
        return l;
    }
}
