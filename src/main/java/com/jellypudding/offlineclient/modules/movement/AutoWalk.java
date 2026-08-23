package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.mojang.blaze3d.platform.InputConstants;

public final class AutoWalk extends Module {

    private final BoolSetting autoSprint = new BoolSetting("Auto sprint",
        "Sprint instead of walking.", false);

    public AutoWalk() {
        super("AutoWalk", "Holds the forward key for you.", Category.MOVEMENT);
        addSettings(autoSprint);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        mc.options.keyUp.setDown(true);

        if (autoSprint.isOn()) {
            return;
        }
        // A hand started sprint ends on key release.
        boolean physicallyHeld = InputConstants.isKeyDown(
            mc.getWindow(), mc.options.keyUp.key.getValue());
        if (mc.player.isSprinting() && !physicallyHeld
            && !OfflineClient.INSTANCE.getModuleManager().get(Sprint.class).isEnabled()) {
            mc.player.setSprinting(false);
        }
    }

    // Sprint is set late in the tick. The player tick clears sprint set any earlier.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!inGame() || !autoSprint.isOn()) {
            return;
        }
        if (!mc.player.isUsingItem()) {
            mc.player.setSprinting(true);
        }
    }

    @Override
    protected void onDisable() {
        mc.options.keyUp.setDown(false);
    }
}
