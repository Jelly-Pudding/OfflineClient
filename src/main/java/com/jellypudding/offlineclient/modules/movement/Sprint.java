package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;

public final class Sprint extends Module {

    public Sprint() {
        super("Sprint", "Automatically sprints whenever you move forward.", Category.MOVEMENT);
    }

    /**
     * Runs at the end of the client tick. The player tick reevaluates
     * and clears sprint set any earlier.
     */
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!inGame()) {
            return;
        }
        if (mc.player.input.hasForwardImpulse() && !mc.player.isUsingItem()) {
            mc.player.setSprinting(true);
        }
    }
}
