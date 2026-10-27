package localapi.api;

import com.google.gson.JsonElement;

/** One-shot function: returns a JSON value, which becomes {"ok":true,"data":...}. */
@FunctionalInterface
public interface OnceHandler {
    JsonElement handle(Call call) throws Exception;
}
