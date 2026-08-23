package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

// Control nudges the vanilla glide. Cruise flies a dive and climb cycle itself.
public final class ElytraFly extends Module {

    public enum Mode {
        CONTROL("Control"),
        CRUISE("Cruise");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    // Around forty degrees down is where a glide trades the most height for speed.
    private static final float DIVE_PITCH = 40;
    private static final float CLIMB_PITCH = -30;

    private static final float PITCH_STEP = 6;

    private static final double TICKS_PER_SECOND = 20;

    // Below this fraction of the target speed a climb would stall.
    private static final double STALL_FRACTION = 0.5;

    private static final double LOW_DURABILITY = 0.05;

    private static final double TAKE_OFF_CLEARANCE = 3;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Control steers by hand. Cruise flies long distances on its own.", Mode.CONTROL);
    private final NumberSetting speed = new NumberSetting("Horizontal speed",
        "How hard the movement keys push you along.", 1, 0.2, 5, 0.1, "x")
        .min(0.1).max(20).visibleWhen(() -> mode.is(Mode.CONTROL));
    private final NumberSetting climbSpeed = new NumberSetting("Vertical speed",
        "How hard jump and sneak push you up and down.", 1, 0.2, 5, 0.1, "x")
        .min(0.1).max(20).visibleWhen(() -> mode.is(Mode.CONTROL));
    private final BoolSetting holdHeight = new BoolSetting("Hold height",
        "Stops the slow sink whilst you glide level.", true)
        .visibleWhen(() -> mode.is(Mode.CONTROL));
    private final BoolSetting instantStop = new BoolSetting("Instant stop",
        "Kills your momentum when you release the keys.", false)
        .visibleWhen(() -> mode.is(Mode.CONTROL));
    private final NumberSetting cruiseSpeed = new NumberSetting("Cruise speed",
        "The speed the dive and climb cycle aims for.", 30, 10, 60, 1, " bps")
        .min(5).visibleWhen(() -> mode.is(Mode.CRUISE));
    private final BoolSetting holdAltitude = new BoolSetting("Hold altitude",
        "Cycles around the height you set off from.", true)
        .visibleWhen(() -> mode.is(Mode.CRUISE));
    private final BoolSetting autoTakeOff = new BoolSetting("Auto take off",
        "Opens the elytra for you as soon as you fall.", true);
    private final BoolSetting keepGliding = new BoolSetting("Keep gliding",
        "Restarts a glide the game cancels midair.", true);
    private final BoolSetting stopInWater = new BoolSetting("Stop in water",
        "Does nothing whilst you are in water.", true);
    private final BoolSetting durabilityGuard = new BoolSetting("Durability guard",
        "Stops helping and warns you when the elytra is nearly broken.", true);

    private int restartCooldown;
    private boolean wasGliding;
    private boolean warned;

    private double cruiseY;
    private boolean diving;
    private float forcedPitch;
    private boolean cruising;

    public ElytraFly() {
        super("ElytraFly", "Full elytra control without firework rockets.", Category.MOVEMENT);
        addSettings(mode, speed, climbSpeed, holdHeight, instantStop, cruiseSpeed, holdAltitude,
            autoTakeOff, keepGliding, stopInWater, durabilityGuard);
        searchTags("elytra", "glide", "fly", "cruise");
    }

    @Override
    public String getSuffix() {
        if (mode.is(Mode.CRUISE)) {
            return cruising ? (diving ? "diving" : "climbing") : "cruise";
        }
        return speed.getValueString() + " " + climbSpeed.getValueString();
    }

    @Override
    protected void onEnable() {
        restartCooldown = 0;
        wasGliding = false;
        cruising = false;
        warned = false;
    }

    @Override
    protected void onDisable() {
        cruising = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (restartCooldown > 0) {
            restartCooldown--;
        }
        if (stopInWater.isOn() && (mc.player.isInWater() || mc.player.isUnderWater())) {
            cruising = false;
            wasGliding = false;
            return;
        }
        if (durabilityGuard.isOn() && checkDurability()) {
            cruising = false;
            wasGliding = mc.player.isFallFlying();
            return;
        }

        if (!mc.player.isFallFlying()) {
            cruising = false;
            startGlide();
            wasGliding = false;
            return;
        }
        wasGliding = true;

        if (mode.is(Mode.CRUISE)) {
            cruiseTick();
        } else {
            cruising = false;
            controlTick();
        }
    }

    // Additive nudges. The glide itself stays vanilla.
    private void controlTick() {
        Input keys = mc.player.input.keyPresses;
        Vec3 velocity = mc.player.getDeltaMovement();
        double accel = 0.08 * speed.getValue();

        double vx = velocity.x;
        double vy = velocity.y;
        double vz = velocity.z;

        double yaw = Math.toRadians(mc.player.getYRot());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = -Math.cos(yaw);
        double rightZ = -Math.sin(yaw);

        boolean steering = keys.forward() || keys.backward() || keys.left() || keys.right();
        if (keys.forward()) {
            vx += forwardX * accel;
            vz += forwardZ * accel;
        }
        if (keys.backward()) {
            vx -= forwardX * accel;
            vz -= forwardZ * accel;
        }
        if (keys.right()) {
            vx += rightX * accel;
            vz += rightZ * accel;
        }
        if (keys.left()) {
            vx -= rightX * accel;
            vz -= rightZ * accel;
        }

        double climb = climbSpeed.getValue();
        if (keys.jump()) {
            vy += 0.08 * climb;
        } else if (keys.shift()) {
            vy -= 0.04 * climb;
        } else if (holdHeight.isOn() && mc.player.getXRot() < 25) {
            // Lift cancels the glide sink except whilst pitched down into a dive.
            double cos = Math.cos(Math.toRadians(mc.player.getXRot()));
            vy = Math.max(vy, 0.08 * (1 - 0.75 * cos * cos));
        }

        if (instantStop.isOn() && !steering) {
            vx = 0;
            vz = 0;
            if (!keys.jump() && vy > 0) {
                vy = 0;
            }
        }

        mc.player.setDeltaMovement(vx, vy, vz);
    }

    // Yaw is left alone.
    private void cruiseTick() {
        if (!cruising) {
            cruising = true;
            cruiseY = mc.player.getY();
            diving = true;
            forcedPitch = mc.player.getXRot();
        }

        Input keys = mc.player.input.keyPresses;
        if (holdAltitude.isOn()) {
            if (keys.jump()) {
                cruiseY += 1;
            } else if (keys.shift()) {
                cruiseY -= 1;
            }
        }

        Vec3 velocity = mc.player.getDeltaMovement();
        double bps = velocity.horizontalDistance() * TICKS_PER_SECOND;
        double target = cruiseSpeed.getValue();
        boolean aboveLine = !holdAltitude.isOn() || mc.player.getY() >= cruiseY;

        if (bps < target * STALL_FRACTION) {
            diving = true;
        } else if (bps >= target) {
            diving = false;
        } else {
            diving = aboveLine;
        }

        float wanted = diving ? DIVE_PITCH : CLIMB_PITCH;
        forcedPitch = forcedPitch + Mth.clamp(wanted - forcedPitch, -PITCH_STEP, PITCH_STEP);
        mc.player.setXRot(forcedPitch);
    }

    private void startGlide() {
        if (restartCooldown > 0) {
            return;
        }
        boolean allowed = wasGliding ? keepGliding.isOn() : autoTakeOff.isOn();
        if (!allowed) {
            return;
        }
        if (mc.player.onGround() || mc.player.isPassenger() || mc.player.isInWater()
            || mc.player.getAbilities().flying
            || !mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            return;
        }
        if (mc.player.getDeltaMovement().y >= 0) {
            return;
        }
        // Close to the ground a glide stutters between landing and gliding.
        double clearance = wasGliding ? 1.5 : TAKE_OFF_CLEARANCE;
        if (!mc.level.noCollision(mc.player,
            mc.player.getBoundingBox().expandTowards(0, -clearance, 0))) {
            return;
        }
        mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player,
            ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        restartCooldown = 5;
    }

    // Warns once.
    private boolean checkDurability() {
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA) || !chest.isDamageableItem()) {
            warned = false;
            return false;
        }
        int max = chest.getMaxDamage();
        int left = max - chest.getDamageValue();
        if (left > max * LOW_DURABILITY) {
            warned = false;
            return false;
        }
        if (!warned) {
            warned = true;
            ChatUtil.error("Your elytra is nearly broken. ElytraFly has stopped helping.");
        }
        return true;
    }
}
