package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

public final class Burrow extends Module {

    // Rungs of the packet lift. The last one clears the block completely.
    private static final double[] LIFT = {0.42, 0.75, 1.01, 1.16};

    // Height a jump has to clear before the block goes down.
    private static final double TRIGGER_HEIGHT = 1.02;

    // A move this far up is refused and the server puts the player back inside the block.
    private static final double RUBBERBAND = 12;

    private static final int JUMP_TIMEOUT = 20;

    public enum Lift { JUMP, PACKET }

    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "Blocks to use in order of preference.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.ENDER_CHEST));
    private final EnumSetting<Lift> lift = new EnumSetting<>("Lift",
        "How to get above the block as it goes in.", Lift.PACKET)
        .describe(Lift.JUMP, "Jumps for real.")
        .describe(Lift.PACKET, "Sends position packets to hop up without moving on screen.");
    private final BoolSetting center = new BoolSetting("Center",
        "Snap to the middle of your block first.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward the block as it goes down.", true);

    private final SlotSwap slots = new SlotSwap();
    private BlockPos anchor;
    private int waited;

    public Burrow() {
        super("Burrow", "Places a blast proof block inside your own hitbox.", Category.COMBAT);
        addSettings(blocks, lift, center, rotate);
        searchTags("clip", "obsidian", "crystal", "hole");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    protected void onEnable() {
        waited = 0;
        anchor = null;
        slots.forget();
        if (!inGame() || mc.player.isSpectator()) {
            setEnabled(false);
            return;
        }
        BlockPos feet = mc.player.blockPosition();
        if (!BlockUtil.isReplaceable(feet)) {
            ChatUtil.error("Already inside a block.");
            setEnabled(false);
            return;
        }
        if (!BlockUtil.isReplaceable(feet.above()) || !BlockUtil.isReplaceable(feet.above(2))) {
            ChatUtil.error("Not enough room above to burrow.");
            setEnabled(false);
            return;
        }
        if (findSlot() == -1) {
            ChatUtil.error("No burrow block in your hotbar.");
            setEnabled(false);
            return;
        }
        anchor = feet;
        if (lift.is(Lift.JUMP)) {
            mc.player.jumpFromGround();
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || anchor == null) {
            setEnabled(false);
            return;
        }
        if (lift.is(Lift.JUMP) && mc.player.getY() < anchor.getY() + TRIGGER_HEIGHT) {
            if (++waited > JUMP_TIMEOUT) {
                ChatUtil.error("Could not get high enough to burrow.");
                setEnabled(false);
            }
            return;
        }
        burrow();
        setEnabled(false);
    }

    private void burrow() {
        if (center.isOn()) {
            BlockUtil.centerPlayer();
        }
        if (lift.is(Lift.PACKET)) {
            for (double step : LIFT) {
                mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
                    mc.player.getX(), mc.player.getY() + step, mc.player.getZ(),
                    false, mc.player.horizontalCollision));
            }
        }

        int slot = findSlot();
        if (slot == -1) {
            ChatUtil.error("No burrow block in your hotbar.");
            return;
        }
        slots.select(slot);
        // The local placement is refused but the packet still goes out. The server
        // has the player up on the lift by then.
        BlockUtil.placeDirect(anchor, rotate.isOn(), true);
        slots.restore();

        if (lift.is(Lift.PACKET)) {
            mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
                mc.player.getX(), mc.player.getY() + RUBBERBAND, mc.player.getZ(),
                false, mc.player.horizontalCollision));
        } else {
            mc.player.absSnapTo(mc.player.getX(), mc.player.getY() + RUBBERBAND, mc.player.getZ());
        }
    }

    private int findSlot() {
        return BlockUtil.findRankedBlockSlot(blocks.getValue(), block -> true);
    }
}
