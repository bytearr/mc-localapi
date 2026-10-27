package captions;

/** Sample-level helpers. Pure functions, no Minecraft, no Whisper. */
final class Audio {
    static final int PAD_SAMPLES_16K = 1600; // 100 ms per side
    /**
     * whisper.cpp returns nothing for input shorter than 1000 ms ("input is too short"), so "Ja." or "Nein."
     * would silently vanish. Only for snippets that end up shorter than this, zeros are appended up to the minimum.
     */
    static final int MIN_SAMPLES_16K = 16_320;
    static final float TARGET_PEAK = 0.9f;
    static final float MAX_GAIN = 4f;

    private Audio() {}

    /** Root mean square of a 48 kHz s16 frame, scaled to 0..1. */
    static float rms(short[] pcm) {
        if (pcm.length == 0) return 0f;
        double sum = 0;
        for (short s : pcm) {
            double v = s / 32768.0;
            sum += v * v;
        }
        return (float) Math.sqrt(sum / pcm.length);
    }

    /** 48 kHz s16 to 16 kHz float, plain average of three samples (boxcar). */
    static float[] to16k(short[] pcm48) {
        int n = pcm48.length / 3;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int j = i * 3;
            out[i] = (pcm48[j] + pcm48[j + 1] + pcm48[j + 2]) / (3f * 32768f);
        }
        return out;
    }

    static float peak(float[] x) {
        float p = 0f;
        for (float v : x) p = Math.max(p, Math.abs(v));
        return p;
    }

    /**
     * Brings the peak to 0.9 with at most 4x gain and adds 100 ms of zeros on each side (see MIN_SAMPLES_16K).
     * Returns null when the snippet cannot reach the target peak with that gain: that is background noise,
     * and scaling it up only gives Whisper something to hallucinate on.
     */
    static float[] prepare(float[] x16) {
        float p = peak(x16);
        if (p <= 0f) return null;
        float gain = Math.min(MAX_GAIN, TARGET_PEAK / p);
        if (p * gain < TARGET_PEAK - 1e-3f) return null;
        float[] out = new float[Math.max(MIN_SAMPLES_16K, x16.length + 2 * PAD_SAMPLES_16K)];
        for (int i = 0; i < x16.length; i++) out[PAD_SAMPLES_16K + i] = x16[i] * gain;
        return out;
    }
}
