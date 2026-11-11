package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.EntityDamageEvent;

// Counts the hits the server reports on you. A hit that lands whilst you still flash from the
// last one is never reported. Only the count is kept. What dealt a hit points at its world.
public final class HitsTaken {

    public static final HitsTaken INSTANCE = new HitsTaken();

    private int count;

    private HitsTaken() {
    }

    // How many hits there have been since the game started. It only ever goes up.
    public int count() {
        return count;
    }

    @Subscribe
    private void onDamage(EntityDamageEvent event) {
        if (event.getEntity() == OfflineClient.MC.player) {
            count++;
        }
    }
}
