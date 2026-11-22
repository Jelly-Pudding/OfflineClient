package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

// Both halves of a double chest count together. A packet may be sent from the network thread.
public final class OpenedContainers {

    private static final Minecraft MC = OfflineClient.MC;

    private final Set<BlockPos> opened = ConcurrentHashMap.newKeySet();

    // Nothing is kept. Null when the packet clicked nothing that counts.
    public static BlockPos clickedOn(Packet<?> packet, Predicate<BlockEntity> counts) {
        BlockEntity blockEntity = clicked(packet, counts);
        return blockEntity == null ? null : blockEntity.getBlockPos();
    }

    public void noteClick(Packet<?> packet, Predicate<BlockEntity> counts) {
        BlockEntity blockEntity = clicked(packet, counts);
        if (blockEntity == null) {
            return;
        }
        BlockPos pos = blockEntity.getBlockPos();
        opened.add(pos);
        BlockPos other = BlockUtil.otherChestHalf(pos, blockEntity.getBlockState());
        if (other != null) {
            opened.add(other);
        }
    }

    public boolean contains(BlockPos pos) {
        return opened.contains(pos);
    }

    public void clear() {
        opened.clear();
    }

    private static BlockEntity clicked(Packet<?> packet, Predicate<BlockEntity> counts) {
        ClientLevel level = MC.level;
        if (!(packet instanceof ServerboundUseItemOnPacket use) || level == null) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(use.hitResult().getBlockPos());
        return blockEntity != null && counts.test(blockEntity) ? blockEntity : null;
    }
}
