package localapi.api;

import com.google.gson.JsonElement;

/**
 * Validated parameters of one call. Required parameters are present and have the declared type
 * when the handler runs. Optional ones may be absent: check has(), or use the overloads with a default.
 */
public interface Call {
    boolean has(String name);

    String string(String name);
    long integer(String name);
    double number(String name);
    boolean bool(String name);
    JsonElement json(String name);

    String string(String name, String def);
    long integer(String name, long def);
    double number(String name, double def);
    boolean bool(String name, boolean def);
}
