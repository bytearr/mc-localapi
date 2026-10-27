package localapi.api;

import com.google.gson.JsonObject;

/**
 * One open connection. Each sent object becomes one line of JSON (NDJSON). Thread-safe and
 * non-blocking: if the reader is too slow the oldest queued frames are dropped (counted in the
 * heartbeat as "dropped"). The core itself sends {"type":"hello"} and {"type":"heartbeat"} lines;
 * give your own lines a "type" too.
 */
public interface StreamSink {
    /** @return false when the connection is already closed (stop sending). */
    boolean send(JsonObject line);

    boolean isOpen();

    /** Close from the plugin side. Safe to call twice. */
    void close();

    /** Runs once when the connection ends, for any reason (client left, plugin closed, shutdown). */
    void onClose(Runnable r);
}
