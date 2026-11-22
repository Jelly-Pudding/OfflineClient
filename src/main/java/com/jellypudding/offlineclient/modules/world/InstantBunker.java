package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.UseBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class InstantBunker extends Module {

    // Every block of the shell as sideways then up then forward from your feet.
    private static final int[][] SHELL = {
        {2, 0, 2}, {-2, 0, 2}, {2, 0, -2}, {-2, 0, -2}, {2, 1, 2}, {-2, 1, 2}, {2, 1, -2},
        {-2, 1, -2}, {2, 2, 2}, {-2, 2, 2}, {2, 2, -2}, {-2, 2, -2}, {1, 2, 2}, {0, 2, 2},
        {-1, 2, 2}, {2, 2, 1}, {2, 2, 0}, {2, 2, -1}, {-2, 2, 1}, {-2, 2, 0}, {-2, 2, -1},
        {1, 2, -2}, {0, 2, -2}, {-1, 2, -2}, {1, 0, 2}, {0, 0, 2}, {-1, 0, 2}, {2, 0, 1},
        {2, 0, 0}, {2, 0, -1}, {-2, 0, 1}, {-2, 0, 0}, {-2, 0, -1}, {1, 0, -2}, {0, 0, -2},
        {-1, 0, -2}, {1, 1, 2}, {0, 1, 2}, {-1, 1, 2}, {2, 1, 1}, {2, 1, 0}, {2, 1, -1},
        {-2, 1, 1}, {-2, 1, 0}, {-2, 1, -1}, {1, 1, -2}, {0, 1, -2}, {-1, 1, -2}, {1, 2, 1},
        {-1, 2, 1}, {1, 2, -1}, {-1, 2, -1}, {0, 2, 1}, {1, 2, 0}, {-1, 2, 0}, {0, 2, -1},
        {0, 2, 0}};

    private static final int LIFT_OFF = 2;

    // The second click makes up for one the server dropped.
    private static final int CLICKS = 2;

    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns towards each block on the server side.", false);

    private final List<BlockPos> shell = new ArrayList<>();
    private final Map<BlockPos, Integer> clicks = new HashMap<>();
    private int timer;

    public InstantBunker() {
        super("InstantBunker", "Jumps and builds a small bunker round you.", Category.WORLD);
        addSettings(rotate);
        searchTags("bunker", "instant base", "panic room");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    protected void onEnable() {
        if (!inGame()) {
            setEnabled(false);
            return;
        }
        if (!mc.player.onGround()) {
            disable("You need to be standing on the ground.");
            return;
        }
        ItemStack held = mc.player.getMainHandItem();
        if (!(held.getItem() instanceof BlockItem)) {
            disable("Hold the blocks in your main hand.");
            return;
        }
        if (held.getCount() < SHELL.length && !mc.player.getAbilities().instabuild) {
            ChatUtil.message("Fewer than " + SHELL.length + " blocks. The bunker may have gaps.");
        }
        BlockPos feet = BlockPos.containing(mc.player.position());
        Direction forward = mc.player.getDirection();
        Direction sideways = forward.getCounterClockWise();
        shell.clear();
        clicks.clear();
        for (int[] offset : SHELL) {
            shell.add(feet.above(offset[1]).relative(forward, offset[2])
                .relative(sideways, offset[0]));
        }
        timer = LIFT_OFF;
        mc.player.jumpFromGround();
    }

    // Paper drops use packets past its limit and the rest wait for a later tick.
    @Subscribe
    private void onTick(TickEvent event) {
        if (timer-- > 0) {
            return;
        }
        boolean placed = false;
        boolean reachedAll = true;
        boolean outOfBlocks = false;
        for (BlockPos pos : shell) {
            if (clicks.getOrDefault(pos, 0) >= CLICKS || !BlockUtil.isReplaceable(pos)
                || BlockUtil.intersectsPlayer(pos)) {
                continue;
            }
            if (!(mc.player.getMainHandItem().getItem() instanceof BlockItem)) {
                outOfBlocks = true;
                break;
            }
            if (UseBudget.remaining() == 0) {
                reachedAll = false;
                break;
            }
            clicks.merge(pos, 1, Integer::sum);
            placed |= BlockUtil.placeAny(pos, rotate.isOn(), false);
        }
        if (placed) {
            SwingMode.swingUse(InteractionHand.MAIN_HAND);
        }
        if (outOfBlocks || reachedAll && mc.player.onGround()) {
            setEnabled(false);
        }
    }
}
