package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import net.minecraft.world.entity.player.Inventory;

// A troll. The held slot walks along the hotbar and wraps round.
public final class AutoSwitch extends Module {

    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks the hand rests on each slot.", 1, 1, 20, 1, " ticks").min(1);

    private int timer;

    public AutoSwitch() {
        super("AutoSwitch", "Cycles through your hotbar slots on their own.", Category.PLAYER);
        addSettings(delay);
        searchTags("auto switch", "hotbar cycle", "troll");
    }

    @Override
    protected void onEnable() {
        timer = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || ++timer < delay.getInt()) {
            return;
        }
        timer = 0;
        Inventory inventory = mc.player.getInventory();
        inventory.setSelectedSlot((inventory.getSelectedSlot() + 1) % InventoryUtil.HOTBAR_SIZE);
    }
}
