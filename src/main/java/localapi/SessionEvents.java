package localapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import localapi.api.StreamSink;

import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owned by the game thread, except for thread-safe subscriber removal on close. */
final class SessionEvents {
    static final SessionEvents INSTANCE = new SessionEvents();
    private final Set<StreamSink> subscribers = ConcurrentHashMap.newKeySet();
    private final ArrayList<JsonObject> history = new ArrayList<>();
    private final TreeSet<String> dirty = new TreeSet<>();
    private String session;
    private boolean active;
    private long sequence;
    private String dimension;
    private boolean deathRecorded;

    void begin(String initialDimension) {
        if (active) end();
        session = UUID.randomUUID().toString();
        history.clear();
        dirty.clear();
        active = true;
        dimension = initialDimension;
        deathRecorded = false;
        record("session_started", new JsonObject());
    }

    void end() {
        if (!active) return;
        flush();
        record("session_ended", new JsonObject());
        active = false;
        dirty.clear();
    }

    void respawn(String nextDimension) {
        if (!active) return;
        if (dimension != null && nextDimension != null && !dimension.equals(nextDimension)) {
            JsonObject data = new JsonObject();
            data.addProperty("from", dimension);
            data.addProperty("to", nextDimension);
            record("dimension_changed", data);
        }
        dimension = nextDimension;
        deathRecorded = false;
    }

    void death(String dimension, double x, double y, double z, String cause) {
        if (!active || deathRecorded) return;
        deathRecorded = true;
        JsonObject data = new JsonObject();
        data.addProperty("cause", cause);
        data.addProperty("cause_source", cause == null ? null : "server_death_message");
        data.addProperty("dimension", dimension);
        data.add("position", Json.vec(x, y, z));
        record("death", data);
    }

    boolean active() { return active; }

    void subscribe(StreamSink sink) {
        JsonObject initial = envelope("resync");
        initial.addProperty("reason", "stream_opened");
        initial.addProperty("active", active);
        initial.addProperty("journal_last_id", history.size());
        sink.send(initial);
        subscribers.add(sink);
        sink.onClose(() -> subscribers.remove(sink));
    }

    void invalidate(String... paths) {
        if (active) java.util.Collections.addAll(dirty, paths);
    }

    void flush() {
        if (dirty.isEmpty()) return;
        JsonObject event = envelope("state_changed");
        JsonArray paths = new JsonArray();
        dirty.forEach(paths::add);
        event.add("paths", paths);
        event.addProperty("resync", true);
        dirty.clear();
        publish(event);
    }

    // Only the explicit historical producers may call this. Never pass a state response.
    void record(String kind, JsonObject data) {
        if (!active) return;
        JsonObject fact = new JsonObject();
        fact.addProperty("id", history.size() + 1);
        fact.addProperty("session", session);
        fact.addProperty("time", System.currentTimeMillis());
        fact.addProperty("kind", kind);
        fact.add("data", data.deepCopy());
        history.add(fact);
        JsonObject event = envelope("historical");
        event.add("event", fact.deepCopy());
        publish(event);
    }

    JsonObject journal(Request request) {
        int since = request.integer("since", 0);
        int limit = request.integer("limit", 200);
        if (since < 0 || limit < 1 || limit > 1000) throw new HttpError(400, "since >= 0; limit must be 1..1000");
        String expected = request.str("session", null);
        if (since > 0 && expected == null) throw new HttpError(400, "session is required with since > 0");
        if ((expected != null && !expected.equals(session)) || since > history.size())
            throw new HttpError(409, "journal session/cursor changed; fetch /journal from the beginning");
        JsonObject out = new JsonObject();
        out.addProperty("session", session);
        out.addProperty("active", active);
        out.addProperty("last_id", history.size());
        int end = (int) Math.min((long) history.size(), (long) since + limit);
        JsonArray events = new JsonArray();
        for (int i = since; i < end; i++) events.add(history.get(i).deepCopy());
        out.add("events", events);
        out.addProperty("next_since", end);
        out.addProperty("has_more", end < history.size());
        return out;
    }

    private JsonObject envelope(String type) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("session", session);
        // Every subscriber sees the same sequence for published events. Opening a stream
        // advertises the existing cursor without advancing other subscribers' sequences.
        event.addProperty("seq", sequence);
        return event;
    }

    private void publish(JsonObject event) {
        event.addProperty("seq", ++sequence);
        for (StreamSink sink : subscribers) {
            if (!sink.isOpen() || !sink.send(event.deepCopy())) subscribers.remove(sink);
        }
    }
}
