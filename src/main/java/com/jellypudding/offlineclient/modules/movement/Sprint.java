package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;

public final class Sprint extends Module {

    private final BoolSetting anyDirection = new BoolSetting("Any direction",
        "Also sprints sideways and backwards.", false);
    private final BoolSetting whilstHungry = new BoolSetting("Whilst hungry",
        "Sprints even when the hunger bar is too low for it.", false);

    public Sprint() {
        super("Sprint", "Automatically sprints whenever you move.", Category.MOVEMENT);
        addSettings(anyDirection, whilstHungry);
        searchTags("auto sprint", "omnidirectional");
    }

    // Read by ClientInputMixin.
    public boolean sprintsAnyDirection() {
        return isEnabled() && anyDirection.isOn();
    }

    // Read by FoodDataMixin.
    public boolean sprintsHungry() {
        return isEnabled() && whilstHungry.isOn();
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
