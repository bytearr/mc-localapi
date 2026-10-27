package localapi.api;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process bus for decoded voice audio. A plugin that hears audio (the voice plugin) publishes,
 * other plugins (captions) subscribe. Plugin class loaders do not see each other, but both see this class.
 * Nothing leaves the process here; the NDJSON stream is a separate path for outside programs.
 */
public final class AudioBus {
    private AudioBus() {}

    /**
     * @param kind      entity | locational | static | microphone
     * @param speaker   UUID of the speaking player (own mic: the local player). May be null if unknown.
     * @param timeMs    System.currentTimeMillis() when the frame was received
     * @param pcm       signed 16 bit, 48000 Hz, mono, normally 960 samples; EMPTY for Voice Chat's "end of speech" packet.
     *                  Private copy: listeners may keep it but must not modify it.
     */
    public record Frame(String kind, UUID speaker, long timeMs, short[] pcm, boolean whispering) {}

    public interface Listener {
        /** Runs on Voice Chat's thread. Hand the frame to your own queue and return. */
        void onFrame(Frame f);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Set<String> SOURCES = ConcurrentHashMap.newKeySet();
    private static volatile long lastFrameMs;

    /** @return a handle; close() unsubscribes */
    public static AutoCloseable subscribe(Listener l) {
        LISTENERS.add(l);
        return () -> LISTENERS.remove(l);
    }

    public static boolean hasListeners() { return !LISTENERS.isEmpty(); }

    public static void publish(Frame f) {
        lastFrameMs = f.timeMs();
        for (Listener l : LISTENERS) {
            try { l.onFrame(f); } catch (Throwable ignored) { /* a broken listener must not break Voice Chat */ }
        }
    }

    /** A publisher calls this once its Voice Chat listeners are registered. */
    public static void addSource(String name) { SOURCES.add(name); }

    public static Set<String> sources() { return Collections.unmodifiableSet(SOURCES); }

    /** 0 when nothing was published yet. */
    public static long lastFrameMs() { return lastFrameMs; }
}
