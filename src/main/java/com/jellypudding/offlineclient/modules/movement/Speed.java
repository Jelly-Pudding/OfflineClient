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
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.SharedConstants;
import net.minecraft.world.phys.Vec3;

public final class Speed extends Module {

    public enum Mode {
        STRAFE, BHOP;

        // The automatic label would read Bhop.
        @Override
        public String toString() {
            return this == BHOP ? "BHop" : name();
        }
    }

    private static final double BASE_SPEED = 0.2806;

    private static final String TIMER_KEY = "speed";

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the speed is gained.", Mode.STRAFE)
        .describe(Mode.STRAFE, "Holds you at a set multiple of your normal speed.")
        .describe(Mode.BHOP, "Bunnyhops and multiplies your speed on every landing.");
    private final NumberSetting multiplier = new NumberSetting("Multiplier",
        "Your speed as a multiple of normal.", 1.6, 1, 10, 0.1, "x").min(0)
        .visibleWhen(() -> mode.is(Mode.STRAFE));
    private final NumberSetting hopBoost = new NumberSetting("Hop boost",
        "Multiplies your speed on every hop. Much past 1.6 the server pulls you back.",
        1.3, 1, 2, 0.05, "x").min(1).max(20)
        .visibleWhen(() -> mode.is(Mode.BHOP));
    private final BoolSetting capSpeed = new BoolSetting("Speed cap",
        "Limits your top speed.", false);
    private final NumberSetting cap = new NumberSetting("Cap",
        "Top speed in blocks a second. Still holds under a timer.",
        12, 4, 30, 0.5, " bps").min(1)
        .under(capSpeed);
    private final BoolSetting keepInAir = new BoolSetting("Keep in air",
        "Keeps your speed whilst airborne.", true)
        .visibleWhen(() -> mode.is(Mode.STRAFE));
    private final BoolSetting forceSprint = new BoolSetting("Force sprint",
        "Sprints whilst you move for longer jumps.", true);
    private final BoolSetting inLiquids = new BoolSetting("In liquids",
        "Keeps the speed in water and lava.", false)
        .visibleWhen(() -> mode.is(Mode.STRAFE));
    private final BoolSetting whilstSneaking = new BoolSetting("Whilst sneaking",
        "Keeps the speed whilst you sneak.", false);
    private final NumberSetting timer = new NumberSetting("Timer",
        "Speeds up the game whilst you move. 1 is normal.", 1, 1, 3, 0.1, "x").min(1);

    public Speed() {
        super("Speed", "Move faster on the ground.", Category.MOVEMENT);
        addSettings(mode, multiplier, hopBoost, capSpeed, cap, keepInAir, forceSprint, inLiquids,
            whilstSneaking, timer);
        searchTags("bunnyhop", "bhop", "strafe");
    }

    @Override
    protected void onDisable() {
        Timer.override(TIMER_KEY, 1f);
    }

    @Override
    public String getSuffix() {
        NumberSetting amount = mode.is(Mode.BHOP) ? hopBoost : multiplier;
        return mode.getValueString() + " " + amount.getValueString();
    }

    // TickEvent stops at a disconnect. ClientTickEvent still runs in the menus.
    // A sprint set any earlier in the tick is cleared again by the player tick.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!inGame()) {
            Timer.override(TIMER_KEY, 1f);
            return;
        }
        if (forceSprint.isOn() && !mc.player.isShiftKeyDown() && !mc.player.isUsingItem()) {
            mc.player.setSprinting(true);
        }
    }

    // Runs after HoleSnap. A hole it has found is not pushed past.
    @Subscribe(priority = -10)
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        Vec3 heading = MovementUtil.inputDirection();
        boolean moving = heading.lengthSqr() > 0;
        Timer.override(TIMER_KEY, moving ? timer.getFloat() : 1f);

        if (mc.player.isSpectator() || mc.player.isPassenger()) {
            return;
        }
        HoleSnap holeSnap = Modules.get(HoleSnap.class);
        if (holeSnap != null && holeSnap.holdsMovement()) {
            return;
        }
        if (mc.player.isShiftKeyDown() && !whilstSneaking.isOn()) {
            return;
        }
        boolean inLiquid = mc.player.isInWater() || mc.player.isInLava();
        if (inLiquid && (mode.is(Mode.BHOP) || !inLiquids.isOn())) {
            return;
        }
        if (mc.player.onClimbable() || mc.player.isFallFlying() || mc.player.getAbilities().flying) {
            return;
        }
        if (!moving) {
            return;
        }

        if (mode.is(Mode.BHOP)) {
            hop();
        } else {
            strafe(heading);
        }
    }

    private void strafe(Vec3 heading) {
        if (!mc.player.onGround() && !keepInAir.isOn()) {
            return;
        }
        double base = baseSpeed();
        double speed = capped(base * multiplier.getValue());
        // The cap must never drag the player below what they would move at anyway.
        speed = Math.max(base, speed);
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(heading.x * speed, velocity.y, heading.z * speed);
    }

    // Under a timer the whole game runs faster.
    // The cap shrinks with it to stay true in real seconds.
    private double capped(double perTick) {
        if (!capSpeed.isOn()) {
            return perTick;
        }
        return Math.min(perTick, cap.getValue() / (SharedConstants.TICKS_PER_SECOND * Timer.current()));
    }

    // Potion effects scale the walk speed attribute. The ceiling moves with them.
    private double baseSpeed() {
        return MovementUtil.withSpeedEffects(mc.player, BASE_SPEED);
    }

    // The air movement stays vanilla.
    private void hop() {
        if (!mc.player.onGround()) {
            return;
        }
        // Whilst the key is held the game jumps on its own.
        // Two jumps in one tick stack the sprint boost twice.
        if (!mc.player.input.keyPresses.jump()) {
            mc.player.jumpFromGround();
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        double current = velocity.horizontalDistance();
        if (current < 1.0E-6) {
            return;
        }
        double wanted = capped(current * hopBoost.getValue());
        double scale = wanted / current;
        mc.player.setDeltaMovement(velocity.x * scale, velocity.y, velocity.z * scale);
    }
}
