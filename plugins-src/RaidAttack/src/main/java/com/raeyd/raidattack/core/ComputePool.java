package com.raeyd.raidattack.core;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared worker pool for OFF-MAIN-THREAD pure computation (currently raider A* replans).
 *
 * <p><b>Hard rule for anything submitted here:</b> pure computation over IMMUTABLE snapshots only —
 * no Bukkit / NMS / Citizens calls, no live world / entity / inventory access. The pattern is:
 * snapshot the inputs on the main thread, compute here, apply the result back on the main thread.
 *
 * <p>Sized to roughly half the cores (capped at 6) so the server's single main tick keeps a hot
 * core to itself — the OS scheduler tends to keep the busy main thread on a fast P-core while these
 * lower-priority daemon workers chew path math on the rest. Threads idle out after 30 s.
 */
public final class ComputePool {

    private final ThreadPoolExecutor exec;
    private final int threads;

    public ComputePool() {
        int cores = Runtime.getRuntime().availableProcessors();
        this.threads = Math.max(2, Math.min(6, cores / 2));
        AtomicInteger n = new AtomicInteger();
        this.exec = new ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "RaidAttack-compute-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        this.exec.allowCoreThreadTimeOut(true);
    }

    /** Submit a pure-computation job. NEVER touch live Bukkit state inside it. */
    public <T> Future<T> submit(Callable<T> job) {
        return exec.submit(job);
    }

    public int threadCount() { return threads; }

    public void shutdown() {
        exec.shutdownNow();
    }
}
