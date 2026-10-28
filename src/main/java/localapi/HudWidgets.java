package localapi;

import com.google.gson.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Ephemeral script-owned presentation data. All access is on the client thread. */
final class HudWidgets {
    static final HudWidgets INSTANCE = new HudWidgets(System::nanoTime);
    static final int MAX_OWNERS = 16, MAX_PER_OWNER = 16, MAX_WIDGETS = 64;
    private final LongSupplier clock;
    private final Map<String, Owner> owners = new LinkedHashMap<>();
    private static final class Owner {
        long expires;
        final Map<String, Widget> widgets = new LinkedHashMap<>();
        Owner(long expires) { this.expires = expires; }
    }
    record Widget(String id, String type, String label, double value, double max, boolean running,
                  int x, int y, int width, long updated, long expires) {
        double valueAt(long now) { return type.equals("timer") && running ? Math.max(0, value - (now - updated) / 1e9) : value; }
        String text(long now) {
            double v = valueAt(now);
            String number = type.equals("timer") ? String.format(Locale.ROOT, "%d:%02d", (long)Math.ceil(v) / 60, (long)Math.ceil(v) % 60)
                    : java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
            return label + ": " + number + (type.equals("progress") ? " / " + java.math.BigDecimal.valueOf(max).stripTrailingZeros().toPlainString() : "");
        }
    }
    record Box(int x, int y, int width, int height) {}
    static Box layout(Widget w, int screenWidth, int screenHeight) {
        int width = Math.min(w.width, Math.max(0, screenWidth));
        int height = Math.min(w.type.equals("progress") ? 25 : 18, Math.max(0, screenHeight));
        return new Box(Math.max(0, Math.min(w.x, screenWidth - width)), Math.max(0, Math.min(w.y, screenHeight - height)), width, height);
    }
    HudWidgets(LongSupplier clock) { this.clock = clock; }
    void clear() { owners.clear(); }
    void prune() {
        long now = clock.getAsLong();
        owners.values().removeIf(o -> now - o.expires >= 0);
        owners.values().forEach(o -> o.widgets.values().removeIf(w -> now - w.expires >= 0));
    }
    List<Widget> visible() { prune(); return owners.values().stream().flatMap(o -> o.widgets.values().stream()).toList(); }
    private Owner owner(Request r) {
        prune(); Owner o = owners.get(r.reqStr("owner"));
        if (o == null) throw new HttpError(404, "owner lease expired or unknown; create a new lease");
        return o;
    }
    JsonObject create(Request r) {
        int ttl = integer(r, "ttl_ms", 10000, 1000, 30000);
        prune(); if (owners.size() >= MAX_OWNERS) throw new HttpError(429, "HUD owner limit reached");
        String token = UUID.randomUUID().toString();
        owners.put(token, new Owner(clock.getAsLong() + ttl * 1_000_000L));
        JsonObject out = new JsonObject(); out.addProperty("owner", token); out.addProperty("ttl_ms", ttl); return out;
    }
    JsonObject renew(Request r) {
        int ttl = integer(r, "ttl_ms", 10000, 1000, 30000);
        owner(r).expires = clock.getAsLong() + ttl * 1_000_000L;
        JsonObject out = new JsonObject(); out.addProperty("ttl_ms", ttl); return out;
    }
    JsonObject release(Request r) { owner(r); owners.remove(r.reqStr("owner")); return ok(); }
    JsonObject remove(Request r) { Owner o = owner(r); boolean removed = o.widgets.remove(r.reqStr("id")) != null;
        JsonObject out = new JsonObject(); out.addProperty("removed", removed); return out; }
    JsonObject put(Request r) {
        Owner o = owner(r);
        String id = string(r, "id", 64), type = string(r, "type", 16), label = string(r, "label", 128);
        if (!Set.of("timer", "counter", "progress").contains(type)) throw new HttpError(400, "type must be timer, counter or progress");
        double value = r.reqDbl("value"), max = r.dbl("max", 100);
        if (Math.abs(value) > 1e9 || (type.equals("timer") && value < 0)) throw new HttpError(400, "value out of range");
        if (type.equals("counter") && value != Math.rint(value)) throw new HttpError(400, "counter value must be an integer");
        if (type.equals("progress") && (max <= 0 || max > 1e9 || value < 0 || value > max)) throw new HttpError(400, "progress requires 0 <= value <= max, max > 0");
        int x = integer(r, "x", 8, 0, 10000), y = integer(r, "y", 8, 0, 10000);
        int width = integer(r, "width", 180, 40, 400), ttl = integer(r, "ttl_ms", 10000, 100, 60000);
        if (!o.widgets.containsKey(id) && (o.widgets.size() >= MAX_PER_OWNER || owners.values().stream().mapToInt(owner -> owner.widgets.size()).sum() >= MAX_WIDGETS))
            throw new HttpError(429, "HUD widget limit reached");
        long now = clock.getAsLong();
        o.widgets.put(id, new Widget(id, type, label, value, max, r.bool("running", false), x, y, width, now, now + ttl * 1_000_000L));
        return ok();
    }
    JsonObject list(Request r) {
        Owner o = owner(r); long now = clock.getAsLong(); JsonObject out = new JsonObject(); JsonArray widgets = new JsonArray();
        out.addProperty("lease_remaining_ms", Math.max(0, (o.expires - now) / 1_000_000));
        for (Widget w : o.widgets.values()) {
            JsonObject v = new JsonObject(); v.addProperty("id", w.id); v.addProperty("type", w.type); v.addProperty("label", w.label);
            v.addProperty("value", w.valueAt(now)); v.addProperty("max", w.max); v.addProperty("running", w.running);
            v.addProperty("x", w.x); v.addProperty("y", w.y); v.addProperty("width", w.width);
            v.addProperty("remaining_ms", Math.max(0, (Math.min(o.expires, w.expires) - now) / 1_000_000)); widgets.add(v);
        }
        out.add("widgets", widgets); return out;
    }
    private static JsonObject ok() { JsonObject out = new JsonObject(); out.addProperty("ok", true); return out; }
    private static int integer(Request r, String name, int fallback, int min, int max) {
        double v = r.dbl(name, fallback);
        if (v != Math.rint(v) || v < min || v > max) throw new HttpError(400, name + " must be an integer in " + min + ".." + max);
        return (int)v;
    }
    private static String string(Request r, String name, int limit) {
        String s = r.reqStr(name);
        if (s.isEmpty() || s.length() > limit || s.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x00a7 || (c >= 0xd800 && c <= 0xdfff)))
            throw new HttpError(400, name + " must be nonempty plain single-line text, max " + limit + " characters");
        return s;
    }
}
