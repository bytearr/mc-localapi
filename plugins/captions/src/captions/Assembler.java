package captions;

import localapi.api.AudioBus;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/**
 * Per speaker: collects loud frames into one utterance. A pause of silenceMs ends it.
 * Quiet frames (RMS below the gate) and empty frames are dropped, they only count as silence.
 * Time is the frame's own receive time, so a busy worker (Whisper running) does not cut utterances short.
 * Used from the worker thread only.
 */
final class Assembler {
    interface Sink {
        void utterance(UUID speaker, String kind, short[] pcm48);
    }

    static final int MAX_SAMPLES = 48000 * 25; // hard cap per utterance: 25 s

    private static final UUID NIL = new UUID(0, 0);

    private static final class State {
        short[] buf = new short[48000];
        int len;
        long lastLoudMs;
        String kind = "";
    }

    private final Map<UUID, State> speakers = new HashMap<>();
    private final Sink sink;
    private final IntSupplier silenceMs;
    private final DoubleSupplier rmsGate;

    Assembler(Sink sink, IntSupplier silenceMs, DoubleSupplier rmsGate) {
        this.sink = sink;
        this.silenceMs = silenceMs;
        this.rmsGate = rmsGate;
    }

    void frame(AudioBus.Frame f) {
        if (f.pcm().length == 0) return; // Voice Chat's end-of-speech marker, nothing to hear
        UUID id = f.speaker() == null ? NIL : f.speaker();
        State st = speakers.get(id);
        if (st != null && st.len > 0 && f.timeMs() - st.lastLoudMs >= silenceMs.getAsInt()) flush(id, st);
        if (Audio.rms(f.pcm()) < rmsGate.getAsDouble()) return;
        if (st == null) {
            st = new State();
            speakers.put(id, st);
        }
        if (st.len + f.pcm().length > MAX_SAMPLES) flush(id, st);
        if (st.len + f.pcm().length > st.buf.length) st.buf = java.util.Arrays.copyOf(st.buf, Math.max(st.buf.length * 2, st.len + f.pcm().length));
        System.arraycopy(f.pcm(), 0, st.buf, st.len, f.pcm().length);
        st.len += f.pcm().length;
        st.lastLoudMs = f.timeMs();
        st.kind = f.kind();
    }

    /** @param nowMs reference time: wall clock when the frame queue is empty, else the newest processed frame time */
    void tick(long nowMs) {
        int silence = silenceMs.getAsInt();
        for (Map.Entry<UUID, State> e : speakers.entrySet()) {
            State st = e.getValue();
            if (st.len > 0 && nowMs - st.lastLoudMs >= silence) flush(e.getKey(), st);
        }
    }

    void clear() {
        speakers.clear();
    }

    private void flush(UUID id, State st) {
        short[] out = java.util.Arrays.copyOf(st.buf, st.len);
        st.len = 0;
        if (st.buf.length > 48000 * 8) st.buf = new short[48000];
        sink.utterance(id == NIL ? null : id, st.kind, out);
    }
}
