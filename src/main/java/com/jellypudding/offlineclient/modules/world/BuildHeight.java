package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Swaps the clicked side on the use packet at the world height limit. The
 * placement lands in the same spot because only a replaceable block is touched.
 */
public final class BuildHeight extends Module {

    public enum Limit {
        TOP("Top"),
        BOTTOM("Bottom"),
        BOTH("Both");

        private final String label;

        Limit(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final EnumSetting<Limit> limit = new EnumSetting<>("Limit",
        "Which end of the world to work at.", Limit.TOP);

    private int swaps;

    public BuildHeight() {
        super("BuildHeight", "Lets you place blocks against the world height limit.", Category.WORLD);
        addSettings(limit);
        searchTags("height limit", "build limit", "roof", "bedrock");
    }

    @Override
    public String getSuffix() {
        return swaps == 0 ? null : String.valueOf(swaps);
    }

    @Override
    protected void onEnable() {
        swaps = 0;
    }

    @Subscribe(priority = 200)
    private void onPacketSend(PacketSendEvent event) {
        if (!(event.getPacket() instanceof ServerboundUseItemOnPacket packet)) {
            return;
        }
        // The block state below is only safe to read on the main thread.
        if (!mc.isSameThread() || !inGame()) {
            return;
        }
        BlockHitResult hit = packet.getHitResult();
        BlockPos pos = hit.getBlockPos();
        Direction swapped = swapFor(hit.getDirection(), pos);
        if (swapped == null) {
            return;
        }
        // A solid block would send the placement to the neighbour on the other side.
        if (!mc.level.getBlockState(pos).canBeReplaced()) {
            return;
        }
        event.setPacket(new ServerboundUseItemOnPacket(packet.getHand(),
            hit.withDirection(swapped), packet.getSequence()));
        swaps++;
    }

    private Direction swapFor(Direction side, BlockPos pos) {
        if (side == Direction.UP && !limit.is(Limit.BOTTOM) && pos.getY() >= mc.level.getMaxY()) {
            return Direction.DOWN;
        }
        if (side == Direction.DOWN && !limit.is(Limit.TOP) && pos.getY() <= mc.level.getMinY()) {
            return Direction.UP;
        }
        return null;
    }
}
