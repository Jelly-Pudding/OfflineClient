package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.event.events.VehicleTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ItemSteerable;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.animal.nautilus.AbstractNautilus;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// Flies and speeds whatever you ride since vehicles use a code path Flight cannot reach.
// Only a steerable vehicle can be driven and speed is set right before its tick.
public final class VehicleFly extends Module {

    public enum Mode { CONTROL, GLIDE }

    // A drop longer than this starts to hurt.
    private static final double SAFE_DROP = 3;

    // The physics never move a vehicle this far in one tick. The server did.
    private static final double RESYNC_DISTANCE = 4;

    // The server banks every drop a vehicle packet makes. Only a ground flag wipes it.
    // A tick with a big drop is sent as several packets so none crosses the hurt line.
    private static final double SAFE_PACKET_DROP = 2.5;

    private static final double TICKS_PER_SECOND = 20;

    private final RegistryListSetting<EntityType<?>> vehicles = new RegistryListSetting<>("Vehicles",
        "Which kinds of vehicle the module drives.", BuiltInRegistries.ENTITY_TYPE, rideableTypes());
    private final BoolSetting fly = new BoolSetting("Fly",
        "Lifts the vehicle with jump and lowers it with sprint.", true);
    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How much the flight takes over.", Mode.CONTROL)
        .describe(Mode.CONTROL, "Steers the vehicle from your keys.")
        .describe(Mode.GLIDE, "Only holds the vehicle up and leaves the steering alone.")
        .under(fly);
    private final NumberSetting flySpeed = new NumberSetting("Horizontal speed",
        "How hard your keys push the vehicle in flight. 1 matches creative flight.", 1, 0.1, 5, 0.1, "x")
        .min(0.1).max(20).under(mode, Mode.CONTROL);
    private final BoolSetting instantStop = new BoolSetting("Instant stop",
        "The vehicle stops dead the moment you let go of the keys. Off lets it coast to a halt.", true)
        .under(mode, Mode.CONTROL);
    private final NumberSetting verticalSpeed = new NumberSetting("Vertical speed",
        "Up and down speed with jump climbing and sprint sinking.", 1, 0.1, 5, 0.1, "x")
        .min(0.1).max(20).under(fly);
    private final NumberSetting fallSpeed = new NumberSetting("Fall speed",
        "Blocks a second the vehicle sinks whilst no key is held. 0 holds the height.", 0, 0, 10, 0.1, " blocks")
        .min(0).under(fly);
    private final BoolSetting fallProtection = new BoolSetting("Fall protection",
        "Tells the server the mount is on the ground. A landing after a long flight then never hurts it.", true)
        .under(fly);
    private final BoolSetting dismountSafety = new BoolSetting("Dismount safety",
        "Swallows the dismount key whilst the vehicle is too high to step off.", true)
        .under(fly);
    private final BoolSetting speed = new BoolSetting("Speed",
        "Drives the vehicle along the ground at a set speed. It takes over the steering from flight too.", false);
    private final NumberSetting groundSpeed = new NumberSetting("Ground speed",
        "Blocks a second the vehicle moves under speed.", 10, 0, 50, 0.5, " blocks")
        .min(0).under(speed);
    private final BoolSetting onlyOnGround = new BoolSetting("Only on ground",
        "Speed only applies whilst the vehicle stands on a block or is a flying kind.", false)
        .under(speed);
    private final BoolSetting inWater = new BoolSetting("In water",
        "Speed also applies whilst the vehicle is in water.", true)
        .under(speed);
    private final BoolSetting faceView = new BoolSetting("Face view",
        "Turns the vehicle to match where you are looking.", true);
    private final BoolSetting maxJump = new BoolSetting("Max jump",
        "A horse jump is always at full power without holding the key to charge it.", true);
    private final BoolSetting spoofSaddle = new BoolSetting("Spoof saddle",
        "Makes the game treat every mount as saddled. Only older servers accept the steering.", false);
    private final BoolSetting ignoreServerMoves = new BoolSetting("Ignore server moves",
        "Drops every vehicle position the server sends. A refused move then leaves you out of sync.", false);

    private boolean holding;
    private double holdY;

    // Read from the packet thread.
    private volatile boolean blockDismount;
    private volatile boolean protecting;
    // Height of the last vehicle packet. Not a number until one has gone out.
    private volatile double lastSentY = Double.NaN;
    // Packets sent by the split come straight back through the send hook.
    private boolean splitting;

    public VehicleFly() {
        super("VehicleFly", "Flies or speeds the boat or mount you are riding.", Category.MOVEMENT);
        addSettings(vehicles, fly, mode, flySpeed, instantStop, verticalSpeed, fallSpeed,
            fallProtection, dismountSafety, speed, groundSpeed, onlyOnGround, inWater, faceView,
            maxJump, spoofSaddle, ignoreServerMoves);
        searchTags("boat fly", "vehicle fly", "horse fly", "entity control", "entity speed",
            "minecart", "strider", "camel", "pig");
    }

    // Every kind a player can steer. Minecarts run on rails and llamas cannot be steered.
    private static List<EntityType<?>> rideableTypes() {
        return BuiltInRegistries.ENTITY_TYPE.stream().filter(type -> {
            Class<?> base = type.getBaseClass();
            if (Llama.class.isAssignableFrom(base)) {
                return false;
            }
            return AbstractBoat.class.isAssignableFrom(base)
                || AbstractHorse.class.isAssignableFrom(base)
                || ItemSteerable.class.isAssignableFrom(base)
                || HappyGhast.class.isAssignableFrom(base)
                || AbstractNautilus.class.isAssignableFrom(base);
        }).toList();
    }

    @Override
    public String getSuffix() {
        if (fly.isOn()) {
            return speed.isOn() ? mode.getValueString() + " and speed" : mode.getValueString();
        }
        return speed.isOn() ? "speed" : null;
    }

    @Override
    protected void onEnable() {
        stop();
    }

    @Override
    protected void onDisable() {
        stop();
    }

    // Read by MobMixin. True whilst every mount should read as saddled.
    public boolean spoofsSaddle() {
        return isEnabled() && spoofSaddle.isOn();
    }

    // Read by LocalPlayerMixin. True whilst a horse jump should always be full power.
    public boolean maxesJump() {
        return isEnabled() && maxJump.isOn();
    }

    // Read by LocalPlayerMixin. Whilst flying a jumping mount the jump key lifts
    // instead of charging its jump.
    public boolean takesJumpKey() {
        if (!isEnabled() || !fly.isOn() || mc.player == null) {
            return false;
        }
        Entity vehicle = mc.player.getControlledVehicle();
        return vehicle instanceof PlayerRideableJumping && wanted(vehicle);
    }

    private boolean wanted(Entity vehicle) {
        return vehicles.contains(vehicle.getType());
    }

    private void stop() {
        holding = false;
        blockDismount = false;
        protecting = false;
        lastSentY = Double.NaN;
    }

    // The vehicle event only fires whilst riding. A dismount is noticed here.
    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || !mc.player.isPassenger()) {
            stop();
        }
    }

    @Subscribe
    private void onVehicleTick(VehicleTickEvent event) {
        Entity vehicle = event.getVehicle();
        // Vanilla only sends vehicle movement for a vehicle the client owns.
        if (!inGame() || mc.player.isSpectator() || !vehicle.isLocalInstanceAuthoritative()
            || !wanted(vehicle)) {
            stop();
            return;
        }

        Vec3 velocity = vehicle.getDeltaMovement();
        double vx = velocity.x;
        double vy = velocity.y;
        double vz = velocity.z;

        if (fly.isOn()) {
            blockDismount = dismountSafety.isOn() && dropIsUnsafe(vehicle);
            protecting = fallProtection.isOn();
            vy = flightLift(vehicle);
            if (mode.is(Mode.CONTROL)) {
                Vec3 heading = MovementUtil.inputDirection();
                if (heading.lengthSqr() > 0 || instantStop.isOn()) {
                    double h = flySpeed.getValue() * MovementUtil.FLY_HORIZONTAL;
                    vx = heading.x * h;
                    vz = heading.z * h;
                }
            }
        } else {
            stop();
        }

        if (speedApplies(vehicle)) {
            Vec3 heading = MovementUtil.inputDirection();
            double h = groundSpeed.getValue() / TICKS_PER_SECOND;
            vx = heading.x * h;
            vz = heading.z * h;
        }

        if (faceView.isOn()) {
            vehicle.setYRot(mc.player.getYRot());
        }
        vehicle.setDeltaMovement(vx, vy, vz);
    }

    private double flightLift(Entity vehicle) {
        double limit = verticalSpeed.getValue() * MovementUtil.FLY_VERTICAL;
        if (mc.options.keyJump.isDown()) {
            holding = false;
            return limit;
        }
        if (mc.options.keySprint.isDown()) {
            holding = false;
            return -limit;
        }
        if (fallSpeed.getValue() > 0) {
            holding = false;
            return -fallSpeed.getValue() / TICKS_PER_SECOND;
        }
        return holdHeight(vehicle, limit);
    }

    private boolean speedApplies(Entity vehicle) {
        if (!speed.isOn()) {
            return false;
        }
        if (onlyOnGround.isOn() && !vehicle.onGround() && !vehicle.isFlyingVehicle()) {
            return false;
        }
        return inWater.isOn() || !vehicle.isInWater();
    }

    // Corrects back towards the height the vehicle was left at.
    // Vehicle physics keep pulling down so a flat zero would sink.
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

    // The server moved the vehicle itself. The next drop is measured from there.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPacket() instanceof ClientboundMoveVehiclePacket)) {
            return;
        }
        if (ignoreServerMoves.isOn()) {
            event.cancel();
            return;
        }
        lastSentY = Double.NaN;
    }

    // Fired on whichever thread sent the packet.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (event.getPacket() instanceof ServerboundPlayerInputPacket packet) {
            guardDismount(event, packet);
        } else if (event.getPacket() instanceof ServerboundMoveVehiclePacket packet) {
            protectLanding(event, packet);
        }
    }

    // A shift flag whilst riding makes the server dismount the player.
    private void guardDismount(PacketSendEvent event, ServerboundPlayerInputPacket packet) {
        Input input = packet.input();
        if (!blockDismount || !input.shift()) {
            return;
        }
        event.setPacket(new ServerboundPlayerInputPacket(new Input(input.forward(), input.backward(),
            input.left(), input.right(), input.jump(), false, input.sprint())));
    }

    private void protectLanding(PacketSendEvent event, ServerboundMoveVehiclePacket packet) {
        if (splitting || !protecting) {
            return;
        }
        Vec3 pos = packet.position();
        double from = lastSentY;
        lastSentY = pos.y;
        if (packet.onGround()) {
            return;
        }
        double drop = Double.isNaN(from) ? 0 : from - pos.y;
        if (drop > SAFE_PACKET_DROP && mc.player != null) {
            int steps = (int) Math.ceil(drop / SAFE_PACKET_DROP);
            splitting = true;
            try {
                for (int i = 1; i < steps; i++) {
                    Vec3 between = new Vec3(pos.x, from - drop * i / steps, pos.z);
                    mc.player.connection.send(new ServerboundMoveVehiclePacket(between,
                        packet.yRot(), packet.xRot(), true));
                }
            } finally {
                splitting = false;
            }
        }
        event.setPacket(new ServerboundMoveVehiclePacket(pos, packet.yRot(), packet.xRot(), true));
    }
}
