package com.jellypudding.offlineclient.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class Background {

    private Background() {
    }

    // One quiet thread that takes its jobs in turn. The game never waits for it to end.
    public static ExecutorService worker(String name) {
        return Executors.newSingleThreadExecutor(quiet(name));
    }

    public static ExecutorService workers(String name, int threads) {
        return Executors.newFixedThreadPool(threads, quiet(name));
    }

    private static ThreadFactory quiet(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        };
    }
}
