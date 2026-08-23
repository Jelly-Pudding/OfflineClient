package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// The placement helper here is shared with SelfWeb.
public final class AutoWeb extends Module {

    // How far ahead of a moving target the web lands.
    private static final double LEAD_TICKS = 6;

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 8, 1, 16, 0.5, " blocks");
    private final NumberSetting range = new NumberSetting("Range",
        "How far you can reach to place.", 4.5, 1, 6, 0.1).min(1);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between webs.", 2, 0, 20, 1, " ticks");
    private final BoolSetting predict = new BoolSetting("Predict",
        "Aim at where a moving target is heading.", true);
    private final BoolSetting doubles = new BoolSetting("Upper body",
        "Also web the block their head is in.", false);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward each web.", true);
    private final BoolSetting render = new BoolSetting("Show webs",
        "Outline the spots being webbed.", true);

    private final List<BlockPos> spots = new ArrayList<>();
    private int timer;
    private String targetName;

    public AutoWeb() {
        super("AutoWeb", "Throws cobwebs at an enemy to lock them in place.", Category.COMBAT);
        addSettings(targetRange, range, delay, predict, doubles, rotate, render);
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
        spots.clear();
    }

    @Override
    protected void onDisable() {
        targetName = null;
        spots.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        spots.clear();
        if (!inGame() || mc.player.isSpectator()) {
            targetName = null;
            return;
        }
        if (timer > 0) {
            timer--;
        }
        Player target = EntityUtil.nearestEnemy(targetRange.getValue());
        targetName = target == null ? null : target.getGameProfile().name();
        if (target == null) {
            return;
        }

        Vec3 point = target.position();
        if (predict.isOn()) {
            Vec3 speed = new Vec3(target.getX() - target.xOld,
                target.getY() - target.yOld, target.getZ() - target.zOld);
            point = point.add(speed.scale(LEAD_TICKS));
        }
        BlockPos feet = BlockPos.containing(point);
        addSpot(feet);
        if (doubles.isOn()) {
            addSpot(feet.above());
        }
        if (timer > 0 || spots.isEmpty()) {
            return;
        }

        boolean placed = false;
        for (BlockPos spot : spots) {
            placed |= placeWeb(spot, rotate.isOn());
        }
        if (placed) {
            timer = delay.getInt();
        }
    }

    private void addSpot(BlockPos pos) {
        if (webbable(pos) && BlockUtil.distanceTo(pos) <= range.getValue()) {
            spots.add(pos.immutable());
        }
    }

    static boolean webbable(BlockPos pos) {
        return BlockUtil.isReplaceable(pos) && BlockUtil.state(pos).getBlock() != Blocks.COBWEB;
    }

    static boolean placeWeb(BlockPos pos, boolean rotate) {
        Minecraft mc = OfflineClient.MC;
        if (!webbable(pos)) {
            return false;
        }
        int slot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.COBWEB)) {
                slot = i;
                break;
            }
        }
        if (slot == -1) {
            return false;
        }
        int previous = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(slot);
        Direction support = BlockUtil.findPlaceSupport(pos);
        boolean placed = support != null
            ? BlockUtil.place(pos, support, rotate, true)
            : BlockUtil.placeDirect(pos, rotate, true);
        mc.player.getInventory().setSelectedSlot(previous);
        return placed;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        for (BlockPos spot : spots) {
            event.getBatch().outlineBox(new AABB(spot).deflate(0.002), 0xFFF0F0F0, false);
        }
    }
}
