package localapi.api;

/**
 * Open stream. open() is called once per connection and must return quickly: keep the sink,
 * register your listener, return. Frames are pushed later with sink.send(...) from any thread.
 * Streams never run on the game thread. Throwing from open() answers the request with 500.
 */
@FunctionalInterface
public interface StreamHandler {
    void open(Call call, StreamSink sink) throws Exception;
}
