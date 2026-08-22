package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
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
 * Walk on water and lava. Solid mode turns liquid underfoot into a
 * collision block through BlockCollisionsMixin while dolphin mode pushes
 * the player up each tick.
 */
public final class Jesus extends Module {

    public enum Mode {
        SOLID("Solid"),
        DOLPHIN("Dolphin");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Upward speed while climbing out of the liquid. */
    private static final double RISE_SPEED = 0.11;
    /** Extra lift per tick while bobbing in dolphin mode. */
    private static final double DOLPHIN_LIFT = 0.04;
    /** How far the sent height wobbles above and below the real one. */
    private static final double PACKET_WOBBLE = 0.05;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Solid lets you stand on the surface. Dolphin keeps you swimming at the top.",
        Mode.SOLID);
    private final BoolSetting lava = new BoolSetting("Lava",
        "Also walk on lava. Only useful in solid mode.", false)
        .visibleWhen(() -> mode.is(Mode.SOLID));
    private final BoolSetting sneakToDip = new BoolSetting("Sneak to dip",
        "Hold sneak to sink into the liquid.", true)
        .visibleWhen(() -> mode.is(Mode.SOLID));
    private final NumberSetting dipFall = new NumberSetting("Dip fall",
        "Fall at least this far and you go under instead of landing on the surface. 0 turns this off.",
        4, 0, 20, 0.5, "m")
        .visibleWhen(() -> mode.is(Mode.SOLID));
    private final BoolSetting wobblePackets = new BoolSetting("Wobble packets",
        "Nudges the height sent to the server up and down while you stand on liquid so it looks like bobbing.",
        true)
        .visibleWhen(() -> mode.is(Mode.SOLID));

    /**
     * Ticks since the player last climbed out of the liquid. Two more
     * small pushes follow.
     */
    private int ticksSinceExit = 10;

    public Jesus() {
        super("Jesus", "Walk on water and lava.", Category.MOVEMENT);
        addSettings(mode, lava, sneakToDip, dipFall, wobblePackets);
        searchTags("water walking", "waterwalk", "lava walking");
    }

    @Override
    public String getSuffix() {
        return mode.getValue().toString();
    }

    @Override
    protected void onEnable() {
        ticksSinceExit = 10;
    }

    /** True while the module should change how the game treats liquid. */
    private boolean active() {
        if (!inGame() || mc.player.isSpectator() || mc.player.isPassenger()) {
            return false;
        }
        return !mc.player.getAbilities().flying;
    }

    /** True when the player wants to go under right now. */
    private boolean wantsToDip() {
        if (sneakToDip.isOn() && mc.options.keyShift.isDown()) {
            return true;
        }
        return dipFall.getValue() > 0 && mc.player.fallDistance > dipFall.getValue();
    }

    private boolean solidWater() {
        return mode.is(Mode.SOLID) && active() && !wantsToDip() && !mc.player.isInWater();
    }

    private boolean solidLava() {
        return mode.is(Mode.SOLID) && lava.isOn() && active() && !wantsToDip()
            && !mc.player.isInLava();
    }

    /**
     * Called for every block the player collides with. Liquid at or below
     * the feet becomes a full block. Liquid beside or above the player is
     * left alone.
     */
    public VoxelShape adjustShape(BlockState state, BlockPos pos, VoxelShape original) {
        if (!isEnabled() || mc.player == null) {
            return original;
        }
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty() || pos.getY() > mc.player.getY() - 1) {
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
            // Climb until the hitbox clears the surface.
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

    /** True if the block right under the feet is liquid. */
    private boolean liquidBelow() {
        return !mc.level.getFluidState(mc.player.blockPosition().below()).isEmpty();
    }

    /**
     * True when there is nothing but liquid in the slice just under the
     * hitbox. A single solid block there means real ground.
     */
    private boolean standingOnLiquid() {
        AABB slice = mc.player.getBoundingBox().move(0, -0.01, 0);
        boolean liquid = false;
        for (BlockPos pos : BlockPos.betweenClosed(
            (int) Math.floor(slice.minX), (int) Math.floor(slice.minY), (int) Math.floor(slice.minZ),
            (int) Math.floor(slice.maxX), (int) Math.floor(slice.minY), (int) Math.floor(slice.maxZ))) {
            BlockState state = mc.level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) {
                liquid = true;
            } else if (!state.isAir()) {
                return false;
            }
        }
        return liquid;
    }

    /**
     * The server sees a player hovering at exactly block height over water
     * which nothing in the game normally does. A tiny wobble each tick looks
     * like the bob of a swimmer instead.
     */
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!wobblePackets.isOn() || !mode.is(Mode.SOLID) || !active()) {
            return;
        }
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket packet) || !packet.hasPosition()) {
            return;
        }
        if (mc.player.isInWater() || mc.player.isInLava() || wantsToDip()
            || !mc.player.onGround() || !standingOnLiquid()) {
            return;
        }
        double x = packet.getX(mc.player.getX());
        double y = packet.getY(mc.player.getY());
        double z = packet.getZ(mc.player.getZ());
        y += mc.player.tickCount % 2 == 0 ? PACKET_WOBBLE : -PACKET_WOBBLE;
        boolean collision = packet.horizontalCollision();

        if (packet.hasRotation()) {
            float yaw = packet.getYRot(mc.player.getYRot());
            float pitch = packet.getXRot(mc.player.getXRot());
            event.setPacket(new ServerboundMovePlayerPacket.PosRot(x, y, z, yaw, pitch, true, collision));
        } else {
            event.setPacket(new ServerboundMovePlayerPacket.Pos(x, y, z, true, collision));
        }
    }
}
