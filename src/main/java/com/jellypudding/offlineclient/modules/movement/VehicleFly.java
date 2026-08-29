package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Flies whatever you are riding. Vehicles move on their own code path which
 * Flight never reaches. Only a vehicle the client may steer can be flown.
 */
public final class VehicleFly extends Module {

    public enum Mode { CONTROL, GLIDE }

    // Blocks per tick at a speed of one. The same pace as the Flight module.
    private static final double HORIZONTAL_UNIT = 0.5;
    private static final double VERTICAL_UNIT = 0.225;

    // A drop longer than this starts to hurt.
    private static final double SAFE_DROP = 3;

    // The physics never move a vehicle this far in one tick. The server did.
    private static final double RESYNC_DISTANCE = 4;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How much the module takes over.", Mode.CONTROL)
        .describe(Mode.CONTROL, "Steers the vehicle from your keys.")
        .describe(Mode.GLIDE, "Only holds the vehicle up and leaves the steering alone.");
    private final NumberSetting speed = new NumberSetting("Horizontal speed",
        "How hard your keys push the vehicle. 1 matches creative flight.", 1, 0.1, 5, 0.1, "x")
        .min(0.1).max(20).visibleWhen(() -> mode.is(Mode.CONTROL));
    private final NumberSetting verticalSpeed = new NumberSetting("Vertical speed",
        "Up and down speed with jump climbing and sprint sinking.", 1, 0.1, 5, 0.1, "x")
        .min(0.1).max(20);
    private final BoolSetting boats = new BoolSetting("Boats",
        "Fly boats and rafts.", true);
    private final BoolSetting mounts = new BoolSetting("Mounts",
        "Fly horses and striders and anything else alive.", true);
    private final BoolSetting others = new BoolSetting("Other vehicles",
        "Fly anything else the server lets you steer.", true);
    private final BoolSetting faceView = new BoolSetting("Face view",
        "Turns the vehicle to match where you are looking.", true)
        .visibleWhen(() -> mode.is(Mode.CONTROL));
    private final BoolSetting dismountSafety = new BoolSetting("Dismount safety",
        "Swallows the dismount key whilst the vehicle is too high to step off.", true);

    private boolean holding;
    private double holdY;

    // Read from the packet thread.
    private volatile boolean blockDismount;

    public VehicleFly() {
        super("VehicleFly", "Flies the boat or mount you are riding.", Category.MOVEMENT);
        addSettings(mode, speed, faceView, verticalSpeed, boats, mounts, others, dismountSafety);
        searchTags("boat fly", "vehicle fly", "horse fly", "entity control", "entity speed",
            "minecart", "strider", "camel", "pig");
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onEnable() {
        stop();
    }

    @Override
    protected void onDisable() {
        stop();
    }

    private boolean wanted(Entity vehicle) {
        if (vehicle instanceof AbstractBoat) {
            return boats.isOn();
        }
        if (vehicle instanceof Mob) {
            return mounts.isOn();
        }
        return others.isOn();
    }

    private void stop() {
        holding = false;
        blockDismount = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            stop();
            return;
        }
        Entity vehicle = mc.player.getRootVehicle();
        // Vanilla only sends vehicle movement for a vehicle the client owns.
        if (vehicle == mc.player || !vehicle.isLocalInstanceAuthoritative() || !wanted(vehicle)) {
            stop();
            return;
        }

        blockDismount = dismountSafety.isOn() && dropIsUnsafe(vehicle);

        double limit = verticalSpeed.getValue() * VERTICAL_UNIT;
        double vy;
        if (mc.options.keyJump.isDown()) {
            vy = limit;
            holding = false;
        } else if (mc.options.keySprint.isDown()) {
            vy = -limit;
            holding = false;
        } else {
            vy = holdHeight(vehicle, limit);
        }

        Vec3 velocity = vehicle.getDeltaMovement();
        double vx = velocity.x;
        double vz = velocity.z;
        if (mode.is(Mode.CONTROL)) {
            vx = 0;
            vz = 0;
            Vec3 heading = MovementUtil.inputDirection();
            double h = speed.getValue() * HORIZONTAL_UNIT;
            vx = heading.x * h;
            vz = heading.z * h;
            if (faceView.isOn()) {
                vehicle.setYRot(mc.player.getYRot());
            }
        }
        vehicle.setDeltaMovement(vx, vy, vz);
    }

    /**
     * Corrects back towards the height the vehicle was left at. Vehicle physics
     * keep pulling downwards and a flat zero would sink.
     */
    private double holdHeight(Entity vehicle, double limit) {
        if (!holding || Math.abs(holdY - vehicle.getY()) > RESYNC_DISTANCE) {
            holdY = vehicle.getY();
            holding = true;
        }
        double correction = holdY - vehicle.getY() + vehicle.getGravity();
        return Math.clamp(correction, -limit, limit);
    }

    private boolean dropIsUnsafe(Entity vehicle) {
        AABB box = vehicle.getBoundingBox();
        return mc.level.noCollision(vehicle, box.minmax(box.move(0, -SAFE_DROP, 0)));
    }

    // Fired on the netty thread. A shift flag whilst riding makes the server dismount the player.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!blockDismount || !(event.getPacket() instanceof ServerboundPlayerInputPacket packet)) {
            return;
        }
        Input input = packet.input();
        if (!input.shift()) {
            return;
        }
        event.setPacket(new ServerboundPlayerInputPacket(new Input(input.forward(), input.backward(),
            input.left(), input.right(), input.jump(), false, input.sprint())));
    }
}
