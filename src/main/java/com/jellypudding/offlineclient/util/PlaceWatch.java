package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.world.phys.Vec3;

// Notices a run that works one place turning up in a new world somewhere else. A portal or a
// lobby or another server counts. A rejoin where you stood does not and the run carries on after
// a kick that AutoReconnect mends.
public final class PlaceWatch {

    // A rejoin puts you where the server last had you. Moves still on their way at the kick can
    // leave that a step or two behind.
    private static final double SAME_SPOT = 4;

    private final WorldWatch world = new WorldWatch();
    private String worldKey;
    private Vec3 lastAt;

    // The next world seen is taken as the run's own.
    public void clear() {
        worldKey = null;
        lastAt = null;
    }

    // Asked on every tick of the run. True when you came into a new world anywhere but the server
    // and the dimension and the spot of the tick before.
    public boolean movedAway() {
        Vec3 at = OfflineClient.MC.player.position();
        String key = ServerInfo.worldKey();
        boolean away = world.changed() && lastAt != null
            && (!key.equals(worldKey) || at.distanceTo(lastAt) > SAME_SPOT);
        worldKey = key;
        lastAt = at;
        return away;
    }
}
