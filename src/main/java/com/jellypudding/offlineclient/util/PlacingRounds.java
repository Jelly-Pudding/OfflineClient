package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.List;

// A round is as many blocks as its row allows and the server's limiter still takes. The delay runs
// between rounds.
public final class PlacingRounds {

    private final NumberSetting perRound = new NumberSetting("Blocks per round",
        "How many blocks to place in one round.", 2, 1, 4, 1);
    private final NumberSetting delay;
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Sends a look packet towards the first block of each round.", true);

    private final SlotSwap slots = new SlotSwap();
    private int timer;

    public PlacingRounds(int startDelay) {
        delay = new NumberSetting("Delay", "Ticks to wait between placing rounds.", startDelay, 0, 10, 1, " ticks");
    }

    public NumberSetting perRound() {
        return perRound;
    }

    public NumberSetting delay() {
        return delay;
    }

    public BoolSetting rotate() {
        return rotate;
    }

    public void forget() {
        timer = 0;
        slots.forget();
    }

    public void restore() {
        slots.restore();
    }

    // The view turns for the first spot alone because the rotation manager keeps the first turn of
    // a tick. Hands back how many went down.
    public int place(List<BlockPos> spots, RegistryListSetting<Block> blocks, SwingMode swing) {
        if (spots.isEmpty()) {
            slots.restore();
            return 0;
        }
        if (timer > 0) {
            timer--;
            return 0;
        }
        int round = Math.min(perRound.getInt(), UseBudget.remaining());
        if (round == 0) {
            return 0;
        }
        int slot = BlockUtil.findRankedBlockSlot(blocks.getValue(), block -> true);
        if (slot == -1) {
            slots.restore();
            return 0;
        }
        slots.select(slot);
        int placed = 0;
        boolean turned = false;
        for (BlockPos pos : spots) {
            if (placed >= round) {
                break;
            }
            boolean turn = rotate.isOn() && !turned;
            if (BlockUtil.placeAny(pos, turn, false)) {
                swing.use();
                turned |= turn;
                placed++;
            }
        }
        if (placed > 0) {
            timer = delay.getInt();
        }
        slots.restore();
        return placed;
    }
}
