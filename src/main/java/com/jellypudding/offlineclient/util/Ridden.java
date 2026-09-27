package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

// The mounts you have ridden. A horse keeps its owner on the server alone and a mount
// you have ridden is taken to be yours.
public enum Ridden {
    INSTANCE;

    // Far more mounts than anyone rides in one sitting.
    private static final int KEPT = 256;

    private static final Set<UUID> mounts = Collections.newSetFromMap(new BoundedMap<>(KEPT));

    @Subscribe
    private void onTick(TickEvent event) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null && player.getVehicle() != null) {
            mounts.add(player.getVehicle().getUUID());
        }
    }

    public static boolean has(Entity entity) {
        return mounts.contains(entity.getUUID());
    }
}
