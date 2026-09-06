package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// A runner moves about a third of a block a tick so webs are laid along
// their predicted path from ahead back to their feet and then around them.
public final class AutoWeb extends Module {

    // How much of the colour a spot already webbed keeps.
    private static final float DONE_FADE = 0.4f;

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 8, 1, 16, 0.5, " blocks");
    private final EnumSetting<TargetPriority> priority = TargetPriority.setting("Webs",
        TargetPriority.NEAREST);
    private final NumberSetting range = new NumberSetting("Range",
        "How far you can reach to place.", 4.5, 1, 6, 0.1, " blocks");
    private final NumberSetting wallsRange = new NumberSetting("Walls range",
        "Shorter reach for a spot you cannot see.", 4, 0, 6, 0.1, " blocks");
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
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 270f).under(render);

    // Spots still to web then spots already webbed. The outline lasts the whole tick.
    private final List<BlockPos> pending = new ArrayList<>();
    private final List<BlockPos> done = new ArrayList<>();
    private final SlotSwap slots = new SlotSwap();
    private int timer;
    private String targetName;

    public AutoWeb() {
        super("AutoWeb", "Throws cobwebs at an enemy to lock them in place.", Category.COMBAT);
        addSettings(targetRange, priority, range, wallsRange, perTick, delay, predict, lead, cover,
            doubles, rotate, render);
        addSettings(style.settings());
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
        Player target = EntityUtil.bestEnemy(targetRange.getValue(), priority.getValue());
        targetName = EntityUtil.nameOf(target);
        if (target == null) {
            return;
        }

        for (BlockPos spot : spotsFor(target)) {
            if (BlockUtil.state(spot).getBlock() == Blocks.COBWEB) {
                done.add(spot);
            } else if (webbable(spot) && inReach(spot)) {
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

    private boolean inReach(BlockPos pos) {
        double reach = BlockUtil.canSee(Vec3.atCenterOf(pos)) ? range.getValue() : wallsRange.getValue();
        return BlockUtil.distanceTo(pos) <= reach;
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
            style.draw(event.getBatch(), spot, false);
        }
        int faded = ColorUtil.fade(style.lineColor(), DONE_FADE);
        for (BlockPos spot : done) {
            style.draw(event.getBatch(), spot, faded, false);
        }
    }
}
