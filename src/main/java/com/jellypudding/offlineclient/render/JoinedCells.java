package com.jellypudding.offlineclient.render;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.List;
import java.util.function.ToLongFunction;

// The sides each cell of a list shares with another cell. The keys are only rebuilt
// when a different list comes in.
public final class JoinedCells<T> {

    private final ToLongFunction<T> key;
    private final LongOpenHashSet keys = new LongOpenHashSet();
    private List<T> known = List.of();

    public JoinedCells(ToLongFunction<T> key) {
        this.key = key;
    }

    // Before any cell of the list is asked about.
    public void update(List<T> cells) {
        if (cells == known) {
            return;
        }
        known = cells;
        keys.clear();
        for (T cell : cells) {
            keys.add(key.applyAsLong(cell));
        }
    }

    public int hiddenSides(T cell) {
        return DrawBatch.sharedSides(keys, key.applyAsLong(cell));
    }

    public void clear() {
        known = List.of();
        keys.clear();
    }
}
