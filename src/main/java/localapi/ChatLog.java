package localapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;

/** Ring buffer of received chat / system messages, so a script can poll with ?since=<id>. */
public final class ChatLog {
    private static final int MAX = 500;
    private static final ArrayDeque<JsonObject> LOG = new ArrayDeque<>();
    private static long nextId = 1;

    private ChatLog() {}

    public static synchronized void add(String kind, String sender, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("id", nextId++);
        o.addProperty("time", System.currentTimeMillis());
        o.addProperty("kind", kind);
        if (sender != null) o.addProperty("sender", sender);
        o.addProperty("text", text);
        LOG.addLast(o);
        while (LOG.size() > MAX) LOG.removeFirst();
    }

    public static synchronized JsonObject since(long since, int limit) {
        JsonArray arr = new JsonArray();
        for (JsonObject o : LOG) {
            if (o.get("id").getAsLong() > since) {
                arr.add(o);
                if (arr.size() >= limit) break;
            }
        }
        JsonObject r = new JsonObject();
        r.addProperty("last_id", nextId - 1);
        r.add("messages", arr);
        return r;
    }
}
