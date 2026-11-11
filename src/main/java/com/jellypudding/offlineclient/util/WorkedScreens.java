package com.jellypudding.offlineclient.util;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

// The container screens our own helpers open and work. A box set down beside you and a station
// visit each say whether the screen up is theirs. Modules that work any screen they see leave
// these alone.
public final class WorkedScreens {

    // A helper that opens a container and works it through its screen.
    interface Worker {
        // True whilst the screen up is the one this helper opened. Its items may still be on their way.
        boolean ownsScreen();
    }

    // Every helper made. Held weakly to keep none alive past whatever holds it.
    private static final Set<Worker> WORKERS = Collections.newSetFromMap(new WeakHashMap<>());

    private WorkedScreens() {
    }

    static void add(Worker worker) {
        WORKERS.add(worker);
    }

    // True whilst one of our helpers works the screen up.
    public static boolean showing() {
        return showingBesides(null);
    }

    // True whilst a helper other than the one asking works the screen up.
    static boolean showingBesides(Worker asking) {
        for (Worker worker : WORKERS) {
            if (worker != asking && worker.ownsScreen()) {
                return true;
            }
        }
        return false;
    }
}
