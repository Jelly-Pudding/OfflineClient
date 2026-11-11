package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ChunkDataEvent;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;

import java.util.HashSet;
import java.util.Set;

// The record of the land you explore on the server you play on. The world map and the minimap
// both draw from it. It runs whilst either module is on and neither needs the other.
public final class ExploredLand {

    private static final ExploredLand INSTANCE = new ExploredLand();

    private final BoolSetting save = new BoolSetting("Save map",
        "Keeps the land you explored for your next visit. The Minimap draws from the same record.", true);
    private final TerrainCache terrain = new TerrainCache(save);
    // The modules that are on and draw the land.
    private final Set<Module> keepers = new HashSet<>();
    private int caveChunks;

    private ExploredLand() {
    }

    // The switch that keeps the record in files. One module lists it and the record obeys it
    // whichever module keeps it running.
    public static BoolSetting saveSetting() {
        return INSTANCE.save;
    }

    // A module that draws the land calls this as it starts. The first one starts the record.
    public static void keep(Module module) {
        if (INSTANCE.keepers.add(module) && INSTANCE.keepers.size() == 1) {
            INSTANCE.terrain.start();
            OfflineClient.INSTANCE.getEventBus().register(INSTANCE);
        }
    }

    // The same module calls this as it stops. The record stops with the last one.
    public static void release(Module module) {
        if (INSTANCE.keepers.remove(module) && INSTANCE.keepers.isEmpty()) {
            OfflineClient.INSTANCE.getEventBus().unregister(INSTANCE);
            INSTANCE.terrain.stop();
        }
    }

    // The land of the dimension you are in. Null before the first tick in a world.
    public static TerrainLayer here() {
        return INSTANCE.terrain.here();
    }

    // The land at your own height round you. Empty whilst no map asks for it.
    public static TerrainLayer cave() {
        return INSTANCE.terrain.cave();
    }

    // Keeps that many chunks of the land at your own height round you up to date. Nought lets
    // it go.
    public static void wantCave(int chunks) {
        INSTANCE.caveChunks = chunks;
    }

    // The height to stand at in a column of the land.
    public static int standY(int x, int z) {
        return INSTANCE.terrain.standY(x, z);
    }

    @Subscribe
    private void onChunkData(ChunkDataEvent event) {
        terrain.arrived(event.getChunk());
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        terrain.noteChange(event.getPacket());
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        terrain.tick(caveChunks);
    }
}
