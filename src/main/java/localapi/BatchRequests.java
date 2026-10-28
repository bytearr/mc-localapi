package localapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;

/** Batch validation and execution, independent of Minecraft. */
final class BatchRequests {
    @FunctionalInterface
    interface Handler { Object handle(Request request) throws Exception; }

    private BatchRequests() {}

    /** Several calls in ONE game-thread step: body {"requests":[{"path":"/key/down","body":{...}}, ...]}. */
    static Object dispatch(Request r, Handler handler) throws Exception {
        JsonElement el = r.body().get("requests");
        if (el == null || !el.isJsonArray()) throw new HttpError(400, "requests must be an array");
        if (el.getAsJsonArray().size() > 100) throw new HttpError(400, "max 100 requests");
        JsonArray out = new JsonArray();
        for (JsonElement e : el.getAsJsonArray()) {
            JsonObject res = new JsonObject();
            try {
                if (!e.isJsonObject()) throw new HttpError(400, "batch item must be an object");
                JsonObject item = e.getAsJsonObject();
                JsonElement pathValue = item.get("path");
                if (pathValue == null || !pathValue.isJsonPrimitive() || !pathValue.getAsJsonPrimitive().isString())
                    throw new HttpError(400, "batch item path must be a string");
                String path = pathValue.getAsString();
                String normalized = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                JsonObject body = new JsonObject();
                if (item.has("body")) {
                    if (!item.get("body").isJsonObject()) throw new HttpError(400, "batch item body must be an object");
                    body = item.getAsJsonObject("body");
                }
                if (normalized.equals("/batch") || normalized.equals("/screenshot") || normalized.equals("/item/icon"))
                    throw new HttpError(400, path + " cannot be used inside /batch");
                Object v = handler.handle(new Request("POST", path, Map.of(), body));
                res.addProperty("ok", true);
                res.add("data", (JsonElement) v);
            } catch (HttpError he) {
                res.addProperty("ok", false);
                res.addProperty("error", he.getMessage());
            }
            out.add(res);
        }
        return out;
    }
}
