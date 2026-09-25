package com.jellypudding.offlineclient.util;

import java.io.Serial;
import java.util.LinkedHashMap;
import java.util.Map;

// Drops its eldest entry once it holds more than its limit. For maps fed by packets
// that may never say an entry is gone.
public final class BoundedMap<K, V> extends LinkedHashMap<K, V> {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int max;

    public BoundedMap(int max) {
        this(max, false);
    }

    // With access order the eldest entry is the one least recently read or written.
    public BoundedMap(int max, boolean accessOrder) {
        super(16, 0.75f, accessOrder);
        this.max = max;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > max;
    }
}
