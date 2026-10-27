package localapi.api;

/** One line of status text on the plugin page. */
public record StatusLine(String text, Level level) {
    public enum Level { INFO, OK, WARN, ERROR }

    public static StatusLine info(String t) { return new StatusLine(t, Level.INFO); }
    public static StatusLine ok(String t) { return new StatusLine(t, Level.OK); }
    public static StatusLine warn(String t) { return new StatusLine(t, Level.WARN); }
    public static StatusLine error(String t) { return new StatusLine(t, Level.ERROR); }
}
