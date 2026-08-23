package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.AxisWalker;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Digs a straight tunnel and walks along it. The axis is taken from the facing
 * when it starts.
 */
public final class Tunneller extends Module {

    private static final int TUNNEL_COLOR = 0xFF40C0FF;
    private static final int CURRENT_COLOR = 0xFFFF5030;

    // Depths looked at in one tick. Keeps a long clear stretch cheap.
    private static final int SCAN_DEPTHS = 8;

    private final NumberSetting width = new NumberSetting("Width",
        "How wide the tunnel is.", 1, 1, 5, 1, " blocks").min(1).max(9);
    private final NumberSetting height = new NumberSetting("Height",
        "How tall the tunnel is.", 2, 1, 5, 1, " blocks").min(1).max(9);
    private final NumberSetting length = new NumberSetting("Length",
        "How far to dig before stopping.", 64, 8, 512, 8, " blocks").min(1);
    private final BoolSetting torches = new BoolSetting("Torches",
        "Puts a torch on the floor as you go. Needs torches in your hotbar.", false);
    private final NumberSetting spacing = new NumberSetting("Torch spacing",
        "Blocks between one torch and the next.", 8, 2, 16, 1, " blocks")
        .min(1).visibleWhen(torches::isOn);

    private final AxisWalker walker = new AxisWalker();
    private final SlotSwap slots = new SlotSwap();
    private int cleared;
    private int lastTorch;
    private BlockPos current;

    public Tunneller() {
        super("Tunneller", "Digs a straight tunnel the way you face.", Category.WORLD);
        addSettings(width, height, length, torches, spacing);
        searchTags("tunnel", "mine", "dig", "strip mine");
    }

    @Override
    public String getSuffix() {
        if (!walker.isLocked()) {
            return null;
        }
        return Math.min(cleared, length.getInt()) + " of " + length.getInt();
    }

    // Bringing this back at launch would start digging at once.
    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        walker.clear();
        slots.forget();
        current = null;
        if (inGame()) {
            lockAxis();
        }
    }

    private void lockAxis() {
        walker.lock();
        cleared = 0;
        lastTorch = 0;
    }

    @Override
    protected void onDisable() {
        BlockMiner.release();
        slots.restoreIfMine();
        mc.options.keyUp.setDown(false);
        walker.clear();
        current = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        current = null;
        if (!inGame()) {
            return;
        }
        if (!walker.isLocked()) {
            lockAxis();
        }
        if (mc.player.isSpectator() || mc.player.isPassenger()) {
            stop("Tunneller stopped because you cannot dig from there.");
            return;
        }
        if (Math.abs(mc.player.getY() - walker.floorY()) > 1.5) {
            stop("Tunneller stopped because you left the tunnel floor.");
            return;
        }

        walker.holdAxis();
        current = nextBlock();

        if (current != null) {
            mc.options.keyUp.setDown(false);
            if (!BlockMiner.mine(current, true)) {
                current = null;
            }
            return;
        }
        if (cleared >= length.getInt() && walker.travelled() >= length.getInt() - 1) {
            ChatUtil.message("§bTunneller §7finished §f" + length.getInt() + "§7 blocks.");
            setEnabled(false);
            return;
        }
        BlockMiner.release();
        mc.options.keyUp.setDown(true);
        placeTorch();
    }

    private BlockPos nextBlock() {
        double reach = mc.player.blockInteractionRange();
        for (int scanned = 0; scanned < SCAN_DEPTHS && cleared < length.getInt(); scanned++) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            boolean anyLeft = false;
            for (BlockPos pos : slice(cleared + 1)) {
                if (!BlockUtil.diggable(pos)) {
                    continue;
                }
                anyLeft = true;
                double distance = BlockUtil.distanceTo(pos);
                if (distance <= reach && distance < bestDistance) {
                    bestDistance = distance;
                    best = pos;
                }
            }
            if (!anyLeft) {
                cleared++;
                continue;
            }
            return best;
        }
        return null;
    }

    private List<BlockPos> slice(int depth) {
        List<BlockPos> result = new ArrayList<>(width.getInt() * height.getInt());
        for (int lane = leftLane(); lane <= rightLane(); lane++) {
            for (int up = 0; up < height.getInt(); up++) {
                result.add(walker.blockAt(depth, lane, up));
            }
        }
        return result;
    }

    private int leftLane() {
        return -((width.getInt() - 1) / 2);
    }

    private int rightLane() {
        return width.getInt() - 1 + leftLane();
    }

    // Puts a torch against the left wall at a set spacing.
    private void placeTorch() {
        if (!torches.isOn()) {
            return;
        }
        int depth = (int) Math.floor(walker.travelled());
        if (depth < 1 || depth - lastTorch < spacing.getInt()) {
            return;
        }
        BlockPos target = walker.blockAt(depth, leftLane(), 0);
        if (!BlockUtil.isReplaceable(target) || !BlockUtil.isSolid(target.below())) {
            return;
        }
        int slot = BlockUtil.findBlockSlot(block -> block == Blocks.TORCH || block == Blocks.SOUL_TORCH);
        if (slot == -1) {
            return;
        }
        slots.select(slot);
        if (BlockUtil.place(target, Direction.DOWN, true, true)) {
            lastTorch = depth;
        }
        slots.restoreIfMine();
    }

    private void stop(String reason) {
        ChatUtil.error(reason);
        setEnabled(false);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!walker.isLocked() || !inGame()) {
            return;
        }
        int end = Math.min(cleared + 1, length.getInt());
        if (end >= 1) {
            AABB near = box(1);
            AABB far = box(end);
            event.getBatch().outlineBox(near.minmax(far).inflate(0.005), TUNNEL_COLOR, true);
        }
        if (current != null) {
            event.getBatch().outlineBlock(current, CURRENT_COLOR, false);
        }
    }

    private AABB box(int depth) {
        AABB first = new AABB(walker.blockAt(depth, leftLane(), 0));
        AABB last = new AABB(walker.blockAt(depth, rightLane(), height.getInt() - 1));
        return first.minmax(last);
    }
}
