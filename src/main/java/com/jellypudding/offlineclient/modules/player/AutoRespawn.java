package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.modules.render.Waypoints;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.screens.DeathScreen;

public final class AutoRespawn extends Module {

    private final BoolSetting deathButton = new BoolSetting("Death screen button",
        "Adds a button to the death screen that turns this on when you die with it off.", true);

    public AutoRespawn() {
        super("AutoRespawn", "Instantly respawns you after dying.", Category.PLAYER);
        addSettings(deathButton);
    }

    // Read by DeathScreenMixin whilst the module is off.
    public boolean showsButton() {
        return deathButton.isOn();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (mc.gui.screen() instanceof DeathScreen) {
            Waypoints waypoints = Modules.get(Waypoints.class);
            if (waypoints != null) {
                waypoints.markDeath(mc.player.position());
            }
            // Closing the screen stops repeat respawn packets.
            mc.player.respawn();
            mc.gui.setScreen(null);
        }
    }
}
