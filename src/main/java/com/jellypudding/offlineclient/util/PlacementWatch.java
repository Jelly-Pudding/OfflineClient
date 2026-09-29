package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Blocks this client placed that the server has not shown back yet. The client draws a
// placed block at once as a guess. The server answers every click it takes with the new
// state of the block. A click it throws away gets no answer and the guess stands until an
// answer to a later click undoes it.
public final class PlacementWatch {

    // When each unanswered block went down. The network thread reads the keys.
    private final Map<Long, Long> waiting = new ConcurrentHashMap<>();

    // Blocks the server has shown back as solid. Filled on the network thread.
    private final Set<Long> confirmed = ConcurrentHashMap.newKeySet();

    // Clicks spent on each block the server has not confirmed yet.
    private final Map<Long, Integer> attempts = new HashMap<>();

    // After the click that placed the block went out.
    public void placed(BlockPos pos) {
        long key = pos.asLong();
        confirmed.remove(key);
        waiting.put(key, System.currentTimeMillis());
        attempts.merge(key, 1, Integer::sum);
    }

    // Hand every received packet over. Runs on the network thread.
    public void onPacket(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundBlockUpdatePacket packet) {
            long key = packet.getPos().asLong();
            if (waiting.containsKey(key) && !packet.getBlockState().canBeReplaced()) {
                confirmed.add(key);
            }
        }
    }

    // Lets go of the blocks the server confirmed. Hands back those the world shows as
    // missing again. The server refused them or a later answer undid a thrown away click.
    public List<BlockPos> sweep() {
        List<BlockPos> missing = new ArrayList<>();
        Iterator<Long> keys = waiting.keySet().iterator();
        while (keys.hasNext()) {
            long key = keys.next();
            if (confirmed.remove(key)) {
                keys.remove();
                attempts.remove(key);
                continue;
            }
            BlockPos pos = BlockPos.of(key);
            if (BlockUtil.isReplaceable(pos)) {
                keys.remove();
                missing.add(pos);
            }
        }
        return missing;
    }

    // Blocks still waiting for the server.
    public int waiting() {
        return waiting.size();
    }

    // Milliseconds the oldest unanswered block has waited. Zero whilst none waits.
    public long longestWait() {
        long now = System.currentTimeMillis();
        long longest = 0;
        for (long placedAt : waiting.values()) {
            longest = Math.max(longest, now - placedAt);
        }
        return longest;
    }

    // Clicks spent on a block the server has not confirmed yet.
    public int attempts(BlockPos pos) {
        return attempts.getOrDefault(pos.asLong(), 0);
    }

    public void clear() {
        waiting.clear();
        confirmed.clear();
        attempts.clear();
    }
}
