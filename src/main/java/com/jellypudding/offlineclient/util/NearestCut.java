package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleBiFunction;

// The nearest part of a list once it outgrows a limit. The sort only runs again when the
// list or the limit or the chunk the player stands in changes.
public final class NearestCut<T> {

    private final ToDoubleBiFunction<T, Vec3> distanceSqr;
    private List<T> source;
    private long chunk;
    private int limit = -1;
    private List<T> cut = List.of();

    // The distance is measured from the player's eyes.
    public NearestCut(ToDoubleBiFunction<T, Vec3> distanceSqr) {
        this.distanceSqr = distanceSqr;
    }

    // True when the cut changed.
    public boolean update(List<T> all, int max) {
        long now = ChunkPos.pack(OfflineClient.MC.player.blockPosition());
        if (all == source && now == chunk && max == limit) {
            return false;
        }
        source = all;
        chunk = now;
        limit = max;
        if (all.size() > max) {
            Vec3 eye = OfflineClient.MC.player.getEyePosition();
            List<T> sorted = new ArrayList<>(all);
            sorted.sort(Comparator.comparingDouble(item -> distanceSqr.applyAsDouble(item, eye)));
            all = sorted.subList(0, max);
        }
        cut = all;
        return true;
    }

    public List<T> result() {
        return cut;
    }

    public void clear() {
        source = null;
        limit = -1;
        cut = List.of();
    }
}
