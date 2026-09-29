package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.phys.AABB;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// The blocks you broke this session in each world. Mining by hand counts from the first tick
// and every break packet a module sends counts as well. Hunting modules leave them out.
public final class OwnDigs {

    public static final OwnDigs INSTANCE = new OwnDigs();

    // Enough of the blocks you broke lately in one world to cover a long dig.
    private static final int KEPT = 4096;

    // By server and dimension. A full world forgets its oldest dig.
    private final Map<String, Set<Long>> byWorld = new HashMap<>();
    // The copy handed to scanner threads and the world it was taken in.
    private LongSet copy = LongSet.of();
    private String copied;
    private boolean stale;

    private OwnDigs() {
    }

    // The digs in the world you are in as a copy another thread may read.
    public static LongSet snapshot() {
        return INSTANCE.copyHere();
    }

    // True when a block you broke in the world you are in lies inside the box.
    public static boolean anyIn(AABB box) {
        return INSTANCE.inside(box);
    }

    @Subscribe
    private void onBlockBreak(BlockBreakEvent event) {
        dug(event.getPos());
    }

    // Last of all because a packet another handler cancels never went out.
    @Subscribe(priority = Subscribe.LAST)
    private void onPacketSend(PacketSendEvent event) {
        if (!event.isCancelled() && event.getPacket() instanceof ServerboundPlayerActionPacket packet
            && (packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                || packet.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)) {
            dug(packet.getPos());
        }
    }

    private synchronized void dug(BlockPos pos) {
        String world = ServerInfo.worldKey();
        if (!world.isEmpty() && byWorld.computeIfAbsent(world, key -> Collections.newSetFromMap(
            new BoundedMap<>(KEPT))).add(pos.asLong())) {
            stale |= world.equals(copied);
        }
    }

    private synchronized LongSet copyHere() {
        String world = ServerInfo.worldKey();
        if (stale || !world.equals(copied)) {
            Set<Long> here = byWorld.get(world);
            copy = here == null ? LongSet.of() : new LongOpenHashSet(here);
            copied = world;
            stale = false;
        }
        return copy;
    }

    private synchronized boolean inside(AABB box) {
        Set<Long> here = byWorld.get(ServerInfo.worldKey());
        if (here == null) {
            return false;
        }
        for (BlockPos pos : BlockPos.betweenClosed(box.contract(1, 1, 1))) {
            if (here.contains(pos.asLong())) {
                return true;
            }
        }
        return false;
    }
}
