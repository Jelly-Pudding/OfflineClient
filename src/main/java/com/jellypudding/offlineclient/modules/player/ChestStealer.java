package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class ChestStealer extends Module {

    public enum ListMode { WHITELIST, BLACKLIST }

    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between each item grab.", 1, 0, 10, 1, " ticks");
    private final EnumSetting<ListMode> listMode = new EnumSetting<>("List mode",
        "Whitelist takes only the listed items. Blacklist takes everything else.",
        ListMode.BLACKLIST);
    private final RegistryListSetting<Item> items = new RegistryListSetting<Item>("Items",
        "The items the list applies to. Click to pick them.", BuiltInRegistries.ITEM,
        List.of());
    private final BoolSetting close = new BoolSetting("Close when done",
        "Close the container once everything is taken.", false);

    private int timer;
    private int lastSlot = -1;
    private int lastCount = -1;
    private boolean inventoryFull;

    public ChestStealer() {
        super("ChestStealer", "Takes everything out of containers for you.", Category.PLAYER);
        addSettings(delay, listMode, items, close);
        searchTags("loot", "chest", "filter");
    }

    @Override
    public String getSuffix() {
        return items.size() == 0 ? null : listMode.getValueString();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (!InventoryUtil.isStorage(mc.gui.screen())) {
            timer = 0;
            lastSlot = -1;
            lastCount = -1;
            inventoryFull = false;
            return;
        }
        if (inventoryFull) {
            if (mc.player.getInventory().getFreeSlot() == -1) {
                return;
            }
            // The refused slot has to look new again or the next pass skips it.
            lastSlot = -1;
            lastCount = -1;
            inventoryFull = false;
        }

        if (timer > 0) {
            timer--;
            return;
        }

        AbstractContainerMenu menu = mc.player.containerMenu;
        int containerSlots = menu.slots.size() - 36;
        if (containerSlots <= 0) {
            return;
        }

        for (int i = 0; i < containerSlots; i++) {
            Slot slot = menu.slots.get(i);
            if (!slot.hasItem()) {
                continue;
            }
            if (!wanted(slot.getItem())) {
                continue;
            }
            // A click that moved nothing means the inventory is full.
            if (i == lastSlot && slot.getItem().getCount() == lastCount) {
                inventoryFull = true;
                return;
            }
            lastSlot = i;
            lastCount = slot.getItem().getCount();
            mc.gameMode.handleContainerInput(menu.containerId, i, 0,
                ContainerInput.QUICK_MOVE, mc.player);
            timer = delay.getInt();
            return;
        }

        if (close.isOn()) {
            mc.player.closeContainer();
        }
    }

    // An empty blacklist leaves every item wanted.
    private boolean wanted(ItemStack stack) {
        return items.contains(stack.getItem()) == listMode.is(ListMode.WHITELIST);
    }
}
