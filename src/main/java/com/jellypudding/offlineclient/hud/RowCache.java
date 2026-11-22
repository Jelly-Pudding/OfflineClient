package com.jellypudding.offlineclient.hud;

import net.minecraft.SharedConstants;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

// An element is asked for its rows several times a frame and some rows walk every statistic or
// every player.
public final class RowCache<T> {

    private final Supplier<List<T>> build;
    private final long keepNanos;

    private List<T> rows;
    private long builtAt;

    public RowCache(Supplier<List<T>> build) {
        this(build, SharedConstants.MILLIS_PER_TICK);
    }

    public RowCache(Supplier<List<T>> build, long keepMillis) {
        this.build = build;
        keepNanos = TimeUnit.MILLISECONDS.toNanos(keepMillis);
    }

    public List<T> get() {
        long now = System.nanoTime();
        if (rows == null || now - builtAt >= keepNanos) {
            rows = build.get();
            builtAt = now;
        }
        return rows;
    }
}
