package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.modules.render.Waypoints;
import com.jellypudding.offlineclient.util.LifeWatch;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.screens.DeathScreen;

public final class AutoRespawn extends Module {

    private final BoolSetting deathButton = new BoolSetting("Death screen button",
        "Adds a button to the death screen that turns this on when you die with it off.", true);

    // The body a respawn was asked for. The server answers with a fresh body whose arrival
    // closes the death screen.
    private final LifeWatch asked = new LifeWatch();

    public AutoRespawn() {
        super("AutoRespawn", "Instantly respawns you after dying.", Category.PLAYER);
        addSettings(deathButton);
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.RESPAWN;
    }

    // Read by DeathScreenMixin whilst the module is off.
    public boolean showsButton() {
        return deathButton.isOn();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.gui.screen() instanceof DeathScreen && asked.changed()) {
            Waypoints waypoints = Modules.get(Waypoints.class);
            if (waypoints != null) {
                waypoints.markDeath(mc.player.position());
            }
            mc.player.respawn();
        }
    }
}
