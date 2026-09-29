package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.function.BooleanSupplier;

// The one owner of the rotation the server sees. Exactly one rotation rides
// the vanilla movement packet each tick and the player's own view never moves.
public final class RotationManager {

    public static final RotationManager INSTANCE = new RotationManager();

    // The most degrees a managed rotation moves in one tick.
    public static final float STEP = 45f;

    public static final float BLOCK_TOLERANCE = 3f;

    // Entities move every tick and the angle the server holds is always stale.
    public static final float ENTITY_TOLERANCE = 25f;

    // A step that reaches any angle in one tick.
    public static final float NO_STEP = 360f;

    private static final Minecraft MC = OfflineClient.MC;

    // The winning request of the current tick. Null once it has been taken.
    private RotationPriority priority;

    private boolean holding;

    // The angle this tick has already settled on. isFacing reads it. A caller
    // can then act on the same tick it asks to turn.
    private boolean projected;
    private float projectedYaw;
    private float projectedPitch;

    // A yaw and pitch published together. The packet thread never sees half a tick.
    private record Angle(float yaw, float pitch) {
    }

    // Touched by the packet send hook on whichever thread sent the packet.
    // Every field the hook reaches has to be visible from that thread.
    private volatile Angle held = new Angle(0, 0);
    private volatile boolean writeRotation;
    private volatile float serverYaw;
    private volatile float serverPitch;
    private volatile boolean rotationSent;

    // The ground and wall flags the server last heard. A look packet sent from here repeats
    // them and changes nothing but the angle. A crit Criticals set up survives it.
    private volatile boolean sentOnGround;
    private volatile boolean sentCollision;

    // True only whilst a glance packet goes out. The hook lets that one through as it is.
    private volatile boolean glancing;

    // A glance left the server facing away from the view. The next rotation sent clears it. A
    // glance held back by another module gets its look back queued behind it.
    private volatile boolean lookBackDue;

    // A use packet turned the server to face along it. The next tick checks it against
    // the view once any module has turned the view back.
    private volatile boolean turnedByUse;

    // The player the server angle was last seeded from.
    private LocalPlayer seededFor;

    private RotationManager() {
    }

    // Asks for an angle this tick. The turn is spread over several ticks when
    // the target is far from the angle the server holds.
    public static void request(float yaw, float pitch, RotationPriority priority) {
        INSTANCE.take(yaw, pitch, priority, STEP);
    }

    // Asks for an angle that has to arrive whole. Only for places where the
    // exact number changes the outcome such as bed direction.
    public static void requestExact(float yaw, float pitch, RotationPriority priority) {
        INSTANCE.take(yaw, pitch, priority, NO_STEP);
    }

    // Asks for an angle at a chosen turn rate in degrees per tick. Used where a
    // module exposes its own rotation speed.
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

    public static boolean isFacing(float yaw, float pitch, float tolerance) {
        float heldNow = INSTANCE.projected ? INSTANCE.projectedYaw : INSTANCE.serverYaw;
        float pitchNow = INSTANCE.projected ? INSTANCE.projectedPitch : INSTANCE.serverPitch;
        return Mth.degreesDifferenceAbs(heldNow, yaw) <= tolerance
            && Math.abs(pitchNow - pitch) <= tolerance;
    }

    // True once the angle has really reached the server. Only for placements whose
    // outcome depends on the yaw the server holds such as a bed. Others want isFacing.
    public static boolean sentIsFacing(float yaw, float pitch, float tolerance) {
        return Mth.degreesDifferenceAbs(INSTANCE.serverYaw, yaw) <= tolerance
            && Math.abs(INSTANCE.serverPitch - pitch) <= tolerance;
    }

    // The angle the server last heard. The camera itself whilst nothing is held.
    public static float serverYaw() {
        return INSTANCE.serverYaw;
    }

    public static float serverPitch() {
        return INSTANCE.serverPitch;
    }

    public static float yawTo(Vec3 point) {
        return yawBetween(MC.player.getEyePosition(), point);
    }

    // Looking straight at the feet. The steepest pitch the game allows either way.
    public static final float STRAIGHT_DOWN = 90f;

    public static float clampPitch(float pitch) {
        return Math.clamp(pitch, -STRAIGHT_DOWN, STRAIGHT_DOWN);
    }

    public static float pitchTo(Vec3 point) {
        return pitchBetween(MC.player.getEyePosition(), point);
    }

    private static float yawBetween(Vec3 from, Vec3 to) {
        return (float) Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90f;
    }

    private static float pitchBetween(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return clampPitch((float) -Math.toDegrees(Math.atan2(to.y - from.y, horizontal)));
    }

    // Turns the view for one call and back. An item used inside raycasts from
    // that angle and its packet carries it to the server.
    public static boolean whileFacing(float yaw, float pitch, BooleanSupplier action) {
        LocalPlayer player = MC.player;
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        player.setYRot(yaw);
        player.setXRot(pitch);
        try {
            return action.getAsBoolean();
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
    }

    // Turns the real view towards the point by at most the step on each axis.
    public static void turnCamera(Vec3 point, float step) {
        LocalPlayer player = MC.player;
        player.setYRot(Mth.approachDegrees(player.getYRot(), yawTo(point), step));
        player.setXRot(clampPitch(Mth.approach(player.getXRot(), pitchTo(point), step)));
    }

    // Turns the view within a frame the way the mouse does. The frame being drawn shows the
    // new angle at once where turnCamera eases into it over the next tick.
    public static void turnFrame(Vec3 point, float partialTicks, float step) {
        LocalPlayer player = MC.player;
        Vec3 eye = player.getEyePosition(partialTicks);
        float yawTurn = Math.clamp(
            Mth.wrapDegrees(yawBetween(eye, point) - player.getViewYRot(partialTicks)), -step, step);
        float pitchTurn = Math.clamp(pitchBetween(eye, point) - player.getViewXRot(partialTicks), -step, step);
        player.turn(yawTurn / InputUtil.MOUSE_TURN, pitchTurn / InputUtil.MOUSE_TURN);
    }

    // Faces the server one way for the packets that follow without moving the view. The angle
    // it should hold goes back out on the next movement packet or at the end of the tick.
    public static void glance(float yaw, float pitch) {
        LocalPlayer player = MC.player;
        if (player == null) {
            return;
        }
        INSTANCE.glancing = true;
        try {
            INSTANCE.sendLook(player, Mth.wrapDegrees(yaw), clampPitch(pitch));
        } finally {
            INSTANCE.glancing = false;
        }
        INSTANCE.lookBackDue = true;
    }

    // Ties go to the first caller.
    private void take(float yaw, float pitch, RotationPriority asked, float step) {
        if (MC.player == null || (priority != null && !asked.beats(priority))) {
            return;
        }
        priority = asked;

        Angle from = held;
        float fromYaw = holding ? from.yaw() : MC.player.getYRot();
        float fromPitch = holding ? from.pitch() : MC.player.getXRot();
        projectedYaw = Mth.wrapDegrees(Mth.approachDegrees(fromYaw, yaw, step));
        projectedPitch = clampPitch(Mth.approach(fromPitch, pitch, step));
        projected = true;
    }

    // Settles who owns the view for this tick. Runs after every request.
    @Subscribe(priority = Subscribe.LAST)
    private void onPreMotion(PreMotionEvent event) {
        if (MC.player != seededFor) {
            // A fresh player has sent nothing yet. Its view is what the server assumes.
            seededFor = MC.player;
            serverYaw = MC.player.getYRot();
            serverPitch = MC.player.getXRot();
            sentOnGround = MC.player.onGround();
            sentCollision = MC.player.horizontalCollision;
            lookBackDue = false;
        }
        boolean wasHolding = holding;
        holding = priority != null;
        rotationSent = false;
        if (turnedByUse) {
            turnedByUse = false;
            lookBackDue |= !facesView();
        }

        if (holding) {
            held = new Angle(projectedYaw, projectedPitch);
        } else if (wasHolding || lookBackDue) {
            // The hold ended or a glance looked away. The server hears where the view points.
            held = viewAngle();
        }

        writeRotation = holding || wasHolding || lookBackDue;
        priority = null;
        projected = false;
    }

    // Puts the held angle on the movement packet vanilla was going to send anyway. Runs last
    // and takes over only the angle. A packet another module held back or dropped never gets
    // here and the angle then goes out on its own after the movement packet.
    @Subscribe(priority = Subscribe.LAST)
    private void onPacketSend(PacketSendEvent event) {
        if (event.getPacket() instanceof ServerboundUseItemPacket use) {
            // The server faces along every use packet it takes.
            serverYaw = use.yRot();
            serverPitch = use.xRot();
            turnedByUse = true;
            return;
        }
        LocalPlayer player = MC.player;
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket move) || player == null) {
            return;
        }
        if (glancing) {
            // A glance goes out as it is.
            note(move, player);
            return;
        }
        Angle angle = held;
        if (writeRotation && !carries(move, angle.yaw(), angle.pitch())) {
            move = PacketUtil.withRotation(move, player, angle.yaw(), angle.pitch());
            event.setPacket(move);
        }
        note(move, player);
        if (move.hasRotation()) {
            rotationSent = true;
            lookBackDue = false;
        }
    }

    // Sends the angle on its own for ticks where the movement packet did not carry it and after
    // a glance that came once the movement packet had gone. When the gate cut the tick's own
    // packet after a burst the look waits for a tick with room for it.
    @Subscribe(priority = Subscribe.LAST)
    private void onPostMotion(PostMotionEvent event) {
        if (MC.player != null && !MoveGate.droppedThisTick() && (writeRotation && !rotationSent || lookBackDue)) {
            Angle angle = holding ? held : viewAngle();
            sendLook(MC.player, angle.yaw(), angle.pitch());
            lookBackDue = false;
        }
        writeRotation = false;
    }

    private void sendLook(LocalPlayer player, float yaw, float pitch) {
        player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, sentOnGround, sentCollision));
    }

    // Keeps what the server holds. A packet without a rotation leaves the angle alone.
    private void note(ServerboundMovePlayerPacket move, LocalPlayer player) {
        sentOnGround = move.isOnGround();
        sentCollision = move.horizontalCollision();
        if (move.hasRotation()) {
            serverYaw = move.getYRot(player.getYRot());
            serverPitch = move.getXRot(player.getXRot());
        }
    }

    private static Angle viewAngle() {
        return new Angle(MC.player.getYRot(), MC.player.getXRot());
    }

    private boolean facesView() {
        return Mth.degreesDifferenceAbs(serverYaw, MC.player.getYRot()) == 0 && serverPitch == MC.player.getXRot();
    }

    private static boolean carries(ServerboundMovePlayerPacket move, float yaw, float pitch) {
        return move.hasRotation() && move.getYRot(0) == yaw && move.getXRot(0) == pitch;
    }
}
