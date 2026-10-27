package localapi.api;

import java.util.List;

/**
 * One setting a plugin shows on its page in Mod Menu > Local API > Plugins.
 * Values are saved by the core in config/localapi/plugins/&lt;id&gt;.json under "options".
 */
public record OptionSpec(String id, String label, Kind kind, List<String> choices, double min, double max, String defaultValue) {
    public enum Kind { CHOICE, INTEGER, NUMBER, TEXT }

    public static OptionSpec choice(String id, String label, List<String> values, String def) {
        return new OptionSpec(id, label, Kind.CHOICE, List.copyOf(values), 0, 0, def);
    }

    public static OptionSpec integer(String id, String label, int min, int max, int def) {
        return new OptionSpec(id, label, Kind.INTEGER, List.of(), min, max, Integer.toString(def));
    }

    public static OptionSpec number(String id, String label, double min, double max, double def) {
        return new OptionSpec(id, label, Kind.NUMBER, List.of(), min, max, Double.toString(def));
    }

    public static OptionSpec text(String id, String label, String def) {
        return new OptionSpec(id, label, Kind.TEXT, List.of(), 0, 0, def);
    }
}
