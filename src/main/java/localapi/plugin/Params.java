package localapi.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import localapi.HttpError;
import localapi.Request;
import localapi.api.Call;
import localapi.api.ParamType;

import java.util.HashMap;
import java.util.Map;

/** Checks the parameters of a call against the declaration. The handler only runs if this passes. */
public final class Params {
    private Params() {}

    /** From text (query string or a declared default). null = not a valid value of that type. */
    static Object convert(ParamType t, String s) {
        try {
            switch (t) {
                case STRING: return s;
                case INTEGER: return Long.parseLong(s.trim());
                case NUMBER: {
                    double d = Double.parseDouble(s.trim());
                    return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
                }
                case BOOLEAN: {
                    String l = s.trim().toLowerCase();
                    if (l.equals("true") || l.equals("1") || l.equals("yes")) return Boolean.TRUE;
                    if (l.equals("false") || l.equals("0") || l.equals("no")) return Boolean.FALSE;
                    return null;
                }
                case JSON: {
                    JsonElement e = JsonParser.parseString(s);
                    return e.isJsonObject() || e.isJsonArray() ? e : null;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** From a JSON body: real JSON types are required (a string "5" is not an integer). */
    static Object fromJson(ParamType t, JsonElement e) {
        try {
            switch (t) {
                case STRING:
                    return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
                case INTEGER: {
                    if (!(e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber())) return null;
                    double d = e.getAsDouble();
                    return d == Math.rint(d) && !Double.isInfinite(d) ? (Object) e.getAsLong() : null;
                }
                case NUMBER:
                    return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? (Object) e.getAsDouble() : null;
                case BOOLEAN:
                    return e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? (Object) e.getAsBoolean() : null;
                case JSON:
                    return e.isJsonObject() || e.isJsonArray() ? e : null;
            }
        } catch (Exception ex) {
            return null;
        }
        return null;
    }

    /** @throws HttpError 400 naming the parameter and the expected type */
    public static Call bind(PluginFunction fn, Request req) {
        Map<String, Object> values = new HashMap<>();
        for (PluginFunction.Param p : fn.params) {
            Object v = null;
            JsonElement be = req.body().get(p.name());
            if (be != null && !be.isJsonNull()) {
                v = fromJson(p.type(), be);
                if (v == null) throw wrongType(p, "got " + describe(be));
            } else if (req.query().containsKey(p.name())) {
                v = convert(p.type(), req.query().get(p.name()));
                if (v == null) throw wrongType(p, "got '" + req.query().get(p.name()) + "'");
            } else if (p.defaultValue() != null) {
                v = convert(p.type(), p.defaultValue());
            } else if (p.required()) {
                throw new HttpError(400, "parameter '" + p.name() + "' is missing, expected " + p.type().label);
            }
            if (v != null) values.put(p.name(), v);
        }
        return new CallImpl(values);
    }

    private static HttpError wrongType(PluginFunction.Param p, String got) {
        return new HttpError(400, "parameter '" + p.name() + "' must be " + p.type().label + ", " + got);
    }

    private static String describe(JsonElement e) {
        if (e.isJsonObject()) return "an object";
        if (e.isJsonArray()) return "an array";
        JsonPrimitive p = e.getAsJsonPrimitive();
        return p.isString() ? "a string" : p.isBoolean() ? "a boolean" : "a number";
    }

    private record CallImpl(Map<String, Object> v) implements Call {
        private Object need(String n) {
            Object o = v.get(n);
            if (o == null) throw new IllegalStateException("parameter '" + n + "' is not set (undeclared, or optional without default)");
            return o;
        }

        @Override public boolean has(String n) { return v.containsKey(n); }
        @Override public String string(String n) { return (String) need(n); }
        @Override public long integer(String n) { return (Long) need(n); }
        @Override public double number(String n) { Object o = need(n); return o instanceof Long l ? l : (Double) o; }
        @Override public boolean bool(String n) { return (Boolean) need(n); }
        @Override public JsonElement json(String n) { return (JsonElement) need(n); }
        @Override public String string(String n, String d) { return v.containsKey(n) ? (String) v.get(n) : d; }
        @Override public long integer(String n, long d) { return v.containsKey(n) ? (Long) v.get(n) : d; }
        @Override public double number(String n, double d) { return v.containsKey(n) ? number(n) : d; }
        @Override public boolean bool(String n, boolean d) { return v.containsKey(n) ? (Boolean) v.get(n) : d; }
    }
}
