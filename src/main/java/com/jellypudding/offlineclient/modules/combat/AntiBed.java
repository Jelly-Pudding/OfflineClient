package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.RespawnBlockBreaker;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;

// String has no shape. A bed cannot go where it lies and it fits inside your own hitbox.
public final class AntiBed extends RespawnBlockBreaker {

    private final BoolSetting instantHead = new BoolSetting("Instant head break",
        "Sends the whole break for a bed at your head in one go so the server finishes it alone.", true);
    private final BoolSetting stringAbove = new BoolSetting("String above",
        "Places string in the block above your head.", false);
    private final BoolSetting stringHead = new BoolSetting("String at head",
        "Places string in the block your head is in.", true);
    private final BoolSetting stringFeet = new BoolSetting("String at feet",
        "Places string in the block your feet are in.", false);
    private final BoolSetting onlyInHole = new BoolSetting("Only in hole",
        "Only places string whilst you stand in a blast proof hole.", true);

    private final SlotSwap stringSlots = new SlotSwap();

    // The head bed already sent to the server. Null once it is gone.
    private BlockPos headBed;

    public AntiBed() {
        super("AntiBed", "Breaks an enemy bed placed next to you.", "beds");
        addSettings(instantHead, stringAbove, stringHead, stringFeet, onlyInHole);
        searchTags("bed bomb", "bed aura", "defence", "string");
    }

    @Override
    protected boolean explodesHere() {
        return ExplosionUtil.bedsExplodeHere();
    }

    @Override
    protected boolean isThreat(BlockPos pos) {
        return BlockUtil.state(pos).getBlock() instanceof BedBlock && dangerous(pos);
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        headBed = null;
    }

    @Override
    protected void onDisable() {
        super.onDisable();
        stringSlots.restore();
        headBed = null;
    }

    @Subscribe
    private void onStringTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || !explodesHere()) {
            return;
        }
        BlockPos feet = mc.player.blockPosition();
        breakHeadBed(feet.above());
        if (onlyInHole.isOn() && !BlockUtil.playerInHole()) {
            return;
        }
        if (stringAbove.isOn()) {
            placeString(feet.above(2));
        }
        if (stringHead.isOn()) {
            placeString(feet.above());
        }
        if (stringFeet.isOn()) {
            placeString(feet);
        }
        stringSlots.restore();
    }

    // One start and stop pair is enough. The server carries the break on by itself.
    private void breakHeadBed(BlockPos head) {
        if (!instantHead.isOn() || !(BlockUtil.state(head).getBlock() instanceof BedBlock)) {
            headBed = null;
            return;
        }
        if (head.equals(headBed)) {
            return;
        }
        if (rotate.isOn()) {
            BlockUtil.faceVector(BlockUtil.hitPoint(head, BlockUtil.facingSide(head)));
        }
        BlockMiner.breakInstantly(head);
        headBed = head;
    }

    private void placeString(BlockPos pos) {
        if (BlockUtil.state(pos).is(Blocks.TRIPWIRE) || !BlockUtil.isReplaceable(pos)) {
            return;
        }
        int slot = InventoryUtil.findSlot(Items.STRING, InventoryUtil.HOTBAR_SIZE);
        if (slot == -1) {
            return;
        }
        stringSlots.select(slot);
        BlockUtil.placeAny(pos, rotate.isOn(), true);
    }
}
