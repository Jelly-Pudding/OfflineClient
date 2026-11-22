package com.jellypudding.offlineclient.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

// Things that happen now and then kept for readers that each look in once a tick. Every reader
// sees each one once. Written and read on the game thread.
public final class Feed<T> {

    // A reader that looks in less often than this many arrive misses the oldest.
    private static final int KEPT = 32;

    private final Deque<T> recent = new ArrayDeque<>();
    private int total;

    public void add(T item) {
        recent.addLast(item);
        if (recent.size() > KEPT) {
            recent.removeFirst();
        }
        total++;
    }

    // What arrived before the reader was made is no news to it.
    public Reader<T> reader() {
        return new Reader<>(this);
    }

    public static final class Reader<T> {

        private final Feed<T> feed;
        private int seen;

        private Reader(Feed<T> feed) {
            this.feed = feed;
            seen = feed.total;
        }

        // What arrived since the last call with the oldest first.
        public List<T> fresh() {
            int missed = feed.total - seen;
            seen = feed.total;
            if (missed == 0) {
                return List.of();
            }
            List<T> kept = List.copyOf(feed.recent);
            return kept.subList(Math.max(0, kept.size() - missed), kept.size());
        }

        // True once when anything arrived since the last call.
        public boolean happened() {
            return !fresh().isEmpty();
        }

        public void sync() {
            seen = feed.total;
        }
    }
}
