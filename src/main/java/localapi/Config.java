package localapi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** config/localapi.json. Created with defaults on first start. */
public final class Config {
    public int port = 25580;
    /** Plain shared password. Default is for testing only. */
    public String password = "Test123";
    /** How long an HTTP request waits for the game thread before it gets 504. */
    public int timeoutMs = 5000;
    /** Max game-thread time spent on API work per rendered frame. */
    public int frameBudgetMs = 4;
    /** Worker threads for HTTP I/O (never the game thread). */
    public int httpThreads = 4;
    /** Max simultaneously open core/plugin streams (each has its own writer thread). */
    public int maxStreams = 8;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Writes the current values back to config/localapi.json. Returns an error text or null. */
    public synchronized String save() {
        try {
            Files.writeString(FabricLoader.getInstance().getConfigDir().resolve("localapi.json"), GSON.toJson(this));
            return null;
        } catch (Exception e) {
            return "cannot write config/localapi.json: " + e.getMessage();
        }
    }

    public static Config load() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve("localapi.json");
        Config c = new Config();
        try {
            if (Files.exists(p)) {
                Config read = GSON.fromJson(Files.readString(p), Config.class);
                if (read != null) c = read;
            }
            Files.writeString(p, GSON.toJson(c)); // write back so new fields appear
        } catch (Exception e) {
            LocalApiMod.LOG.error("[localapi] config problem, using defaults", e);
        }
        if (c.port < 1024 || c.port > 65535) c.port = 25580;
        if (c.password == null || c.password.isEmpty()) c.password = "Test123";
        if (c.timeoutMs < 100) c.timeoutMs = 5000;
        if (c.frameBudgetMs < 1) c.frameBudgetMs = 4;
        if (c.httpThreads < 1) c.httpThreads = 4;
        if (c.maxStreams < 1) c.maxStreams = 8;
        return c;
    }
}


