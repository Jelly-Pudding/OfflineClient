package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

// Jumps and drops a five by five shell round you before you land.
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

    // Ticks after the jump before the first block goes down.
    private static final int LIFT_OFF = 2;

    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns towards each block on the server side.", false);

    private final List<BlockPos> shell = new ArrayList<>();
    private int timer;

    public InstantBunker() {
        super("InstantBunker", "Builds a small bunker round you in one jump.", Category.WORLD);
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
            ChatUtil.error("You need to be standing on the ground.");
            setEnabled(false);
            return;
        }
        ItemStack held = mc.player.getMainHandItem();
        if (!(held.getItem() instanceof BlockItem)) {
            ChatUtil.error("Hold the blocks in your main hand.");
            setEnabled(false);
            return;
        }
        if (held.getCount() < SHELL.length && !mc.player.getAbilities().instabuild) {
            ChatUtil.message("Fewer than " + SHELL.length + " blocks. The bunker may have gaps.");
        }
        BlockPos feet = BlockPos.containing(mc.player.position());
        Direction forward = mc.player.getDirection();
        Direction sideways = forward.getCounterClockWise();
        shell.clear();
        for (int[] offset : SHELL) {
            shell.add(feet.above(offset[1]).relative(forward, offset[2])
                .relative(sideways, offset[0]));
        }
        timer = LIFT_OFF;
        mc.player.jumpFromGround();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || timer-- > 0) {
            return;
        }
        for (BlockPos pos : shell) {
            if (BlockUtil.isReplaceable(pos) && !BlockUtil.intersectsPlayer(pos)) {
                BlockUtil.placeAny(pos, rotate.isOn(), false);
            }
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
        if (mc.player.onGround()) {
            setEnabled(false);
        }
    }
}
