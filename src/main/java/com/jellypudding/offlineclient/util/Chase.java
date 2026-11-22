package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.movement.Flight;
import com.jellypudding.offlineclient.path.PathWalker;
import com.jellypudding.offlineclient.path.Trip;
import com.jellypudding.offlineclient.setting.BoolSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

// In the air the chase always goes straight and climbs or sinks to the height of the target.
public final class Chase {

    private static final int RETARGET_TICKS = 10;

    private static final double SWIM_LIFT = 0.04;

    private static final double LEVEL_SLACK = 0.5;

    // The game takes two fresh presses of jump within seven ticks for the double tap that
    // switches its own flight off. A press of the chase waits one tick longer than that.
    private static final int JUMP_GAP = 8;

    private final Trip trip = new Trip();
    private final HeldKey forward = new HeldKey(options -> options.keyUp);
    private final HeldKey jump = new HeldKey(options -> options.keyJump);
    private final HeldKey sneak = new HeldKey(options -> options.keyShift);
    private int retarget;

    private int jumpQuiet;

    private int ownedAt = -1;

    public static BoolSetting pathSwitch(boolean defaultValue) {
        return new BoolSetting("Use pathfinder",
            "Finds a way round what is in between instead of heading straight at the target. In the air"
                + " you always go straight and keep to the height of the target.", defaultValue);
    }

    // The pathfinder only knows the ground and has nothing to offer in flight.
    public static boolean inFlight() {
        LocalPlayer player = OfflineClient.MC.player;
        return player != null && (player.getAbilities().flying || Modules.enabled(Flight.class));
    }

    public void tick(Entity target, double keep, boolean usePath) {
        LocalPlayer player = OfflineClient.MC.player;
        FaceMode.CLIENT.face(target.getBoundingBox().getCenter(), RotationPriority.ATTACK);
        boolean flying = inFlight();
        if (usePath && !flying) {
            letGo();
            if (--retarget <= 0 || !trip.active()) {
                retarget = RETARGET_TICKS;
                trip.walker().turn(PathWalker.Turn.NONE);
                trip.start(target.blockPosition(), keep);
            }
            trip.tick();
            return;
        }
        trip.stop();
        double above = target.getY() - player.getY();
        if (flying) {
            level(player, above);
        } else {
            jump.letGo();
            sneak.letGo();
            if (player.horizontalCollision && player.onGround()) {
                player.jumpFromGround();
            }
            if (player.isInWater() && above > 0) {
                player.push(0, SWIM_LIFT, 0);
            }
        }
        forward.set(player.distanceTo(target) > keep);
    }

    // The module calls this on every tick it has nothing to chase.
    public void stop() {
        trip.stop();
        letGo();
        // The window of the last press outlives the chase. Up in the air it is seen out.
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null && player.tickCount == ownedAt + 1 && jumpQuiet < JUMP_GAP && inFlight()) {
            ownJump(player);
        }
    }

    private void level(LocalPlayer player, double above) {
        ownJump(player);
        boolean holdsJump = jump.isHeld();
        boolean climb = above > LEVEL_SLACK && (holdsJump || jumpQuiet >= JUMP_GAP);
        if (climb && !holdsJump) {
            jumpQuiet = 0;
        }
        jump.set(climb);
        sneak.set(above < -LEVEL_SLACK);
    }

    // Makes the jump key the chase's alone for this tick. A press of the player's own is put
    // back up before the game reads it since it could pair up with a press of the chase.
    // What the key did before the chase took it is unknown and a whole window is waited out.
    private void ownJump(LocalPlayer player) {
        jumpQuiet = player.tickCount == ownedAt + 1 ? Math.min(jumpQuiet + 1, JUMP_GAP) : 0;
        ownedAt = player.tickCount;
        KeyMapping key = OfflineClient.MC.options.keyJump;
        if (key.isDown() && !jump.isHeld()) {
            key.setDown(false);
        }
    }

    private void letGo() {
        forward.letGo();
        jump.letGo();
        sneak.letGo();
    }
}
