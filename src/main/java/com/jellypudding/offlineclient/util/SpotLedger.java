package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.core.BlockPos;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

// Spots a module changed and the tick each one changed. A spot is undone once it has waited long
// enough. One that is gone by itself or has waited far too long is forgotten.
public final class SpotLedger {

    private static final int GIVE_UP = 100;

    private final Map<BlockPos, Integer> spots = new LinkedHashMap<>();

    public void add(BlockPos pos) {
        spots.put(pos, OfflineClient.MC.player.tickCount);
    }

    public void clear() {
        spots.clear();
    }

    public boolean isEmpty() {
        return spots.isEmpty();
    }

    // Spots that are gone or out of date are dropped on the way. True when one was undone.
    public boolean undoDue(int wait, Predicate<BlockPos> stillThere, Predicate<BlockPos> undo) {
        int now = OfflineClient.MC.player.tickCount;
        Iterator<Map.Entry<BlockPos, Integer>> it = spots.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            BlockPos pos = entry.getKey();
            int age = now - entry.getValue();
            // The clock starts again on a respawn and an age below nought is out of date.
            if (age < 0 || age > wait + GIVE_UP || !stillThere.test(pos)) {
                it.remove();
                continue;
            }
            if (age >= wait && undo.test(pos)) {
                it.remove();
                return true;
            }
        }
        return false;
    }
}
