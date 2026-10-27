package captions;

import io.github.givimad.whisperjni.*;
import java.nio.file.Path;

public final class Bench {
    public static void main(String[] a) throws Exception {
        WhisperJNI.loadLibrary();
        WhisperJNI w = new WhisperJNI();
        WhisperContextParams cp = new WhisperContextParams();
        cp.useGPU = false;
        WhisperContext ctx = w.init(Path.of(a[0]), cp);
        short[] s = SelfTest.to48k(SelfTest.readWav16k(Path.of(a[1])));
        float[] x = Audio.prepare(Audio.to16k(s));
        System.out.println("audio " + x.length / 16000.0 + " s");
        int f = Math.clamp(x.length / 320, 64, 1500);
        int[][] variants = {{f, 1, 4}, {0, 1, 4}, {f, 5, 4}, {f + 100, 1, 4}};
        for (int[] v : variants) {
            for (int run = 0; run < 1; run++) {
                WhisperFullParams p = new WhisperFullParams(v[1] == 1 ? WhisperSamplingStrategy.GREEDY : WhisperSamplingStrategy.BEAN_SEARCH);
                p.beamSearchBeamSize = v[1]; p.audioCtx = v[0]; p.nThreads = v[2]; p.temperature = 0; p.temperatureInc = 0;
                p.noContext = true; p.language = "auto"; p.printProgress = false; p.printRealtime = false; p.printTimestamps = false;
                long t0 = System.nanoTime();
                w.full(ctx, p, x, x.length);
                System.out.println("audioCtx=" + v[0] + " beam=" + v[1] + " threads=" + v[2] + " run" + run + ": " + (System.nanoTime() - t0) / 1_000_000 + " ms -> " + w.fullNSegments(ctx) + " seg: " + w.fullGetSegmentText(ctx, 0));
            }
        }
        System.exit(0);
    }
}
