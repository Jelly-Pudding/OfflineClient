package com.jellypudding.offlineclient.rig.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Every line the client writes to the player. A mixin of the rig feeds it.
public final class ChatTap {

    private static final List<String> LINES = Collections.synchronizedList(new ArrayList<>());

    private ChatTap() {
    }

    public static void heard(String line) {
        LINES.add(line);
    }

    // How many lines have been written. Lines from this count on are read with since.
    public static int mark() {
        return LINES.size();
    }

    public static List<String> since(int mark) {
        synchronized (LINES) {
            return new ArrayList<>(LINES.subList(Math.min(mark, LINES.size()), LINES.size()));
        }
    }
}
