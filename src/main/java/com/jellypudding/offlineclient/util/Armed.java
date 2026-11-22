package com.jellypudding.offlineclient.util;

import java.util.concurrent.TimeUnit;

// One thing pressed once that waits a few seconds for a second press on the same thing. Anything
// that throws work away asks twice this way. Arming another thing stands the first one down.
public final class Armed<T> {

    public static final int CONFIRM_SECONDS = 3;
    public static final long CONFIRM_MS = TimeUnit.SECONDS.toMillis(CONFIRM_SECONDS);

    private T target;
    private long until;

    // True when this press confirms the thing armed before. Any other press arms what it was on.
    public boolean press(T pressed) {
        if (isArmed(pressed)) {
            disarm();
            return true;
        }
        target = pressed;
        until = System.currentTimeMillis() + CONFIRM_MS;
        return false;
    }

    public boolean isArmed() {
        return target != null && System.currentTimeMillis() <= until;
    }

    public boolean isArmed(T candidate) {
        return isArmed() && target.equals(candidate);
    }

    public void disarm() {
        target = null;
    }
}
