package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.PacketUtil;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Solid mode turns liquid underfoot into a collision block through
 * BlockCollisionsMixin. Dolphin mode pushes the player up each tick.
 */
public final class Jesus extends Module {

    public enum Mode { SOLID, DOLPHIN }

    // Upward speed whilst climbing out of the liquid.
    private static final double RISE_SPEED = 0.11;
    // Extra lift per tick whilst bobbing in dolphin mode.
    private static final double DOLPHIN_LIFT = 0.04;

    // Ticks out of the water before the surface counts as solid again.
    private static final int SETTLED = 10;
    // How far the sent height wobbles above and below the real one.
    private static final double PACKET_WOBBLE = 0.05;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the liquid holds you.", Mode.SOLID)
        .describe(Mode.SOLID, "Lets you stand and walk on the surface.")
        .describe(Mode.DOLPHIN, "Keeps you swimming along the top instead.");
    private final BoolSetting lava = new BoolSetting("Lava",
        "Also walk on lava.", false)
        .under(mode, Mode.SOLID);
    private final BoolSetting climbOn = new BoolSetting("Climb on",
        "Liquid level with your feet counts as solid. You step or jump onto it instead of wading in.", true)
        .under(mode, Mode.SOLID);
    private final BoolSetting riseToSurface = new BoolSetting("Rise to surface",
        "Pushes you up out of the liquid when you are in it. Off lets you swim about as normal.", true)
        .under(mode, Mode.SOLID);
    private final BoolSetting sneakToDip = new BoolSetting("Sneak to dip",
        "Hold sneak to sink into the liquid.", true)
        .under(mode, Mode.SOLID);
    private final NumberSetting dipFall = new NumberSetting("Dip fall",
        "Fall further than this and you sink instead of landing.",
        4, 0, 20, 0.5, " blocks")
        .under(mode, Mode.SOLID);
    private final BoolSetting powderSnow = new BoolSetting("Powder snow",
        "Also walk on powder snow.", true);
    private final BoolSetting wobblePackets = new BoolSetting("Wobble packets",
        "Makes standing on liquid look like bobbing to the server.",
        true)
        .under(mode, Mode.SOLID);

    private int ticksSinceExit = SETTLED;

    public Jesus() {
        super("Jesus", "Walk on water and lava.", Category.MOVEMENT);
        addSettings(mode, lava, climbOn, riseToSurface, sneakToDip, dipFall, wobblePackets, powderSnow);
        searchTags("water walking", "waterwalk", "lava walking");
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onEnable() {
        ticksSinceExit = SETTLED;
    }

    private boolean active() {
        LocalPlayer player = mc.player;
        return player != null && mc.level != null && active(player);
    }

    // The packet thread holds its own reference. A second read can come back null.
    private boolean active(LocalPlayer player) {
        if (player.isSpectator() || player.isPassenger()) {
            return false;
        }
        return !player.getAbilities().flying;
    }

    private boolean wantsToDip() {
        if (sneakToDip.isOn() && mc.options.keyShift.isDown()) {
            return true;
        }
        LocalPlayer player = mc.player;
        return player != null && dipFall.getValue() > 0 && player.fallDistance > dipFall.getValue();
    }

    private boolean solidWater() {
        return mode.is(Mode.SOLID) && active() && !wantsToDip() && !mc.player.isInWater();
    }

    private boolean solidLava() {
        return mode.is(Mode.SOLID) && lava.isOn() && active() && !wantsToDip()
            && !mc.player.isInLava();
    }

    // Read by PowderSnowBlockMixin.
    public boolean walksOnPowderSnow() {
        return isEnabled() && powderSnow.isOn() && active() && !wantsToDip();
    }

    // Called for every block the player collides with.
    public VoxelShape adjustShape(BlockState state, BlockPos pos, VoxelShape original) {
        if (!isEnabled() || mc.player == null) {
            return original;
        }
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty() || pos.getY() > highestSolidLevel()) {
            return original;
        }
        if (fluid.is(FluidTags.WATER) && solidWater()) {
            return Shapes.block();
        }
        if (fluid.is(FluidTags.LAVA) && solidLava()) {
            return Shapes.block();
        }
        return original;
    }

    /**
     * Liquid below the feet is always solid. With Climb on the liquid level
     * with the feet is solid too whilst the player stands on dry ground so
     * they walk into a wall of it and step or jump on top.
     */
    private double highestSolidLevel() {
        double feet = mc.player.getY();
        if (climbOn.isOn() && mc.player.onGround() && !mc.player.isInWater() && !mc.player.isInLava()) {
            return Math.floor(feet);
        }
        return feet - 1;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!active()) {
            return;
        }
        if (mode.is(Mode.DOLPHIN)) {
            dolphinTick();
            return;
        }
        boolean inSolidLiquid = (mc.player.isInWater() && !wantsToDip())
            || (mc.player.isInLava() && lava.isOn() && !wantsToDip());
        Vec3 velocity = mc.player.getDeltaMovement();

        if (inSolidLiquid) {
            // Swimming stays vanilla until the player climbs out on their own.
            if (!riseToSurface.isOn()) {
                ticksSinceExit = SETTLED;
                return;
            }
            mc.player.setDeltaMovement(velocity.x, RISE_SPEED, velocity.z);
            ticksSinceExit = 0;
            return;
        }
        // One more push clears the surface and then gravity sets the player down.
        if (ticksSinceExit == 0) {
            mc.player.setDeltaMovement(velocity.x, RISE_SPEED, velocity.z);
        } else if (ticksSinceExit == 1 && liquidBelow()) {
            mc.player.setDeltaMovement(velocity.x, 0, velocity.z);
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

    private boolean liquidBelow() {
        return !mc.level.getFluidState(mc.player.blockPosition().below()).isEmpty();
    }

    private static boolean standingOnLiquid(LocalPlayer player, ClientLevel level) {
        AABB slice = player.getBoundingBox().move(0, -0.01, 0);
        boolean liquid = false;
        for (BlockPos pos : BlockPos.betweenClosed(
            (int) Math.floor(slice.minX), (int) Math.floor(slice.minY), (int) Math.floor(slice.minZ),
            (int) Math.floor(slice.maxX), (int) Math.floor(slice.minY), (int) Math.floor(slice.maxZ))) {
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) {
                liquid = true;
            } else if (!state.isAir()) {
                return false;
            }
        }
        return liquid;
    }

    // Nothing in vanilla hovers at exactly block height over water.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!wobblePackets.isOn() || !mode.is(Mode.SOLID)) {
            return;
        }
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket packet) || !packet.hasPosition()) {
            return;
        }
        // The packet thread can lose the player mid handler.
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || !active(player)) {
            return;
        }
        if (player.isInWater() || player.isInLava() || wantsToDip()
            || !player.onGround() || !standingOnLiquid(player, level)) {
            return;
        }
        double x = packet.getX(player.getX());
        double y = packet.getY(player.getY());
        double z = packet.getZ(player.getZ());
        y += player.tickCount % 2 == 0 ? PACKET_WOBBLE : -PACKET_WOBBLE;
        event.setPacket(PacketUtil.withPosition(packet, player, x, y, z, true));
    }
}
