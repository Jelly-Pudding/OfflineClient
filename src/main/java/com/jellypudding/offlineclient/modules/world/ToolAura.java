package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.BlockUseAura;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockTurns;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.Buckets;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.UseBudget;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Uses whatever tool is in hand on the listed blocks in reach wherever the tool would change
// the block. The game itself says what each tool does to each block.
public final class ToolAura extends BlockUseAura {

    // A spot of the strip loop this far past the range is forgotten.
    private static final double LOOP_SLACK = 2;

    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks the tool is used on. Click to pick them.", BuiltInRegistries.BLOCK,
        List.of(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.PODZOL,
            Blocks.MYCELIUM, Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG,
            Blocks.ACACIA_LOG, Blocks.CHERRY_LOG, Blocks.DARK_OAK_LOG, Blocks.PALE_OAK_LOG,
            Blocks.MANGROVE_LOG, Blocks.POPLAR_LOG, Blocks.CRIMSON_STEM, Blocks.WARPED_STEM,
            Blocks.BAMBOO_BLOCK));
    private final BoolSetting refill = new BoolSetting("Refill bottle",
        "Fills the bottle again at water in reach each time it has been emptied onto dirt.", true);
    private final BoolSetting loop = new BoolSetting("Strip loop",
        "Breaks each log the axe stripped and puts the next log from your hotbar in its place until"
            + " they run out.", false);
    private final BoolSetting antiBreak = new BoolSetting("Anti break",
        "Never uses a tool that is about to break.", true);

    private final InventoryUtil.SlotSwap slots = new InventoryUtil.SlotSwap();
    private final WorldWatch world = new WorldWatch();
    // Where the strip loop stripped a log and what the strip left there. Each is broken and the
    // hole takes the next log.
    private final Map<BlockPos, Block> stripped = new HashMap<>();
    // The kinds of block the axe has stripped. The loop puts no other kind back.
    private final Set<Block> strips = new HashSet<>();
    // The stripped log being broken. Null whilst none is.
    private BlockPos breaking;
    private int used;

    public ToolAura() {
        super("ToolAura", "Uses the shovel or axe or hoe or water bottle you hold on the listed blocks around"
            + " you.", "Multi use");
        addSettings(blocks, range, multi, lineOfSight, refill, loop, antiBreak, face, swing);
        searchTags("path", "shovel", "strip logs", "axe", "hoe", "till", "mud", "water bottle", "stripper");
    }

    @Override
    public String getSuffix() {
        return count(used, "used");
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        world.accept();
        forgetLoop();
        used = 0;
    }

    @Override
    protected void onDisable() {
        stopBreaking();
        forgetLoop();
        slots.restoreIfMine();
    }

    private void forgetLoop() {
        stripped.clear();
        strips.clear();
        breaking = null;
    }

    // The loop's own digging is not the player's.
    @Override
    protected boolean busy() {
        return mc.player.isHandsBusy() || breaking == null && mc.gameMode.isDestroying();
    }

    @Override
    protected void rest() {
        stopBreaking();
    }

    @Override
    protected void work() {
        if (world.changed()) {
            forgetLoop();
        }
        ItemStack held = mc.player.getMainHandItem();
        if (antiBreak.isOn() && ItemUtil.nearlyBroken(held)) {
            stopBreaking();
            return;
        }
        if (looping(held)) {
            return;
        }
        if (held.is(Items.GLASS_BOTTLE)) {
            refill(held);
            return;
        }
        List<BlockPos> targets = inReach(pos -> listed(pos) && BlockTurns.result(held, pos) != null);
        if (targets.isEmpty() || UseBudget.remaining() == 0) {
            return;
        }
        boolean strip = loop.isOn() && held.is(ItemTags.AXES);
        // A sneaking click reaches the tool. The server then skips the door or chest under the
        // click and a shield in the other hand cannot hold the tool back.
        InputUtil.whileSneaking(() -> click(targets, pos -> use(pos, strip)) > 0);
    }

    // The strip loop only takes a log. Copper the axe scrapes stays where it is.
    private boolean use(BlockPos pos, boolean strip) {
        BlockState before = BlockUtil.state(pos);
        if (!BlockUtil.useOn(pos, false, false)) {
            return false;
        }
        used++;
        if (strip && (before.is(BlockTags.LOGS) || before.is(BlockTags.BAMBOO_BLOCKS))) {
            strips.add(before.getBlock());
            stripped.put(pos, BlockUtil.state(pos).getBlock());
        }
        return true;
    }

    private boolean listed(BlockPos pos) {
        return blocks.contains(BlockUtil.state(pos).getBlock());
    }

    // A lone empty bottle is the one the last click emptied. It is filled again whilst there is
    // still dirt to wet. A stack of bottles is the player's own and stays as it is.
    private void refill(ItemStack bottle) {
        if (!refill.isOn() || bottle.getCount() != 1 || UseBudget.remaining() == 0) {
            return;
        }
        if (inReach(pos -> listed(pos) && BlockTurns.wets(BlockUtil.state(pos), BlockUtil.facingSide(pos)))
            .isEmpty()) {
            return;
        }
        BlockPos water = BlockUtil.nearestWithin(range.getValue(),
            pos -> BlockUtil.isWaterSource(pos) && Buckets.scoopAim(pos) != null);
        if (water == null) {
            return;
        }
        Vec3 aim = Buckets.scoopAim(water);
        if (Buckets.useFacing(aim)) {
            mc.rightClickDelay = InputUtil.USE_DELAY;
        }
    }

    // One step of the strip loop. True whilst it has a stripped log to break or a hole to fill.
    private boolean looping(ItemStack held) {
        if (!loop.isOn() || !held.is(ItemTags.AXES)) {
            stopBreaking();
            stripped.clear();
            return false;
        }
        BlockPos spot = nextSpot();
        if (spot == null) {
            stopBreaking();
            return false;
        }
        if (!BlockUtil.isReplaceable(spot)) {
            breakStripped(spot);
            return true;
        }
        stopBreaking();
        int slot = InventoryUtil.hotbarSlot(stack -> stack.getItem() instanceof BlockItem item
            && strips.contains(item.getBlock()));
        if (slot == -1) {
            // No log is left to put there. The spot is done.
            stripped.remove(spot);
            return false;
        }
        if (UseBudget.remaining() > 0 && putLog(slot, spot)) {
            stripped.remove(spot);
        }
        return true;
    }

    // The nearest spot in reach that holds the log the axe stripped or the hole it left. A spot
    // that came to hold anything else is forgotten.
    private BlockPos nextSpot() {
        double reach = range.getValue();
        stripped.entrySet().removeIf(spot -> !BlockUtil.reaches(spot.getKey(), reach + LOOP_SLACK)
            || !BlockUtil.isReplaceable(spot.getKey())
            && !BlockUtil.state(spot.getKey()).is(spot.getValue()));
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos pos : stripped.keySet()) {
            double distance = BlockUtil.reachTo(pos);
            if (distance < best && distance <= reach && ready(pos)) {
                best = distance;
                nearest = pos;
            }
        }
        return nearest;
    }

    // The log underfoot waits until you step off it. A hole somebody stands in waits for them
    // to step out.
    private static boolean ready(BlockPos pos) {
        return BlockUtil.isReplaceable(pos) ? BlockUtil.blockFits(pos) : !BlockUtil.isStandingOn(pos);
    }

    private void breakStripped(BlockPos pos) {
        if (BlockMiner.minesOther(pos)) {
            return;
        }
        face.getValue().face(BlockUtil.hitPoint(pos, BlockUtil.facingSide(pos)), RotationPriority.MINE);
        breaking = BlockMiner.mineWithHeld(pos, false, SwingMode.of(swing.isOn())) ? pos : null;
    }

    private void stopBreaking() {
        if (breaking != null) {
            BlockMiner.release(breaking);
            breaking = null;
        }
    }

    private boolean putLog(int slot, BlockPos pos) {
        slots.select(slot);
        face.getValue().face(Vec3.atCenterOf(pos), RotationPriority.PLACE);
        boolean placed = BlockUtil.placeAny(pos, false, false);
        if (placed) {
            SwingMode.of(swing.isOn()).use();
            mc.rightClickDelay = InputUtil.USE_DELAY;
        }
        slots.restore();
        return placed;
    }
}
