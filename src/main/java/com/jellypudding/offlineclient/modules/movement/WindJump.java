package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.WindLaunch;

public final class WindJump extends Module {

    private static final int WAIT_TICKS = 60;

    private final BoolSetting fromInventory = new BoolSetting("Take from inventory",
        "Also throws wind charges from the rest of your inventory and puts the stack back afterwards.",
        true);

    private final WindLaunch launch = new WindLaunch();
    private int waited;

    public WindJump() {
        super("WindJump", "Throws a wind charge at your feet and jumps with it to fly as high as a charge can send you.",
            Category.MOVEMENT);
        addSettings(fromInventory);
        searchTags("wind charge", "launch", "high jump", "boost");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    protected void onEnable() {
        waited = 0;
        if (!inGame() || Hands.tied()) {
            setEnabled(false);
            return;
        }
        if (!WindLaunch.hasCharge(fromInventory.isOn())) {
            disable("No wind charge to throw.");
        }
    }

    @Override
    protected void onDisable() {
        launch.stop();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (launch.tick()) {
            setEnabled(false);
            return;
        }
        if (launch.start(fromInventory.isOn())) {
            return;
        }
        if (!WindLaunch.hasCharge(fromInventory.isOn())) {
            disable("No wind charge to throw.");
        } else if (++waited > WAIT_TICKS) {
            disable(!mc.player.onGround() ? "You never landed to launch from."
                : mc.player.isUsingItem() ? "Let go of the item you are using to launch."
                : "The wind charge never cooled down.");
        }
    }
}
