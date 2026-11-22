package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.BounceWay;
import com.jellypudding.offlineclient.util.GlideSwitch;
import com.jellypudding.offlineclient.util.HeldKey;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

// The Bounce mode of ElytraFly. A pull back shuts the wings for a moment to let the server settle.
final class ElytraBounce {

    enum Heading { FREE, SNAP, FIXED }

    private static final Minecraft MC = OfflineClient.MC;

    private static final float YAW_SNAP = 45;

    // A ceiling this close over the head stops a jump at once. A tunnel two blocks high leaves
    // a fifth of a block.
    private static final double LOW_CEILING = 0.5;
    // A sprint jump pushes this far along the heading. Tunnel run keeps to the floor under a low
    // ceiling and takes the push every tick.
    private static final double JUMP_PUSH = 0.2;
    private static final double BOUNCE_BRAKE = 0.6;

    private final EnumSetting<Heading> bounceHeading = new EnumSetting<>("Heading",
        "How the bounce steers.", Heading.SNAP)
        .describe(Heading.FREE, "You steer with the mouse.")
        .describe(Heading.SNAP, "Snaps your heading to the nearest 45 degrees.")
        .describe(Heading.FIXED, "Holds the yaw you set below.");
    private final NumberSetting bounceYaw = new NumberSetting("Yaw",
        "The heading held whilst bouncing.", 0, 0, 360, 1, " degrees").max(360)
        .under(bounceHeading, Heading.FIXED);
    private final BoolSetting lockPitch = new BoolSetting("Lock pitch",
        "Holds the pitch below whilst bouncing. Off leaves the pitch to you.", true);
    private final NumberSetting bouncePitch = new NumberSetting("Pitch",
        "The pitch held whilst bouncing.", 0, -90, 90, 1, " degrees")
        .under(lockPitch);
    private final BoolSetting autoJump = new BoolSetting("Auto jump",
        "Holds jump for you. Off only holds forward and leaves the jumping to you.", true);
    private final BoolSetting tunnelRun = new BoolSetting("Tunnel run",
        "Under a ceiling two blocks high it lets go of jump and runs along the floor with the push of a jump every"
            + " tick. A speed check may notice how fast that is.", false)
        .under(autoJump);
    private final BoolSetting manualTakeOff = new BoolSetting("Manual take off",
        "Leaves opening the wings to you. The wings still reopen after a pull back.", false);
    private final BoolSetting sprintAlways = new BoolSetting("Sprint always",
        "Keeps sprint on in the air too. Off sprints on the ground only and stops the view zoom flicker.", true);
    private final BoolSetting restart = new BoolSetting("Restart",
        "Opens the wings again after the server pulls you back.", true);
    private final NumberSetting restartDelay = new NumberSetting("Restart delay",
        "Ticks to wait after the server pulls you back before the wings open again.",
        20, 0, 100, 1, " ticks")
        .under(restart);
    private final BoolSetting passObstacles = new BoolSetting("Pass obstacles",
        "Walks round a block in the way with the pathfinder and bounces on from the first spot past it on your line.",
        false);
    private final EnumSetting<BounceWay.Portals> portalTraps = new EnumSetting<>("Portal traps",
        "What the bounce does about a nether portal built across the way ahead.", BounceWay.Portals.IGNORE)
        .describe(BounceWay.Portals.IGNORE, "Takes no notice of portals.")
        .describe(BounceWay.Portals.STOP, "Stops short of the portal and stands until you turn or move on.")
        .describe(BounceWay.Portals.GO_ROUND, "Stops short of the portal and walks round it with the pathfinder.");

    private final GlideSwitch glide = GlideSwitch.get();
    private final HeldKey forward = new HeldKey(options -> options.keyUp);
    private final HeldKey jump = new HeldKey(options -> options.keyJump);
    private final BounceWay way = new BounceWay();

    private boolean reopen;
    private boolean fovHeld;
    private int bounceRestart;
    private boolean rubberbanded;

    Setting<?>[] settings() {
        return new Setting<?>[] {bounceHeading, bounceYaw, lockPitch, bouncePitch, autoJump, tunnelRun, manualTakeOff,
            sprintAlways, restart, restartDelay, passObstacles, portalTraps};
    }

    <E extends Enum<E>> ElytraBounce under(EnumSetting<E> parent, E value) {
        Setting.under(settings(), parent, value);
        return this;
    }

    void start() {
        bounceRestart = 0;
        rubberbanded = false;
        reopen = false;
    }

    void pulledBack(boolean bouncing) {
        rubberbanded = bouncing;
    }

    // True when the wings are to open behind the position packet of the tick.
    boolean tick(boolean steered, boolean standStill) {
        if (rubberbanded) {
            rubberbanded = false;
            glide.close();
            if (restart.isOn()) {
                bounceRestart = restartDelay.getInt();
                reopen = true;
            }
        }
        if (bounceRestart > 0) {
            bounceRestart--;
            standAside();
            return false;
        }
        if (standStill) {
            standAside();
            brakeBounce();
            return false;
        }
        if (MC.player.isPassenger() || MC.player.getAbilities().flying
            || !MovementUtil.wearsGlider()) {
            standAside();
            return false;
        }
        if (lockPitch.isOn()) {
            MC.player.setXRot(bouncePitch.getFloat());
        }
        // The autopilot turns the view itself.
        switch (steered ? Heading.FREE : bounceHeading.getValue()) {
            case SNAP -> MC.player.setYRot(Math.round(MC.player.getYRot() / YAW_SNAP) * YAW_SNAP);
            case FIXED -> MC.player.setYRot(bounceYaw.getFloat());
            case FREE -> { }
        }
        if (wayBlocked()) {
            return false;
        }
        // Sprinting only on the ground pumps the view zoom on and off every hop. The zoom is
        // held still for the ride.
        if (!sprintAlways.isOn()) {
            fovHeld = true;
        }
        forward.hold();
        boolean tunnel = autoJump.isOn() && tunnelRun.isOn() && MC.player.onGround() && underLowCeiling();
        jump.set(autoJump.isOn() && !tunnel);
        // Sprinting in the air upsets some anti cheats.
        // Sprint always is for servers that do not mind it.
        MC.player.setSprinting(sprintAlways.isOn() || MC.player.onGround()
            || !MC.player.isFallFlying());
        if (tunnel) {
            float yaw = MC.player.getYRot() * Mth.DEG_TO_RAD;
            MC.player.addDeltaMovement(new Vec3(-Mth.sin(yaw) * JUMP_PUSH, 0, Mth.cos(yaw) * JUMP_PUSH));
        }
        if (MC.player.isFallFlying() || MC.player.onGround()) {
            return false;
        }
        // A manual take off still reopens the wings once after a pull back.
        if (!manualTakeOff.isOn() || reopen) {
            reopen = false;
            return true;
        }
        return false;
    }

    void letGo() {
        way.release();
        releaseKeys();
        fovHeld = false;
    }

    void reset() {
        way.reset();
    }

    void idle() {
        way.standAside();
    }

    void onPacket(Packet<?> packet) {
        if (!portalTraps.is(BounceWay.Portals.IGNORE)) {
            way.onPacket(packet);
        }
    }

    String stoppedFor() {
        return way.stoppedFor();
    }

    boolean holdsFovStill() {
        return fovHeld;
    }

    private boolean wayBlocked() {
        if (!passObstacles.isOn() && portalTraps.is(BounceWay.Portals.IGNORE)) {
            way.release();
            return false;
        }
        BounceWay.State state = way.check(MC.player.getYRot(), passObstacles.isOn(), portalTraps.getValue());
        if (state == BounceWay.State.CLEAR) {
            return false;
        }
        releaseKeys();
        fovHeld = false;
        switch (state) {
            case BRAKING -> brakeBounce();
            case WALKING -> way.walk();
            default -> {
            }
        }
        return true;
    }

    private void standAside() {
        releaseKeys();
        way.standAside();
    }

    private void brakeBounce() {
        if (MC.player.isFallFlying()) {
            glide.close();
        }
        Vec3 velocity = MC.player.getDeltaMovement();
        MC.player.setDeltaMovement(velocity.x * BOUNCE_BRAKE, velocity.y, velocity.z * BOUNCE_BRAKE);
    }

    private boolean underLowCeiling() {
        return !MC.level.noCollision(MC.player, MC.player.getBoundingBox().expandTowards(0, LOW_CEILING, 0));
    }

    private void releaseKeys() {
        forward.letGo();
        jump.letGo();
    }
}
