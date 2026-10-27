package localapi;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Hand-off between HTTP threads and the game thread.
 * HTTP threads enqueue and wait on a future; the game thread drains the queue at the
 * start of every rendered frame with a time budget, so it never blocks on I/O.
 */
public final class GameQueue {
    private static final ConcurrentLinkedQueue<Runnable> QUEUE = new ConcurrentLinkedQueue<>();
    static volatile long budgetNanos = 4_000_000L;
    static volatile long lastDrainMillis = 0;

    private GameQueue() {}

    @SuppressWarnings("unchecked")
    public static <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        QUEUE.add(() -> {
            if (f.isDone()) return; // caller timed out and cancelled: do not run stale actions
            try {
                T r = task.call();
                if (r instanceof CompletionStage<?> cs) {
                    ((CompletionStage<T>) cs).whenComplete((v, ex) -> {
                        if (ex != null) f.completeExceptionally(ex); else f.complete(v);
                    });
                } else {
                    f.complete(r);
                }
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    /** Called on the game thread, once per frame. */
    public static void drain() {
        lastDrainMillis = System.currentTimeMillis();
        long end = System.nanoTime() + budgetNanos;
        Runnable r;
        while ((r = QUEUE.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                LocalApiMod.LOG.error("[localapi] task crashed", t);
            }
            if (System.nanoTime() > end) break; // rest waits for the next frame
        }
    }

    public static int pending() { return QUEUE.size(); }
}
