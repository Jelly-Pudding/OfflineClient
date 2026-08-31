package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class AutoDrop extends Module {

    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "The items to throw away. Click to pick them.", BuiltInRegistries.ITEM, ItemUtil.JUNK);
    private final BoolSetting hotbar = new BoolSetting("Hotbar too",
        "Also throw junk that lands in your hotbar.", true);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between each throw.", 2, 0, 20, 1, " ticks");

    private int timer;

    public AutoDrop() {
        super("AutoDrop", "Throws away junk items as they enter your inventory.", Category.PLAYER);
        addSettings(items, hotbar, delay);
        searchTags("inventory cleaner", "junk", "throw");
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (!InventoryUtil.canClick() || !InventoryUtil.carried().isEmpty()) {
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        int first = hotbar.isOn() ? 0 : 9;
        for (int i = first; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || !items.contains(stack.getItem())) {
                continue;
            }
            int networkSlot = InventoryUtil.networkSlot(i);
            // Button 1 throws the whole stack in one click.
            mc.gameMode.handleContainerInput(0, networkSlot, 1, ContainerInput.THROW, mc.player);
            timer = delay.getInt();
            return;
        }
    }
}
