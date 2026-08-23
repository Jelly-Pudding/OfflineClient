package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The one owner of the rotation the server sees. Exactly one rotation rides
 * the vanilla movement packet each tick and the player's own view never moves.
 */
public final class RotationManager {

    // Registered on the event bus once from OfflineClient.init.
    public static final RotationManager INSTANCE = new RotationManager();

    // The most degrees a managed rotation moves in one tick.
    public static final float STEP = 45f;

    public static final float BLOCK_TOLERANCE = 3f;

    // Entities move every tick and the angle the server holds is always stale.
    public static final float ENTITY_TOLERANCE = 25f;

    // A step that reaches any angle in one tick.
    private static final float NO_STEP = 360f;

    private static final Minecraft MC = OfflineClient.MC;

    // The winning request of the current tick. Null once it has been taken.
    private RotationPriority priority;

    private boolean holding;
    private float heldYaw;
    private float heldPitch;

    // The angle this tick has already settled on. Read by isFacing so a caller
    // can act on the same tick it asks to turn.
    private boolean projected;
    private float projectedYaw;
    private float projectedPitch;

    // True for the rest of the tick once the held angle has been worked out.
    private boolean writeRotation;

    // Written from the packet send hook on whichever thread sent the packet.
    private volatile float serverYaw;
    private volatile float serverPitch;
    private volatile boolean rotationSent;

    private RotationManager() {
    }

    /**
     * Asks for an angle this tick. The turn is spread over several ticks when
     * the target is far from the angle the server holds.
     */
    public static void request(float yaw, float pitch, RotationPriority priority) {
        INSTANCE.take(yaw, pitch, priority, STEP);
    }

    /**
     * Asks for an angle that has to arrive whole. Only for places where the
     * exact number changes the outcome such as bed direction.
     */
    public static void requestExact(float yaw, float pitch, RotationPriority priority) {
        INSTANCE.take(yaw, pitch, priority, NO_STEP);
    }

    /**
     * Asks for an angle at a chosen turn rate in degrees per tick. Used where a
     * module exposes its own rotation speed.
     */
    public static void request(float yaw, float pitch, RotationPriority priority, float step) {
        INSTANCE.take(yaw, pitch, priority, step);
    }

    public static boolean look(Vec3 point, RotationPriority priority, float tolerance) {
        return look(point, priority, tolerance, STEP);
    }

    public static boolean look(Vec3 point, RotationPriority priority, float tolerance, float step) {
        float yaw = yawTo(point);
        float pitch = pitchTo(point);
        request(yaw, pitch, priority, step);
        return isFacing(yaw, pitch, tolerance);
    }

    public static boolean isRotating() {
        return INSTANCE.holding;
    }

    public static float getServerYaw() {
        return INSTANCE.serverYaw;
    }

    public static float getServerPitch() {
        return INSTANCE.serverPitch;
    }

    public static boolean isFacing(float yaw, float pitch, float tolerance) {
        float heldNow = INSTANCE.projected ? INSTANCE.projectedYaw : INSTANCE.serverYaw;
        float pitchNow = INSTANCE.projected ? INSTANCE.projectedPitch : INSTANCE.serverPitch;
        return Mth.degreesDifferenceAbs(heldNow, yaw) <= tolerance
            && Math.abs(pitchNow - pitch) <= tolerance;
    }

    public static float yawTo(Vec3 point) {
        Vec3 eye = MC.player.getEyePosition();
        return (float) Math.toDegrees(Math.atan2(point.z - eye.z, point.x - eye.x)) - 90f;
    }

    public static float pitchTo(Vec3 point) {
        Vec3 eye = MC.player.getEyePosition();
        double dx = point.x - eye.x;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return Math.clamp((float) -Math.toDegrees(Math.atan2(point.y - eye.y, horizontal)), -90f, 90f);
    }

    // Ties go to the first caller.
    private void take(float yaw, float pitch, RotationPriority asked, float step) {
        if (MC.player == null || (priority != null && !asked.beats(priority))) {
            return;
        }
        priority = asked;

        float fromYaw = holding ? heldYaw : MC.player.getYRot();
        float fromPitch = holding ? heldPitch : MC.player.getXRot();
        projectedYaw = Mth.wrapDegrees(Mth.approachDegrees(fromYaw, yaw, step));
        projectedPitch = Math.clamp(Mth.approach(fromPitch, pitch, step), -90f, 90f);
        projected = true;
    }

    // Settles who owns the view for this tick. Runs after every request.
    @Subscribe(priority = Integer.MIN_VALUE)
    private void onPreMotion(PreMotionEvent event) {
        boolean wasHolding = holding;
        holding = priority != null;
        rotationSent = false;

        if (holding) {
            heldYaw = projectedYaw;
            heldPitch = projectedPitch;
        } else if (wasHolding) {
            // The hold ended. The server is told where the view really points.
            heldYaw = MC.player.getYRot();
            heldPitch = MC.player.getXRot();
        }

        writeRotation = holding || wasHolding;
        priority = null;
        projected = false;
    }

    /**
     * Puts the held angle on the movement packet vanilla was going to send
     * anyway. Runs last and takes over only the angle.
     */
    @Subscribe(priority = Integer.MIN_VALUE)
    private void onPacketSend(PacketSendEvent event) {
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket move)) {
            return;
        }
        if (event.isCancelled()) {
            // A module is holding the movement packets back.
            rotationSent = true;
            return;
        }
        LocalPlayer player = MC.player;
        if (player == null) {
            return;
        }
        if (writeRotation && !carries(move, heldYaw, heldPitch)) {
            move = PacketUtil.withRotation(move, player, heldYaw, heldPitch);
            event.setPacket(move);
        }
        if (move.hasRotation()) {
            serverYaw = move.getYRot(player.getYRot());
            serverPitch = move.getXRot(player.getXRot());
            rotationSent = true;
        }
    }

    // Sends the angle on its own for ticks where vanilla sent nothing.
    @Subscribe(priority = Integer.MIN_VALUE)
    private void onPostMotion(PostMotionEvent event) {
        if (writeRotation && !rotationSent && MC.player != null) {
            MC.player.connection.send(new ServerboundMovePlayerPacket.Rot(
                heldYaw, heldPitch, MC.player.onGround(), MC.player.horizontalCollision));
        }
        writeRotation = false;
    }

    private static boolean carries(ServerboundMovePlayerPacket move, float yaw, float pitch) {
        return move.hasRotation() && move.getYRot(0) == yaw && move.getXRot(0) == pitch;
    }
}
