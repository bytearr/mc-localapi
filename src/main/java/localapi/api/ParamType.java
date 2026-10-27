package localapi.api;

/** Parameter types a function can declare. The core checks them before the handler runs. */
public enum ParamType {
    STRING("string"), INTEGER("integer"), NUMBER("number"), BOOLEAN("boolean"), JSON("json (object or array)");

    public final String label;

    ParamType(String label) { this.label = label; }
}
