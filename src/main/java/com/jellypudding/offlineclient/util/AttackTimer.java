package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;

import java.util.Random;

/**
 * The wait between one hit and the next. The spread keeps the gap from
 * landing on the same number every time.
 */
public final class AttackTimer {

    // Milliseconds in one game tick.
    private static final long TICK_MS = 50;

    private final NumberSetting delay = new NumberSetting("Hit delay",
        "Ticks to wait between hits.", 0, 0, 20, 1, " ticks").min(0);
    // Evenly spaced hits are the easiest pattern for a server to spot.
    private final NumberSetting randomise = new NumberSetting("Randomise",
        "Extra random ticks added to the wait.", 2, 0, 10, 1, " ticks").min(0);

    private final Random random = new Random();

    // The moment the next hit is allowed at.
    private long readyAt;

    // Both settings in the order a module should show them.
    public Setting<?>[] settings() {
        return new Setting<?>[] {delay, randomise};
    }

    public boolean ready() {
        return System.currentTimeMillis() >= readyAt;
    }

    // Starts the next wait. Called once a hit has landed.
    public void spent() {
        int spread = randomise.getInt();
        long ticks = delay.getInt() + (spread > 0 ? random.nextInt(spread + 1) : 0);
        readyAt = System.currentTimeMillis() + ticks * TICK_MS;
    }

    // Lets the next hit through straight away.
    public void clear() {
        readyAt = 0;
    }
}
