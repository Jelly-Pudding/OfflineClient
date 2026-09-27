package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Lies down in a bed in reach once the night or a thunderstorm allows it. The server wakes
// you at dawn and the module gets you up itself if it does not.
public final class AutoSleep extends Module {

    // How far from the bottom middle of either half the server lets you stand.
    private static final double BED_REACH = 3;
    private static final double BED_HEIGHT = 2;

    // Wide enough to take in every bed the server lets you reach.
    private static final double BED_SEARCH = 5;

    // The box around the head of the bed that must be free of monsters.
    private static final double MONSTER_WIDTH = 16;
    private static final double MONSTER_HEIGHT = 10;

    // Ticks to wait for a bed that was clicked before another try.
    private static final int RETRY_TICKS = 100;

    // Ticks after dawn the server gets to wake you before you get up yourself.
    private static final int WAKE_GRACE = 40;

    private final BoolSetting onlyStill = new BoolSetting("Only whilst still",
        "Waits until you let go of the movement keys before lying down.", true);

    // Set once you have slept this night. Getting up early keeps you up until the next one.
    private boolean rested;
    private int retryTicks;
    private int dawnTicks;

    public AutoSleep() {
        super("AutoSleep", "Sleeps in a bed near you at night and gets up in the morning.",
            Category.PLAYER);
        addSettings(onlyStill);
        searchTags("bed", "sleep", "night", "phantom");
    }

    @Override
    protected void onEnable() {
        rested = false;
        retryTicks = 0;
        dawnTicks = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || mc.player.isDeadOrDying()) {
            return;
        }
        if (retryTicks > 0) {
            retryTicks--;
        }
        boolean night = canSleepHere();
        if (mc.player.isSleeping()) {
            rested = true;
            getUpAtDawn(night);
            return;
        }
        dawnTicks = 0;
        if (!night) {
            rested = false;
            return;
        }
        if (rested || retryTicks > 0 || mc.gui.screen() != null || mc.player.isUsingItem()
            || onlyStill.isOn() && MovementUtil.hasInput()) {
            return;
        }
        BlockPos bed = BlockUtil.nearestWithin(BED_SEARCH, this::usable);
        if (bed != null && BlockUtil.interact(bed, BlockUtil.facingSide(bed))) {
            retryTicks = RETRY_TICKS;
        }
    }

    // Wherever beds do not blow up they work once it is dark.
    private boolean canSleepHere() {
        return !ExplosionUtil.bedsExplodeHere() && mc.level.isDarkOutside();
    }

    private void getUpAtDawn(boolean night) {
        if (night) {
            dawnTicks = 0;
        } else if (++dawnTicks == WAKE_GRACE) {
            mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player,
                ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
        }
    }

    // A free bed that the server would let you sleep in from where you stand. Straw beds are
    // left alone. They fall apart once you get up.
    private boolean usable(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        if (!(state.getBlock() instanceof BedBlock) || state.getValue(AbstractBedBlock.OCCUPIED)) {
            return false;
        }
        BlockPos other = pos.relative(AbstractBedBlock.getConnectedDirection(state));
        if (!withinReach(pos) && !withinReach(other)) {
            return false;
        }
        if (suffocates(pos.above()) || suffocates(other.above())) {
            return false;
        }
        return !monstersNear(state.getValue(AbstractBedBlock.PART) == BedPart.HEAD ? pos : other);
    }

    private boolean withinReach(BlockPos half) {
        Vec3 bottom = Vec3.atBottomCenterOf(half);
        return Math.abs(mc.player.getX() - bottom.x) <= BED_REACH
            && Math.abs(mc.player.getY() - bottom.y) <= BED_HEIGHT
            && Math.abs(mc.player.getZ() - bottom.z) <= BED_REACH;
    }

    private boolean suffocates(BlockPos pos) {
        return BlockUtil.state(pos).isSuffocating(mc.level, pos);
    }

    // The server refuses the bed whilst a monster is this close to its head. Asking would
    // only bring the refusal up on the screen.
    private boolean monstersNear(BlockPos head) {
        AABB area = AABB.ofSize(Vec3.atBottomCenterOf(head), MONSTER_WIDTH, MONSTER_HEIGHT, MONSTER_WIDTH);
        return !mc.level.getEntitiesOfClass(Monster.class, area, Entity::isAlive).isEmpty();
    }
}
