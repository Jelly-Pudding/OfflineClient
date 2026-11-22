package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.HashMap;
import java.util.Map;

// Blocks tick one ring past the simulation distance and a flow or a spreading plant there reaches
// one ring further. Each chunk keeps the last time it lay in that reach or stayed loaded on your
// client after. It runs whatever module is on.
public final class OwnSimulation {

    public static final OwnSimulation INSTANCE = new OwnSimulation();

    private static final int TICKING_MARGIN = 1;
    private static final int SPILL_RINGS = 1;
    private static final int NOTE_TICKS = 20;
    // Far more chunks than a long journey passes through. A full world forgets the chunk it
    // reached longest ago.
    private static final int KEPT = 65536;

    // By dimension of the server you play on.
    private final Map<String, Map<Long, Long>> byWorld = new HashMap<>();
    private final ServerWatch server = new ServerWatch();
    private int ticks;

    private OwnSimulation() {
    }

    public static int tickingRings() {
        return OfflineClient.MC.player.connection.serverSimulationDistance + TICKING_MARGIN;
    }

    public static int reachRings() {
        return tickingRings() + SPILL_RINGS;
    }

    public static boolean reached(long chunk) {
        Map<Long, Long> here = INSTANCE.byWorld.get(ServerInfo.worldKey());
        return inReach(chunk) || here != null && here.containsKey(chunk);
    }

    // A look comes once a second.
    public static boolean reachedWithin(long chunk, long ms) {
        Map<Long, Long> here = INSTANCE.byWorld.get(ServerInfo.worldKey());
        Long last = here == null ? null : here.get(chunk);
        return last != null && System.currentTimeMillis() - last < ms;
    }

    private static boolean inReach(long chunk) {
        ChunkPos me = OfflineClient.MC.player.chunkPosition();
        return Mth.chessboardDistance(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), me.x(), me.z()) <= reachRings();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (server.changed()) {
            byWorld.clear();
        }
        if (++ticks >= NOTE_TICKS) {
            ticks = 0;
            note();
        }
    }

    // The server may tick a chunk before it sends it and the chunks in your reach count whether
    // your client holds them yet or not. A chunk reached before keeps its time fresh whilst your
    // client still holds it.
    private void note() {
        LocalPlayer player = OfflineClient.MC.player;
        Map<Long, Long> here = byWorld.computeIfAbsent(ServerInfo.worldKey(), key -> new BoundedMap<>(KEPT, true));
        long now = System.currentTimeMillis();
        ChunkPos me = player.chunkPosition();
        int reach = reachRings();
        int view = Math.max(player.connection.serverChunkRadius, reach);
        ClientChunkCache cache = OfflineClient.MC.level.getChunkSource();
        for (int dx = -view; dx <= view; dx++) {
            for (int dz = -view; dz <= view; dz++) {
                int x = me.x() + dx;
                int z = me.z() + dz;
                long key = ChunkPos.pack(x, z);
                boolean reached = Math.max(Math.abs(dx), Math.abs(dz)) <= reach;
                if (reached || (here.containsKey(key) && cache.getChunk(x, z, ChunkStatus.FULL, false) != null)) {
                    here.put(key, now);
                }
            }
        }
    }
}
