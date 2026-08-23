package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/**
 * Other items are handled by FastUse. Both share the delay cap in
 * FastUse.capUseDelay.
 */
public final class FastPlace extends Module {

    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between placing blocks. Vanilla waits 4.", 0, 0, 4, 1, " ticks");

    public FastPlace() {
        super("FastPlace", "Removes the delay between placing blocks.", Category.PLAYER);
        addSettings(delay);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty()) {
            held = mc.player.getOffhandItem();
        }
        if (held.getItem() instanceof BlockItem) {
            FastUse.capUseDelay(delay.getInt());
        }
    }
}
