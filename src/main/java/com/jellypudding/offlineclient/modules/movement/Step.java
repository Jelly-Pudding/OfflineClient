package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class Step extends Module {

    public enum Mode { SIMPLE, LEGIT }

    public enum ActiveWhen { ALWAYS, SNEAKING, NOT_SNEAKING }

    // The two heights a real jump passes through on its way up one block.
    private static final double FIRST_JUMP_POINT = 0.42;
    private static final double SECOND_JUMP_POINT = 0.753;

    // The most a legit step can climb. Any higher is not a jump the server believes.
    private static final double LEGIT_LIMIT = 1;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the step is done.", Mode.SIMPLE)
        .describe(Mode.SIMPLE, "Raises the step height so you walk straight up. Any height.")
        .describe(Mode.LEGIT, "Sends the packets of a real jump so anti cheats see nothing odd. One block only.");
    private final NumberSetting height = new NumberSetting("Height",
        "How high you can step up without jumping.", 1, 0.6, 3, 0.1, " blocks")
        .under(mode, Mode.SIMPLE);
    private final EnumSetting<ActiveWhen> activeWhen = new EnumSetting<>("Active when",
        "When the step is allowed.", ActiveWhen.NOT_SNEAKING)
        .describe(ActiveWhen.ALWAYS, "Steps up sneaking or not.")
        .describe(ActiveWhen.SNEAKING, "Only steps up whilst you sneak.")
        .describe(ActiveWhen.NOT_SNEAKING, "Never steps up whilst you sneak.");
    private final BoolSetting safeStep = new BoolSetting("Safe step",
        "Stays in a hole when your health is low or a crystal is near.", false);
    private final NumberSetting safeHealth = new NumberSetting("Safe health",
        "Hearts at or below which the step stays off.", 5, 0.5, 10, 0.5, " hearts")
        .under(safeStep);
    private final BoolSetting stepDown = new BoolSetting("Step down",
        "Snaps you down small drops instead of falling them. EdgeGuard wins at any edge it is guarding.", false);
    private final NumberSetting downDistance = new NumberSetting("Down distance",
        "Longest drop that is snapped.", 3, 0.5, 10, 0.5, " blocks")
        .under(stepDown);
    private final NumberSetting downSpeed = new NumberSetting("Down speed",
        "How hard the snap pulls down.", 3, 0.5, 10, 0.5, "x")
        .under(stepDown);

    public Step() {
        super("Step", "Step up full blocks without jumping.", Category.MOVEMENT);
        addSettings(mode, height, activeWhen, safeStep, safeHealth, stepDown, downDistance, downSpeed);
        searchTags("reverse step", "step down");
    }

    @Override
    public String getSuffix() {
        return mode.is(Mode.LEGIT) ? "Legit" : height.getValueString();
    }

    // Called from LocalPlayerMixin.maxUpStep().
    public float adjustStepHeight(float vanilla) {
        if (!isEnabled() || mc.player == null || mode.is(Mode.LEGIT) || !allowed()) {
            return vanilla;
        }
        return Math.max(vanilla, height.getFloat());
    }

    private boolean allowed() {
        boolean sneaking = mc.player.isShiftKeyDown();
        if (activeWhen.is(ActiveWhen.SNEAKING) && !sneaking) {
            return false;
        }
        // The sneak edge check uses the step height as its drop probe.
        if (activeWhen.is(ActiveWhen.NOT_SNEAKING) && sneaking) {
            return false;
        }
        return !safeStep.isOn() || !inDanger();
    }

    private boolean inDanger() {
        float hearts = (mc.player.getHealth() + mc.player.getAbsorptionAmount()) / 2f;
        return hearts <= safeHealth.getFloat() || EntityUtil.crystalNearby(6);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isPassenger()) {
            return;
        }
        if (mode.is(Mode.LEGIT) && allowed()) {
            stepLegit();
        }
        if (stepDown.isOn()) {
            snapDown();
        }
    }

    /**
     * Walking into a block edge sends the two positions a jump would pass
     * through and then puts the player on top. The server sees a normal jump.
     */
    private void stepLegit() {
        if (!mc.player.horizontalCollision || !mc.player.onGround()) {
            return;
        }
        if (mc.player.onClimbable() || mc.player.isInWater() || mc.player.isInLava()
            || mc.player.input.keyPresses.jump()) {
            return;
        }
        if (mc.player.input.getMoveVector().lengthSquared() < 1.0E-6f) {
            return;
        }
        // The lip is probed just above the feet so a ledge under the box counts.
        AABB probe = mc.player.getBoundingBox().move(0, 0.05, 0).inflate(0.05);
        if (!mc.level.noCollision(mc.player, probe.move(0, 1, 0))) {
            return;
        }
        double top = Double.NEGATIVE_INFINITY;
        for (VoxelShape shape : mc.level.getBlockCollisions(mc.player, probe)) {
            top = Math.max(top, shape.bounds().maxY);
        }
        double rise = top - mc.player.getY();
        if (rise <= 0 || rise > LEGIT_LIMIT) {
            return;
        }
        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();
        boolean onGround = mc.player.onGround();
        boolean collided = mc.player.horizontalCollision;
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            x, y + FIRST_JUMP_POINT * rise, z, onGround, collided));
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            x, y + SECOND_JUMP_POINT * rise, z, onGround, collided));
        mc.player.setPos(x, y + rise, z);
    }

    private void snapDown() {
        if (!mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava()
            || mc.player.noPhysics || mc.options.keyJump.isDown()
            || mc.player.isShiftKeyDown()) {
            return;
        }
        if (mc.player.xxa == 0 && mc.player.zza == 0) {
            return;
        }
        // An edge EdgeGuard is holding you on is not one to snap down from.
        EdgeGuard edgeGuard = Modules.get(EdgeGuard.class);
        if (edgeGuard != null && edgeGuard.shouldGuard()) {
            return;
        }
        // A bed edge would bounce the snap straight back up.
        BlockPos feet = mc.player.blockPosition();
        if (BlockUtil.state(feet).getBlock() instanceof BedBlock
            || BlockUtil.state(feet.below()).getBlock() instanceof BedBlock) {
            return;
        }
        double drop = downDistance.getValue() + 0.01;
        if (mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(0, -drop, 0))) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(velocity.x, -downSpeed.getValue(), velocity.z);
    }
}
