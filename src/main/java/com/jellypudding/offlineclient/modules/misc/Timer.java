package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

// Behaviour lives in DeltaTrackerMixin. Other modules can push a temporary override.
public final class Timer extends Module {

    public enum Mode { STEADY, CHARGE }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode", "When the game is sped up.", Mode.STEADY)
        .describe(Mode.STEADY, "Runs the game at the set speed all the time.")
        .describe(Mode.CHARGE, "Saves a tick for every tick you send the server no movement and spends them"
            + " at the burst speed once you move. You never send more than a steady player would.");
    private final NumberSetting speed = new NumberSetting("Speed",
        "Game speed multiplier.", 2, 0.1, 10, 0.1, "x").under(mode, Mode.STEADY);
    // Saved ticks can only be spent faster than the game runs by itself.
    private final NumberSetting burstSpeed = new NumberSetting("Burst speed",
        "Game speed multiplier whilst saved ticks are spent.", 2, 1.1, 10, 0.1, "x")
        .min(1.01).under(mode, Mode.CHARGE);
    private final NumberSetting maxCharge = new NumberSetting("Max charge",
        "The most ticks that can be saved up.", 20, 1, 100, 1, " ticks").under(mode, Mode.CHARGE);

    private final Map<String, Float> overrides = new ConcurrentHashMap<>();

    private double saved;
    private boolean bursting;
    // Set on whichever thread sends the packet.
    private volatile boolean sentMove;

    public Timer() {
        super("Timer", "Speeds up or slows down the whole game client side.", Category.MISC);
        addSettings(mode, speed, burstSpeed, maxCharge);
        searchTags("tick shift", "game speed");
    }

    @Override
    public String getSuffix() {
        if (mode.is(Mode.CHARGE)) {
            return (int) saved + " saved";
        }
        float effective = getSpeed();
        if (effective == speed.getFloat()) {
            return speed.getValueString();
        }
        return speed.write(effective) + "x";
    }

    public static float current() {
        Timer timer = Modules.get(Timer.class);
        return timer == null ? 1f : timer.getSpeed();
    }

    public static void override(String key, float multiplier) {
        Timer timer = Modules.get(Timer.class);
        if (timer != null) {
            timer.setOverride(key, multiplier);
        }
    }

    // A value of one or less clears that module's boost. The highest active source wins.
    private void setOverride(String key, float multiplier) {
        if (multiplier <= 1f) {
            overrides.remove(key);
        } else {
            overrides.put(key, multiplier);
        }
    }

    private float ownSpeed() {
        if (mode.is(Mode.STEADY)) {
            return speed.getFloat();
        }
        return bursting ? burstSpeed.getFloat() : 1f;
    }

    public float getSpeed() {
        float best = isEnabled() ? ownSpeed() : 1f;
        for (float value : overrides.values()) {
            best = Math.max(best, value);
        }
        return best;
    }

    @Override
    protected void onDisable() {
        forgetCharge();
    }

    // A burst ends with the world. The menus and the next join run at normal speed with nothing saved.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (mc.level == null) {
            forgetCharge();
        }
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (event.getPacket() instanceof ServerboundMovePlayerPacket) {
            sentMove = true;
        }
    }

    // A burst at twice the speed runs two ticks where one was due and each of them spends
    // half a saved one.
    @Subscribe
    private void onTick(TickEvent event) {
        boolean moved = sentMove;
        sentMove = false;
        if (!mode.is(Mode.CHARGE)) {
            forgetCharge();
            return;
        }
        float pace = burstSpeed.getFloat();
        bursting = saved > 0 && MovementUtil.hasInput();
        if (bursting) {
            saved = Math.max(0, saved - (pace - 1) / pace);
        } else if (!moved) {
            saved = Math.min(maxCharge.getValue(), saved + 1);
        }
    }

    private void forgetCharge() {
        saved = 0;
        bursting = false;
    }
}
