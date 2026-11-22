package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.MovementUtil;
import com.jellypudding.offlineclient.util.PacketUtil;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

// Solid mode makes liquid underfoot solid via BlockCollisionsMixin.
// Dolphin mode pushes the player up each tick.
public final class Jesus extends Module {

    public enum Mode { SOLID, DOLPHIN }
    public enum Bypass { WOBBLE, NONE }
    private enum Liquid { NONE, WATER, LAVA }

    private static final double RISE_SPEED = 0.11;
    private static final double DOLPHIN_LIFT = 0.04;

    // Any count past the two ticks of a lift out of the liquid. No lift is due.
    private static final int SETTLED = 2;
    private static final double PACKET_WOBBLE = 0.05;

    private static final int SHORT_RESISTANCE_TICKS = 15 * SharedConstants.TICKS_PER_SECOND;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the liquid holds you.", Mode.SOLID)
        .describe(Mode.SOLID, "Lets you stand and walk on the surface.")
        .describe(Mode.DOLPHIN, "Keeps you swimming along the top instead.");
    private final BoolSetting water = new BoolSetting("Water",
        "Lets you walk on water.", true)
        .under(mode, Mode.SOLID);
    private final BoolSetting dipIfBurning = new BoolSetting("Dip when burning",
        "Lets you drop into water whilst you are on fire to put it out.", true)
        .under(water);
    private final BoolSetting sneakToDip = new BoolSetting("Sneak to dip",
        "Holding sneak sinks you into the water.", true)
        .under(water);
    private final NumberSetting dipFall = new NumberSetting("Dip fall",
        "A fall further than this into water sinks you instead of landing. 0 always catches you.",
        4, 0, 20, 0.5, " blocks")
        .under(water);
    private final BoolSetting lava = new BoolSetting("Lava",
        "Also lets you walk on lava.", false)
        .under(mode, Mode.SOLID);
    private final BoolSetting dipIfResistant = new BoolSetting("Dip when resistant",
        "Lets you sink into lava whilst Fire Resistance has over 15 seconds left.", true)
        .under(lava);
    private final BoolSetting lavaSneakToDip = new BoolSetting("Lava sneak to dip",
        "Holding sneak sinks you into the lava.", true)
        .under(lava);
    private final NumberSetting lavaDipFall = new NumberSetting("Lava dip fall",
        "A fall further than this into lava sinks you instead of landing. 0 always catches you.",
        0, 0, 20, 0.5, " blocks")
        .under(lava);
    private final BoolSetting climbOn = new BoolSetting("Climb on",
        "Liquid level with your feet holds you as well. Walking into it lifts you on top.", true)
        .under(mode, Mode.SOLID);
    private final BoolSetting riseToSurface = new BoolSetting("Rise to surface",
        "Pushes you up out of the liquid when you are in it. Off lets you swim about as normal.", true)
        .under(mode, Mode.SOLID);
    private final BoolSetting riding = new BoolSetting("Whilst riding",
        "Also holds whatever you ride on the surface. Boats on water and striders on lava are left alone.", true)
        .under(mode, Mode.SOLID);
    private final EnumSetting<Bypass> bypass = new EnumSetting<>("Server bypass",
        "How the packets hide the walk from an anti cheat.", Bypass.WOBBLE)
        .describe(Bypass.WOBBLE, "Makes standing on liquid look like bobbing to the server.")
        .describe(Bypass.NONE, "Sends the packets as they are.")
        .under(mode, Mode.SOLID);
    private final BoolSetting powderSnow = new BoolSetting("Powder snow",
        "Also lets you walk on powder snow.", true);

    private int ticksSinceExit = SETTLED;

    private boolean lifting;
    private double liftX;
    private double liftZ;

    public Jesus() {
        super("Jesus", "Lets you walk on water and lava.", Category.MOVEMENT);
        addSettings(mode, water, dipIfBurning, sneakToDip, dipFall, lava, dipIfResistant,
            lavaSneakToDip, lavaDipFall, climbOn, riseToSurface, riding, bypass, powderSnow);
        searchTags("water walking", "waterwalk", "lava walking");
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onEnable() {
        ticksSinceExit = SETTLED;
        lifting = false;
    }

    // Read by Step. Nothing else may move the player on the tick of a lift.
    public boolean lifting() {
        return isEnabled() && lifting;
    }

    private boolean active() {
        LocalPlayer player = mc.player;
        return player != null && mc.level != null && active(player);
    }

    // The packet thread holds its own reference. A second read can come back null.
    private boolean active(LocalPlayer player) {
        if (player.isSpectator() || player.getAbilities().flying) {
            return false;
        }
        return !player.isPassenger() || riding.isOn();
    }

    private Entity mover(LocalPlayer player) {
        Entity vehicle = player.getVehicle();
        return vehicle != null && riding.isOn() ? vehicle : player;
    }

    private boolean sneakingToDip() {
        return sneakToDip.isOn() && mc.options.keyShift.isDown();
    }

    private static boolean fallenPast(LocalPlayer player, NumberSetting limit) {
        return limit.getValue() > 0 && player.fallDistance > limit.getValue();
    }

    private boolean waterWanted() {
        LocalPlayer player = mc.player;
        if (!mode.is(Mode.SOLID) || !water.isOn() || !active()) {
            return false;
        }
        if (player.getVehicle() instanceof AbstractBoat) {
            return false;
        }
        if (dipIfBurning.isOn() && player.isOnFire()) {
            return false;
        }
        return !sneakingToDip() && !fallenPast(player, dipFall);
    }

    private boolean lavaWanted() {
        LocalPlayer player = mc.player;
        if (!mode.is(Mode.SOLID) || !lava.isOn() || !active()) {
            return false;
        }
        if (player.getVehicle() instanceof Strider) {
            return false;
        }
        if (dipIfResistant.isOn() && longFireResistance(player)) {
            return false;
        }
        if (lavaSneakToDip.isOn() && mc.options.keyShift.isDown()) {
            return false;
        }
        return !fallenPast(player, lavaDipFall);
    }

    private static boolean longFireResistance(LocalPlayer player) {
        MobEffectInstance effect = player.getEffect(MobEffects.FIRE_RESISTANCE);
        if (effect == null) {
            return false;
        }
        if (effect.isInfiniteDuration()) {
            return true;
        }
        return effect.getDuration() > SHORT_RESISTANCE_TICKS
            * player.getAttributeValue(Attributes.BURNING_TIME);
    }

    // Read by PowderSnowBlockMixin.
    public boolean walksOnPowderSnow() {
        return isEnabled() && powderSnow.isOn() && active() && !sneakingToDip()
            && !fallenPast(mc.player, dipFall);
    }

    // Called for every block the player or the ridden vehicle collides with.
    public VoxelShape adjustShape(Entity entity, BlockState state, BlockPos pos, VoxelShape original) {
        if (!isEnabled() || mc.player == null) {
            return original;
        }
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) {
            return original;
        }
        Entity mover = mover(mc.player);
        if (entity != mover || pos.getY() > highestSolidLevel(mover)) {
            return original;
        }
        if (fluid.is(FluidTags.WATER) && waterWanted() && !mover.isInWater()) {
            return Shapes.block();
        }
        if (fluid.is(FluidTags.LAVA) && lavaWanted() && !mover.isInLava()) {
            return Shapes.block();
        }
        return original;
    }

    // Liquid below the feet is solid. Climb on also solidifies liquid at foot level.
    // The wall must stay up through the jump. The player then lands on top.
    private double highestSolidLevel(Entity mover) {
        double feet = mover.getY();
        if (climbOn.isOn() && !mover.isInWater() && !mover.isInLava()) {
            return Math.floor(feet);
        }
        return feet - 1;
    }

    // A jump or a step clears the edge on a slant. The sweep of your feet then
    // passes through the liquid inside the block and lava sets you alight. The
    // lift goes straight up and the move across waits for the next tick.
    @Subscribe(priority = 10)
    private void onClimbTick(TickEvent event) {
        lifting = false;
        if (!mode.is(Mode.SOLID) || !climbOn.isOn() || !active()) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player.isPassenger() || !player.onGround() || !player.horizontalCollision
            || player.isInWater() || player.isInLava()) {
            return;
        }
        Vec3 heading = MovementUtil.inputDirection();
        if (heading.lengthSqr() == 0) {
            return;
        }
        double rise = MovementUtil.ledgeRise(player, heading);
        if (rise <= 0 || rise > 1 + MovementUtil.LEDGE_CLEARANCE || !heldLiquidAhead(player, heading)) {
            return;
        }
        liftX = player.getX();
        liftZ = player.getZ();
        player.setPos(liftX, player.getY() + rise + MovementUtil.LEDGE_CLEARANCE, liftZ);
        player.setDeltaMovement(Vec3.ZERO);
        lifting = true;
    }

    // The server sweeps from the last packet to this one. Anything this tick
    // carried sideways is undone before the packet goes out.
    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (!lifting || !inGame()) {
            return;
        }
        lifting = false;
        mc.player.setPos(liftX, mc.player.getY(), liftZ);
        mc.player.setDeltaMovement(Vec3.ZERO);
    }

    private boolean heldLiquidAhead(LocalPlayer player, Vec3 heading) {
        AABB probe = MovementUtil.ledgeProbe(player, heading);
        int feet = Mth.floor(player.getY());
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(probe.minX), feet, Mth.floor(probe.minZ),
            Mth.floor(probe.maxX), feet, Mth.floor(probe.maxZ))) {
            FluidState fluid = mc.level.getFluidState(pos);
            if ((fluid.is(FluidTags.WATER) && waterWanted()) || (fluid.is(FluidTags.LAVA) && lavaWanted())) {
                return true;
            }
        }
        return false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!active() || lifting) {
            return;
        }
        if (mode.is(Mode.DOLPHIN)) {
            dolphinTick();
            return;
        }
        Entity mover = mover(mc.player);
        Vec3 velocity = mover.getDeltaMovement();

        // The column carries the player on its own. Jump adds a push and nothing fights it.
        if (mover.getInBlockState().is(Blocks.BUBBLE_COLUMN)) {
            if (mc.options.keyJump.isDown() && velocity.y < RISE_SPEED) {
                mover.setDeltaMovement(velocity.x, RISE_SPEED, velocity.z);
            }
            ticksSinceExit = SETTLED;
            return;
        }

        boolean inSolidLiquid = (mover.isInWater() && waterWanted())
            || (mover.isInLava() && lavaWanted());

        if (inSolidLiquid) {
            // Swimming stays vanilla until the player climbs out on their own.
            if (!riseToSurface.isOn()) {
                ticksSinceExit = SETTLED;
                return;
            }
            mover.setDeltaMovement(velocity.x, RISE_SPEED, velocity.z);
            ticksSinceExit = 0;
            return;
        }
        // One more push clears the surface and then gravity sets the player down.
        if (ticksSinceExit == 0) {
            mover.setDeltaMovement(velocity.x, RISE_SPEED, velocity.z);
        } else if (ticksSinceExit == 1 && liquidBelow(mover)) {
            mover.setDeltaMovement(velocity.x, 0, velocity.z);
        }
        ticksSinceExit++;
    }

    private void dolphinTick() {
        if (mc.player.isShiftKeyDown() || !mc.player.isInWater()) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(velocity.x, velocity.y + DOLPHIN_LIFT, velocity.z);
    }

    private boolean liquidBelow(Entity mover) {
        return !mc.level.getFluidState(mover.blockPosition().below()).isEmpty();
    }

    private boolean overWantedLiquid(LocalPlayer player, ClientLevel level) {
        return switch (standingOnLiquid(mover(player), level)) {
            case WATER -> waterWanted();
            case LAVA -> lavaWanted();
            case NONE -> false;
        };
    }

    private static Liquid standingOnLiquid(Entity mover, ClientLevel level) {
        AABB slice = mover.getBoundingBox().move(0, -0.01, 0);
        Liquid found = Liquid.NONE;
        for (BlockPos pos : BlockPos.betweenClosed(
            (int) Math.floor(slice.minX), (int) Math.floor(slice.minY), (int) Math.floor(slice.minZ),
            (int) Math.floor(slice.maxX), (int) Math.floor(slice.minY), (int) Math.floor(slice.maxZ))) {
            BlockState state = level.getBlockState(pos);
            FluidState fluid = state.getFluidState();
            if (fluid.is(FluidTags.WATER)) {
                found = Liquid.WATER;
            } else if (fluid.is(FluidTags.LAVA)) {
                found = Liquid.LAVA;
            } else if (!state.isAir()) {
                return Liquid.NONE;
            }
        }
        return found;
    }

    // Nothing in vanilla hovers at exactly block height over water.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (bypass.is(Bypass.NONE) || !mode.is(Mode.SOLID)) {
            return;
        }
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket packet)
            || PacketUtil.isGroundClaim(packet)) {
            return;
        }
        // The packet thread can lose the player mid handler.
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || !active(player) || player.isPassenger()) {
            return;
        }
        if (!packet.hasPosition() || !player.onGround() || player.isInWater() || player.isInLava()
            || !overWantedLiquid(player, level)) {
            return;
        }
        double x = packet.getX(player.getX());
        double y = packet.getY(player.getY());
        double z = packet.getZ(player.getZ());
        y += player.tickCount % 2 == 0 ? PACKET_WOBBLE : -PACKET_WOBBLE;
        event.setPacket(PacketUtil.withPosition(packet, player, x, y, z, true));
    }
}
