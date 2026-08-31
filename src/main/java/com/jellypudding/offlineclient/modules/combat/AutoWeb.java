package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A runner covers about a third of a block a tick and a web only lands
 * where they are about to be. The spots are laid along their path from the
 * predicted position back to their feet and then round about.
 */
public final class AutoWeb extends Module {

    private static final int PENDING_COLOR = 0xFFF0F0F0;
    private static final int DONE_COLOR = 0x60F0F0F0;

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 8, 1, 16, 0.5, " blocks");
    private final NumberSetting range = new NumberSetting("Range",
        "How far you can reach to place.", 4.5, 1, 6, 0.1);
    private final NumberSetting perTick = new NumberSetting("Webs per tick",
        "How many webs go down in one tick.", 2, 1, 4, 1);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between rounds.", 1, 0, 20, 1, " ticks");
    private final BoolSetting predict = new BoolSetting("Predict",
        "Lays the webs along the way a moving target is heading.", true);
    private final NumberSetting lead = new NumberSetting("Lead",
        "How many ticks ahead of a runner the furthest web goes.", 6, 1, 15, 1, " ticks")
        .under(predict);
    private final NumberSetting cover = new NumberSetting("Cover",
        "Blocks to web on every side of the target as well. Zero webs only their feet.",
        0, 0, 2, 1, " blocks");
    private final BoolSetting doubles = new BoolSetting("Upper body",
        "Also web the block their head is in.", false);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards each web.", true);
    private final BoolSetting render = new BoolSetting("Show webs",
        "Outline the spots being webbed. Done ones fade.", true);

    // Spots still to web then spots already webbed. The outline lasts the whole tick.
    private final List<BlockPos> pending = new ArrayList<>();
    private final List<BlockPos> done = new ArrayList<>();
    private final SlotSwap slots = new SlotSwap();
    private int timer;
    private String targetName;

    public AutoWeb() {
        super("AutoWeb", "Throws cobwebs at an enemy to lock them in place.", Category.COMBAT);
        addSettings(targetRange, range, perTick, delay, predict, lead, cover, doubles, rotate, render);
        searchTags("cobweb", "web", "trap");
    }

    @Override
    public String getSuffix() {
        return targetName;
    }

    @Override
    protected void onEnable() {
        timer = 0;
        targetName = null;
        pending.clear();
        done.clear();
    }

    @Override
    protected void onDisable() {
        targetName = null;
        pending.clear();
        done.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        pending.clear();
        done.clear();
        if (!inGame() || mc.player.isSpectator()) {
            targetName = null;
            return;
        }
        if (timer > 0) {
            timer--;
        }
        Player target = EntityUtil.nearestEnemy(targetRange.getValue());
        targetName = EntityUtil.nameOf(target);
        if (target == null) {
            return;
        }

        for (BlockPos spot : spotsFor(target)) {
            if (BlockUtil.state(spot).getBlock() == Blocks.COBWEB) {
                done.add(spot);
            } else if (webbable(spot) && BlockUtil.distanceTo(spot) <= range.getValue()) {
                pending.add(spot);
            }
        }
        if (timer > 0 || pending.isEmpty()) {
            return;
        }

        int placed = 0;
        for (BlockPos spot : pending) {
            if (placed >= perTick.getInt()) {
                break;
            }
            if (placeWeb(spot, rotate.isOn(), slots)) {
                placed++;
            }
        }
        if (placed > 0) {
            timer = delay.getInt();
        }
    }

    // Furthest ahead first. A web behind a runner is wasted.
    private Set<BlockPos> spotsFor(Player target) {
        Set<BlockPos> spots = new LinkedHashSet<>();
        Vec3 feet = target.position();
        Vec3 pace = predict.isOn() ? EntityUtil.velocityOf(target) : Vec3.ZERO;
        // Vertical movement is a jump. The web wants the block they land in.
        pace = new Vec3(pace.x, 0, pace.z);
        int ahead = pace.horizontalDistanceSqr() > 0.0004 ? lead.getInt() : 0;
        for (int tick = ahead; tick >= 0; tick--) {
            addColumn(spots, BlockPos.containing(feet.add(pace.scale(tick))));
        }
        int ring = cover.getInt();
        BlockPos centre = BlockPos.containing(feet);
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                addColumn(spots, centre.offset(dx, 0, dz));
            }
        }
        return spots;
    }

    private void addColumn(Set<BlockPos> spots, BlockPos feet) {
        spots.add(feet.immutable());
        if (doubles.isOn()) {
            spots.add(feet.above());
        }
    }

    static boolean webbable(BlockPos pos) {
        return BlockUtil.isReplaceable(pos) && BlockUtil.state(pos).getBlock() != Blocks.COBWEB;
    }

    static boolean placeWeb(BlockPos pos, boolean rotate, SlotSwap slots) {
        if (!webbable(pos)) {
            return false;
        }
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.COBWEB));
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        boolean placed = BlockUtil.placeAny(pos, rotate, true);
        slots.restore();
        return placed;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        for (BlockPos spot : pending) {
            event.getBatch().outlineBlock(spot, PENDING_COLOR, false);
        }
        for (BlockPos spot : done) {
            event.getBatch().outlineBlock(spot, DONE_COLOR, false);
        }
    }
}
