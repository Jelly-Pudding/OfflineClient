package com.jellypudding.offlineclient.util;

import net.minecraft.util.Util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

// MouseHandlerMixin hands over every real press. A held button is one click however long it stays
// down. Both sides run on the game thread.
public final class ClickRate {

    private static final long WINDOW_MILLIS = 1000;

    private static final Map<Integer, Deque<Long>> PRESSES = new HashMap<>();

    private ClickRate() {
    }

    // Takes the button numbers InputConstants gives such as MOUSE_BUTTON_LEFT.
    public static void press(int button) {
        long now = Util.getMillis();
        Deque<Long> times = PRESSES.computeIfAbsent(button, key -> new ArrayDeque<>());
        times.addLast(now);
        forgetOld(times, now);
    }

    public static int perSecond(int button) {
        Deque<Long> times = PRESSES.get(button);
        if (times == null) {
            return 0;
        }
        forgetOld(times, Util.getMillis());
        return times.size();
    }

    private static void forgetOld(Deque<Long> times, long now) {
        while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MILLIS) {
            times.removeFirst();
        }
    }
}
