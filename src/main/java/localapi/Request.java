package localapi;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/** Parsed request. Parameters are read from the JSON body first, then the query string. */
public record Request(String method, String path, Map<String, String> query, JsonObject body) {

    public boolean has(String k) {
        return (body.has(k) && !body.get(k).isJsonNull()) || query.containsKey(k);
    }

    public String str(String k, String def) {
        JsonElement e = body.get(k);
        if (e != null && !e.isJsonNull()) return e.getAsString();
        String q = query.get(k);
        return q != null ? q : def;
    }

    public String reqStr(String k) {
        String s = str(k, null);
        if (s == null) throw new HttpError(400, "missing parameter: " + k);
        return s;
    }

    public double dbl(String k, double def) {
        String s = str(k, null);
        if (s == null) return def;
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { throw new HttpError(400, "parameter " + k + " is not a number"); }
    }

    public double reqDbl(String k) {
        if (!has(k)) throw new HttpError(400, "missing parameter: " + k);
        return dbl(k, 0);
    }

    public int integer(String k, int def) {
        String s = str(k, null);
        if (s == null) return def;
        try { return (int) Math.floor(Double.parseDouble(s)); }
        catch (NumberFormatException e) { throw new HttpError(400, "parameter " + k + " is not a number"); }
    }

    public int reqInt(String k) {
        if (!has(k)) throw new HttpError(400, "missing parameter: " + k);
        return integer(k, 0);
    }

    public boolean bool(String k, boolean def) {
        String s = str(k, null);
        if (s == null) return def;
        return s.equals("1") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes");
    }
}
