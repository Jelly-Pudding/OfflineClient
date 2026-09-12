package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.config.BuildTemplate;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

// Right click a block and the picked template goes up from there the way you face.
public final class AutoBuild extends Module {

    private static final int PLACE_DELAY = 4;
    // How many boxes are drawn at most whilst a big shape goes up.
    private static final int MAX_DRAWN = 1024;

    private final ChoiceListSetting template = new ChoiceListSetting("Template",
        "Which shape to build. The first one picked wins. Files live in offlineclient/templates.",
        BuildTemplate::names);
    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes a block may go.", 6, 1, 10, 0.05).min(1).max(10);
    private final BoolSetting lineOfSight = new BoolSetting("Line of sight",
        "Never places through a wall. Safer against anti cheats and slower.", false);
    private final BoolSetting savedBlocks = new BoolSetting("Use saved blocks",
        "Places the blocks named in the template. Off builds it from whatever you hold.", true);
    private final BoolSetting instant = new BoolSetting("Instant",
        "Places the whole shape in one tick. Only creative mode will let that through.", false);
    private final EnumSetting<FaceMode> faceTarget = FaceMode.setting(FaceMode.SERVER);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.PACKET);
    private final BoolSetting fastPlace = new BoolSetting("Fast place",
        "Ignores the vanilla wait between placements.", true);
    private final BoolSetting strictOrder = new BoolSetting("Strict order",
        "Places the blocks in the order the template lists them. Slower and tidier.", false);
    private final BoolSetting render = new BoolSetting("Show blocks",
        "Outlines what is still to be placed.", true);
    private final BoxStyle todoBox = new BoxStyle(BoxStyle.Shape.LINES, 120).under(render);

    private final SlotSwap slots = new SlotSwap();
    private BuildTemplate loaded;
    private final Map<BlockPos, Block> remaining = new LinkedHashMap<>();
    private int total;

    public AutoBuild() {
        super("AutoBuild", "Builds a saved shape where you right click.", Category.WORLD);
        addSettings(template, range, lineOfSight, savedBlocks, instant, faceTarget, swing, fastPlace,
            strictOrder, render);
        addSettings(todoBox.settings());
        searchTags("auto build", "insta build", "template", "structure");
    }

    @Override
    public String getSuffix() {
        if (loaded == null) {
            return null;
        }
        if (remaining.isEmpty()) {
            return loaded.name();
        }
        return loaded.name() + " " + (total - remaining.size()) * 100 / total + "%";
    }

    @Override
    protected void onEnable() {
        remaining.clear();
        loaded = null;
    }

    @Override
    protected void onDisable() {
        remaining.clear();
        slots.restoreIfMine();
    }

    private String chosenName() {
        Iterator<String> picked = template.chosen().iterator();
        return picked.hasNext() ? picked.next() : null;
    }

    // A fresh pick in the list swaps the shape between builds.
    private boolean loadChosen() {
        String name = chosenName();
        if (name == null) {
            return false;
        }
        if (loaded == null || !loaded.name().equals(name)) {
            loaded = BuildTemplate.load(name);
        }
        return loaded != null;
    }

    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (!inGame() || !remaining.isEmpty()) {
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        if (!loadChosen()) {
            ChatUtil.error("Pick a template first.");
            return;
        }
        BlockPos origin = hit.getBlockPos().relative(hit.getDirection());
        remaining.putAll(loaded.layOut(origin, mc.player.getDirection()));
        total = remaining.size();
        event.cancel();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (remaining.isEmpty()) {
            slots.restoreIfMine();
            loadChosen();
            return;
        }
        remaining.keySet().removeIf(pos -> !BlockUtil.isReplaceable(pos));
        if (remaining.isEmpty()) {
            ChatUtil.message("§bAutoBuild §7finished §f" + loaded.name() + "§7.");
            return;
        }
        if (!instant.isOn() && !fastPlace.isOn() && mc.rightClickDelay > 0) {
            return;
        }
        for (Map.Entry<BlockPos, Block> entry : remaining.entrySet()) {
            boolean placed = tryPlace(entry.getKey(), entry.getValue());
            if (placed && !instant.isOn()) {
                return;
            }
            if (!placed && strictOrder.isOn()) {
                return;
            }
        }
    }

    private boolean tryPlace(BlockPos pos, Block wanted) {
        Direction support = BlockUtil.findPlaceSupport(pos);
        if (support == null) {
            return false;
        }
        Vec3 hit = BlockUtil.hitPoint(pos.relative(support), support.getOpposite());
        if (mc.player.getEyePosition().distanceToSqr(hit) > range.getValue() * range.getValue()) {
            return false;
        }
        if (lineOfSight.isOn() && !BlockUtil.canSee(hit)) {
            return false;
        }
        if (!holdBlock(wanted)) {
            return false;
        }
        faceTarget.getValue().face(hit, RotationPriority.PLACE);
        if (!BlockUtil.place(pos, support, false, false)) {
            return false;
        }
        swing.getValue().swing(InteractionHand.MAIN_HAND);
        mc.rightClickDelay = PLACE_DELAY;
        return true;
    }

    // The named block goes in hand when the template asks for one. Otherwise any block.
    private boolean holdBlock(Block wanted) {
        Block needed = savedBlocks.isOn() ? wanted : null;
        if (needed == null) {
            return mc.player.getMainHandItem().getItem() instanceof BlockItem
                || pickSlot(BlockUtil.findBlockSlot());
        }
        if (mc.player.getMainHandItem().getItem() instanceof BlockItem item && item.getBlock() == needed) {
            return true;
        }
        return pickSlot(BlockUtil.findBlockSlot(block -> block == needed));
    }

    private boolean pickSlot(int slot) {
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        return true;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn() || remaining.isEmpty()) {
            return;
        }
        int drawn = 0;
        for (BlockPos pos : remaining.keySet()) {
            if (drawn++ >= MAX_DRAWN) {
                break;
            }
            todoBox.draw(event.getBatch(), pos, true);
        }
    }
}
