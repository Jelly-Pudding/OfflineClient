package com.jellypudding.offlineclient.hud;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

// The rows an element shows worked out again at most every fifty milliseconds. An element is
// asked for its rows several times a frame and some rows walk every statistic or every player.
public final class RowCache<T> {

    private static final long KEEP_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final Supplier<List<T>> build;

    // Null until first built.
    private List<T> rows;
    private long builtAt;

    public RowCache(Supplier<List<T>> build) {
        this.build = build;
    }

    public List<T> get() {
        long now = System.nanoTime();
        if (rows == null || now - builtAt >= KEEP_NANOS) {
            rows = build.get();
            builtAt = now;
        }
        return rows;
    }
}
