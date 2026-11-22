package com.jellypudding.offlineclient.rig.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Keeps every exception no thread caught. The game's own handler still gets each one.
public final class Uncaught {

    private static final List<String> SEEN = Collections.synchronizedList(new ArrayList<>());
    private static boolean installed;

    private Uncaught() {
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, thrown) -> {
            SEEN.add("on thread " + thread.getName() + " " + Rig.describe(thrown));
            if (before != null) {
                before.uncaughtException(thread, thrown);
            }
        });
    }

    public static void clear() {
        SEEN.clear();
    }

    public static List<String> drain() {
        synchronized (SEEN) {
            List<String> seen = new ArrayList<>(SEEN);
            SEEN.clear();
            return seen;
        }
    }
}
