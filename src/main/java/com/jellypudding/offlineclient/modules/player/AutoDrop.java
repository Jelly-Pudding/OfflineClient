package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class AutoDrop extends Module {

    // Where the main grid and the hotbar start in network slot order.
    private static final int MAIN_START = 9;
    private static final int HOTBAR_START = 36;

    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "The items to throw away. Click to pick them.", BuiltInRegistries.ITEM, ItemUtil.JUNK);
    private final BoolSetting hotbar = new BoolSetting("Hotbar too",
        "Also throw junk that lands in your hotbar.", true);
    private final BoolSetting worn = new BoolSetting("Worn too",
        "Also throw junk you are wearing or holding in the offhand.", false);
    private final BoolSetting fullStacksOnly = new BoolSetting("Full stacks only",
        "Only throw a stack once it has filled up.", false);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between each throw.", 2, 0, 20, 1, " ticks");

    private final BoolSetting guard = new BoolSetting("Guard items",
        "Stops you throwing away the items below by hand.", false);
    private final RegistryListSetting<Item> guarded = new RegistryListSetting<>("Guarded",
        "Items you are never allowed to throw. Click to pick them.", BuiltInRegistries.ITEM,
        List.of())
        .under(guard);
    private final BoolSetting guardFrames = new BoolSetting("Guard frames",
        "Also stops a guarded item going into an item frame or a pot.", true)
        .under(guard);
    private final KeybindSetting overrideKey = new KeybindSetting("Override key",
        "Holding this key lets a guarded item through.", KeybindSetting.UNBOUND)
        .under(guard);

    private int timer;

    public AutoDrop() {
        super("AutoDrop", "Throws away junk items as they enter your inventory.", Category.PLAYER);
        addSettings(items, hotbar, worn, fullStacksOnly, delay, guard, guarded, guardFrames,
            overrideKey);
        searchTags("inventory cleaner", "junk", "throw", "anti drop");
    }

    // True when the game must refuse to throw this stack away.
    public boolean guards(ItemStack stack) {
        return isEnabled() && guard.isOn() && !stack.isEmpty()
            && guarded.contains(stack.getItem()) && !overrideKey.isHeld();
    }

    public boolean guardsFrames() {
        return guardFrames.isOn();
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

        List<Slot> slots = mc.player.inventoryMenu.slots;
        for (int netSlot = 0; netSlot < slots.size(); netSlot++) {
            if (!scans(netSlot)) {
                continue;
            }
            ItemStack stack = slots.get(netSlot).getItem();
            if (stack.isEmpty() || !items.contains(stack.getItem())) {
                continue;
            }
            if (fullStacksOnly.isOn() && stack.getCount() < stack.getMaxStackSize()) {
                continue;
            }
            // Button 1 throws the whole stack in one click.
            mc.gameMode.handleContainerInput(0, netSlot, 1, ContainerInput.THROW, mc.player);
            timer = delay.getInt();
            return;
        }
    }

    // Network slots nine to thirty five are the main grid and the rest are named.
    private boolean scans(int netSlot) {
        if (netSlot >= MAIN_START && netSlot < HOTBAR_START) {
            return true;
        }
        if (netSlot >= HOTBAR_START && netSlot < InventoryUtil.OFFHAND_SLOT) {
            return hotbar.isOn();
        }
        boolean armour = netSlot >= InventoryUtil.ARMOR_START && netSlot < MAIN_START;
        return worn.isOn() && (armour || netSlot == InventoryUtil.OFFHAND_SLOT);
    }
}
