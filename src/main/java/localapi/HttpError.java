package localapi;

import com.google.gson.JsonObject;

public final class HttpError extends RuntimeException {
    public final int status;
    /** Extra fields merged into the error JSON (e.g. the list of real paths on 404). May be null. */
    public final JsonObject extra;

    public HttpError(int status, String message) {
        this(status, message, null);
    }

    public HttpError(int status, String message, JsonObject extra) {
        super(message, null, false, false);
        this.status = status;
        this.extra = extra;
    }
}
