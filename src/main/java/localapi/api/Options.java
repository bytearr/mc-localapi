package localapi.api;

/** Live view of a plugin's saved settings. Safe to read from any thread; always the current value. */
public interface Options {
    String string(String id);

    int integer(String id);

    double number(String id);
}
