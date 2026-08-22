package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import net.minecraft.client.gui.screens.DeathScreen;

public final class AutoRespawn extends Module {

    public AutoRespawn() {
        super("AutoRespawn", "Instantly respawns you after dying.", Category.PLAYER);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (mc.gui.screen() instanceof DeathScreen) {
            // Closing the screen stops repeat respawn packets.
            mc.player.respawn();
            mc.gui.setScreen(null);
        }
    }
}
