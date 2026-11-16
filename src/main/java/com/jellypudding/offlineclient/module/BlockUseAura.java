package com.jellypudding.offlineclient.module;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.UseBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

// Shared by modules that use an item on the blocks in reach. A subclass says which blocks and
// puts the item in hand. The waiting and the scan and the clicking are the same for all of them.
public abstract class BlockUseAura extends Module {

    // A click that left the block as it was is not made again for this long. A click the server
    // turns down leaves the block as it was too.
    private static final int RETRY_TICKS = 20;

    // One block as it looked when it was clicked.
    private record Click(BlockPos pos, BlockState state) {
    }

    protected final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes to reach.", 5, 1, 6, 0.1, " blocks");
    protected final BoolSetting multi;
    protected final BoolSetting lineOfSight = new BoolSetting("Line of sight",
        "Leaves alone every block you cannot see from where you stand.", true);
    protected final EnumSetting<FaceMode> face = FaceMode.setting(FaceMode.SERVER);
    protected final BoolSetting swing = SwingMode.useSetting(true);

    private final Cooldowns<Click> tried = new Cooldowns<>();

    protected BlockUseAura(String name, String description, String multiName) {
        super(name, description, Category.WORLD);
        multi = new BoolSetting(multiName,
            "Works on every block in reach in one tick. Fast but obvious to an anti cheat.", false);
    }

    // One round of work. Only runs whilst the hands are free.
    protected abstract void work();

    // Runs in place of the work on a tick something else has the hands. A borrowed slot goes back here.
    protected void rest() {
    }

    // Mining and steering a mount keep the hands. A subclass may let either pass.
    protected boolean busy() {
        return mc.gameMode.isDestroying() || mc.player.isHandsBusy();
    }

    // The game's own wait between two right clicks. Working on everything at once skips it.
    protected boolean paced() {
        return !multi.isOn() && mc.rightClickDelay > 0;
    }

    // Ticks a block is left alone after a click that did not change it.
    protected int retryTicks() {
        return RETRY_TICKS;
    }

    @Override
    protected void onEnable() {
        tried.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.gameMode == null) {
            return;
        }
        tried.tick();
        if (mc.gui.screen() != null || Hands.taken() || busy()) {
            rest();
            return;
        }
        if (!paced()) {
            work();
        }
    }

    // The blocks in reach that pass the test with the nearest first.
    protected final List<BlockPos> inReach(Predicate<BlockPos> wanted) {
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (wanted.test(pos) && !tried.contains(new Click(pos, BlockUtil.state(pos)))
                && (!lineOfSight.isOn() || BlockUtil.canSee(pos))) {
                found.add(pos);
            }
        }
        return found;
    }

    // Clicks the targets in turn until the game takes one or with the multi switch all of them
    // and says how many it took. One angle rides the move packet of a tick and only the first
    // target gets the turn unless every click sends a look packet of its own.
    protected final int click(List<BlockPos> targets, Predicate<BlockPos> use) {
        int limit = multi.isOn() ? targets.size() : 1;
        int taken = 0;
        boolean turned = false;
        for (BlockPos pos : targets) {
            // Paper drops every use packet past its limit and never answers it.
            if (taken >= limit || UseBudget.remaining() == 0) {
                break;
            }
            if (!turned || face.is(FaceMode.SPAM)) {
                face.getValue().face(BlockUtil.hitPoint(pos, BlockUtil.facingSide(pos)), RotationPriority.PLACE);
                turned = true;
            }
            tried.put(new Click(pos, BlockUtil.state(pos)), retryTicks());
            if (use.test(pos)) {
                taken++;
            }
        }
        if (taken > 0) {
            SwingMode.of(swing.isOn()).use();
            mc.rightClickDelay = InputUtil.USE_DELAY;
        }
        return taken;
    }
}
