package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public final class Speed extends Module {

    public enum Mode {
        STRAFE("Strafe"),
        HOP("Hop");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final double BASE_SPEED = 0.2806;

    // Each level of the speed effect adds a fifth and each level of slowness takes off just under a sixth.
    private static final double SPEED_PER_LEVEL = 0.2;
    private static final double SLOWNESS_PER_LEVEL = 0.15;

    private static final double TICKS_PER_SECOND = 20;

    private static final double HOP_SHARE = 0.3;
    private static final double MAX_HOP_FACTOR = 1.6;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Strafe pushes you along the ground. Hop bunnyhops you forward instead.", Mode.STRAFE);
    private final NumberSetting multiplier = new NumberSetting("Multiplier",
        "Ground speed multiplier.", 1.6, 1, 10, 0.1, "x").min(0);
    private final NumberSetting speedCap = new NumberSetting("Speed cap",
        "Never move faster than this many blocks a second.", 12, 4, 30, 0.5, " bps")
        .min(1).visibleWhen(() -> mode.is(Mode.STRAFE));
    private final BoolSetting keepInAir = new BoolSetting("Keep in air",
        "Keeps your speed whilst airborne.", true)
        .visibleWhen(() -> mode.is(Mode.STRAFE));
    private final BoolSetting forceSprint = new BoolSetting("Force sprint",
        "Hold sprint on whilst you move so the jump keeps its boost.", true);
    private final BoolSetting inLiquids = new BoolSetting("In liquids",
        "Keep pushing whilst you are in water or lava.", false);
    private final BoolSetting whilstSneaking = new BoolSetting("Whilst sneaking",
        "Keep pushing whilst you sneak. Very easy to spot.", false);
    private final NumberSetting timer = new NumberSetting("Timer",
        "Also speeds up the game whilst you move. 1 does nothing.", 1, 1, 3, 0.1, "x").min(1);

    public Speed() {
        super("Speed", "Move faster on the ground.", Category.MOVEMENT);
        addSettings(mode, multiplier, speedCap, keepInAir, forceSprint, inLiquids,
            whilstSneaking, timer);
        searchTags("bunnyhop", "strafe");
    }

    // Modules.get is cached.
    private static void setTimerOverride(float multiplier) {
        Timer module = Modules.get(Timer.class);
        if (module != null) {
            module.setOverride("speed", multiplier);
        }
    }

    @Override
    protected void onDisable() {
        setTimerOverride(1f);
    }

    @Override
    public String getSuffix() {
        return mode.getValue() + " " + multiplier.getValueString();
    }

    // TickEvent stops at a disconnect. ClientTickEvent still runs in the menus.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!inGame()) {
            setTimerOverride(1f);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        boolean moving = mc.player.input.getMoveVector().length() > 1e-4f;
        setTimerOverride(moving ? timer.getFloat() : 1f);

        if (mc.player.isSpectator() || mc.player.isPassenger()) {
            return;
        }
        if (mc.player.isShiftKeyDown() && !whilstSneaking.isOn()) {
            return;
        }
        if ((mc.player.isInWater() || mc.player.isInLava()) && !inLiquids.isOn()) {
            return;
        }
        if (mc.player.onClimbable() || mc.player.isFallFlying() || mc.player.getAbilities().flying) {
            return;
        }
        Vec2 move = mc.player.input.getMoveVector();
        if (move.length() < 1e-4f) {
            return;
        }
        if (forceSprint.isOn() && !mc.player.isShiftKeyDown() && !mc.player.isUsingItem()) {
            mc.player.setSprinting(true);
        }

        if (mode.is(Mode.HOP)) {
            hop();
        } else {
            strafe(move);
        }
    }

    private void strafe(Vec2 move) {
        if (!mc.player.onGround() && !keepInAir.isOn()) {
            return;
        }
        double base = baseSpeed();
        // The cap must never drag the player below what they would move at anyway.
        double speed = Math.max(base,
            Math.min(base * multiplier.getValue(), speedCap.getValue() / TICKS_PER_SECOND));
        double angle = Math.toRadians(mc.player.getYRot()) + Math.atan2(-move.x, move.y);
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(-Math.sin(angle) * speed, velocity.y, Math.cos(angle) * speed);
    }

    // Potion effects scale the walk speed attribute so the ceiling moves with them.
    private double baseSpeed() {
        double speed = BASE_SPEED;
        MobEffectInstance boost = mc.player.getEffect(MobEffects.SPEED);
        if (boost != null) {
            speed *= 1 + SPEED_PER_LEVEL * (boost.getAmplifier() + 1);
        }
        MobEffectInstance slow = mc.player.getEffect(MobEffects.SLOWNESS);
        if (slow != null) {
            speed *= Math.max(0, 1 - SLOWNESS_PER_LEVEL * (slow.getAmplifier() + 1));
        }
        return speed;
    }

    // The air movement stays vanilla.
    private void hop() {
        if (!mc.player.onGround()) {
            return;
        }
        double factor = Math.min(1 + (multiplier.getValue() - 1) * HOP_SHARE, MAX_HOP_FACTOR);
        // Whilst the key is held the game jumps on its own. Two jumps in one tick stack the sprint boost twice.
        if (!mc.player.input.keyPresses.jump()) {
            mc.player.jumpFromGround();
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(velocity.x * factor, velocity.y, velocity.z * factor);
    }
}
