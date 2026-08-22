package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Throws out junk the moment it lands in the inventory.
 */
public final class AutoDrop extends Module {

    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "The items to throw away. Click to pick them.", BuiltInRegistries.ITEM,
        List.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.GRAVEL,
            Items.NETHERRACK, Items.ROTTEN_FLESH, Items.POISONOUS_POTATO, Items.WHEAT_SEEDS));
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
        // Only the survival inventory. Chests and crafting tables use other slot numbers.
        if (mc.gui.screen() instanceof AbstractContainerScreen
            && !(mc.gui.screen() instanceof InventoryScreen)) {
            return;
        }
        if (mc.player.containerMenu.containerId != 0 || !mc.player.containerMenu.getCarried().isEmpty()) {
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        int first = hotbar.isOn() ? 0 : 9;
        for (int i = first; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || !items.contains(stack.getItem())) {
                continue;
            }
            int networkSlot = i < 9 ? 36 + i : i;
            // Button 1 throws the whole stack in one click.
            mc.gameMode.handleContainerInput(0, networkSlot, 1, ContainerInput.THROW, mc.player);
            timer = delay.getInt();
            return;
        }
    }
}
