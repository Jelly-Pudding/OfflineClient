package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;

import java.util.Random;

// The wait between one hit and the next.
// The spread keeps the gap from landing on the same number every time.
public final class AttackTimer {

    // Milliseconds in one game tick.
    private static final long TICK_MS = 50;
    private static final float FULL_TPS = 20;

    private final NumberSetting delay = new NumberSetting("Hit delay",
        "Ticks to wait between hits.", 0, 0, 20, 1, " ticks").min(0);
    // Evenly spaced hits are the easiest pattern for a server to spot.
    private final NumberSetting randomise = new NumberSetting("Randomise",
        "Extra random ticks added to the wait.", 2, 0, 10, 1, " ticks").min(0);
    private final BoolSetting tpsSync = new BoolSetting("TPS sync",
        "Stretches the wait whilst the server ticks slowly so no hit is wasted.", true);

    private final Random random = new Random();

    // The moment the next hit is allowed at.
    private long readyAt;

    // The settings in the order a module should show them.
    public Setting<?>[] settings() {
        return new Setting<?>[] {delay, randomise, tpsSync};
    }

    public boolean ready() {
        return System.currentTimeMillis() >= readyAt;
    }

    // Starts the next wait. Called once a hit has landed.
    public void spent() {
        int spread = randomise.getInt();
        double ticks = delay.getInt() + (spread > 0 ? random.nextInt(spread + 1) : 0);
        if (tpsSync.isOn()) {
            ticks *= FULL_TPS / Math.max(1, TickRate.INSTANCE.tps());
        }
        readyAt = System.currentTimeMillis() + Math.round(ticks * TICK_MS);
    }

    // Lets the next hit through straight away.
    public void clear() {
        readyAt = 0;
    }
}
