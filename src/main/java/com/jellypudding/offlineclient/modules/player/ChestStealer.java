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
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class ChestStealer extends Module {

    public enum ListMode { WHITELIST, BLACKLIST }

    private final NumberSetting delay = new NumberSetting("Delay",
        "Milliseconds between each item grab.", 50, 0, 500, 10, "ms").min(0).max(5000);
    private final NumberSetting initialDelay = new NumberSetting("Initial delay",
        "Milliseconds to wait before the first grab of a container.", 50, 0, 1000, 10, "ms")
        .min(0).max(5000);
    private final NumberSetting jitter = new NumberSetting("Jitter",
        "Adds up to this many milliseconds at random to each grab.", 50, 0, 500, 10, "ms")
        .min(0).max(1000);
    private final EnumSetting<ListMode> listMode = new EnumSetting<>("List mode",
        "What the list means.", ListMode.BLACKLIST)
        .describe(ListMode.WHITELIST, "Takes only the listed items.")
        .describe(ListMode.BLACKLIST, "Takes everything except the listed items.");
    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "The items the list applies to. Click to pick them.", BuiltInRegistries.ITEM,
        List.of());
    private final RegistryListSetting<MenuType<?>> screens = new RegistryListSetting<>("Screens",
        "Which container screens are stolen from. Click to pick them.", BuiltInRegistries.MENU,
        List.of(MenuType.GENERIC_9x3, MenuType.GENERIC_9x6, MenuType.SHULKER_BOX,
            MenuType.HOPPER, MenuType.GENERIC_3x3));
    private final BoolSetting throwOut = new BoolSetting("Throw out",
        "Throws each item on the ground instead of into your inventory.", false);
    private final BoolSetting backwards = new BoolSetting("Throw backwards",
        "Turns you around whilst throwing so the pile lands behind you.", false)
        .under(throwOut);
    private final BoolSetting buttons = new BoolSetting("Buttons",
        "Draws Steal and Dump buttons above every container screen.", true);
    private final BoolSetting close = new BoolSetting("Close when done",
        "Close the container once everything is taken.", false);

    private long nextClick;
    private int lastSlot = -1;
    private int lastCount = -1;
    private boolean inventoryFull;
    private boolean open;
    // Set by the dump button. One stack moves per tick until the pass is done.
    private boolean dumpOnce;

    public ChestStealer() {
        super("ChestStealer", "Takes everything out of containers for you.", Category.PLAYER);
        addSettings(delay, initialDelay, jitter, listMode, items, screens, throwOut, backwards,
            buttons, close);
        searchTags("loot", "chest", "filter", "steal", "dump");
    }

    @Override
    public String getSuffix() {
        return items.size() == 0 ? null : listMode.getValueString();
    }

    public boolean showsButtons() {
        return isEnabled() && buttons.isOn();
    }

    // A button press makes the next grab due at once.
    public void stealNow() {
        nextClick = 0;
    }

    public void dumpNow() {
        dumpOnce = true;
        nextClick = 0;
    }

    // The screens the module works in. A menu built without a type is skipped.
    public boolean handles(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return false;
        }
        MenuType<?> type = container.getMenu().menuType;
        return type != null && screens.contains(type);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (!handles(mc.gui.screen())) {
            forget();
            return;
        }
        if (!open) {
            open = true;
            nextClick = System.currentTimeMillis() + initialDelay.getInt();
        }
        if (dumpOnce) {
            dumpOnce = false;
            dumpPass();
            return;
        }
        if (inventoryFull && !throwOut.isOn()) {
            if (mc.player.getInventory().getFreeSlot() == -1) {
                return;
            }
            // The refused slot has to look new again or the next pass skips it.
            lastSlot = -1;
            lastCount = -1;
            inventoryFull = false;
        }
        if (System.currentTimeMillis() < nextClick) {
            return;
        }

        AbstractContainerMenu menu = mc.player.containerMenu;
        int containerSlots = menu.slots.size() - InventoryUtil.WHOLE_INVENTORY;
        if (containerSlots <= 0) {
            return;
        }

        for (int i = 0; i < containerSlots; i++) {
            Slot slot = menu.slots.get(i);
            if (!slot.hasItem() || !wanted(slot.getItem())) {
                continue;
            }
            // A click that moved nothing means the inventory is full.
            if (i == lastSlot && slot.getItem().getCount() == lastCount) {
                inventoryFull = true;
                return;
            }
            lastSlot = i;
            lastCount = slot.getItem().getCount();
            take(menu, i);
            waitAgain();
            return;
        }

        if (close.isOn()) {
            mc.player.closeContainer();
        }
    }

    private void take(AbstractContainerMenu menu, int slot) {
        if (!throwOut.isOn()) {
            mc.gameMode.handleContainerInput(menu.containerId, slot, 0,
                ContainerInput.QUICK_MOVE, mc.player);
            return;
        }
        if (backwards.isOn()) {
            RotationManager.requestExact(mc.player.getYRot() + 180f, mc.player.getXRot(),
                RotationPriority.IDLE);
        }
        // Button one throws the whole stack straight onto the ground.
        mc.gameMode.handleContainerInput(menu.containerId, slot, 1,
            ContainerInput.THROW, mc.player);
    }

    // Moves everything the filter does not want back into the container.
    private void dumpPass() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        InventoryTweaks tweaks = Modules.get(InventoryTweaks.class);
        for (int i = menu.slots.size() - InventoryUtil.WHOLE_INVENTORY; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            if (tweaks != null && !tweaks.dumps(stack)) {
                continue;
            }
            mc.gameMode.handleContainerInput(menu.containerId, i, 0,
                ContainerInput.QUICK_MOVE, mc.player);
            waitAgain();
            dumpOnce = true;
            return;
        }
    }

    private void waitAgain() {
        int wait = delay.getInt();
        if (jitter.getInt() > 0) {
            wait += ThreadLocalRandom.current().nextInt(jitter.getInt() + 1);
        }
        nextClick = System.currentTimeMillis() + wait;
    }

    private void forget() {
        nextClick = 0;
        lastSlot = -1;
        lastCount = -1;
        inventoryFull = false;
        open = false;
        dumpOnce = false;
    }

    // An empty blacklist leaves every item wanted.
    private boolean wanted(ItemStack stack) {
        return items.contains(stack.getItem()) == listMode.is(ListMode.WHITELIST);
    }
}
