package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

public final class ChestStealer extends Module {

    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between each item grab.", 1, 0, 10, 1, " ticks");
    private final BoolSetting close = new BoolSetting("Close when done",
        "Close the container once everything is taken.", false);

    private int timer;
    private int lastSlot = -1;
    private int lastCount = -1;
    private boolean inventoryFull;

    public ChestStealer() {
        super("ChestStealer", "Takes everything out of containers for you.", Category.PLAYER);
        addSettings(delay, close);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (!(mc.gui.screen() instanceof AbstractContainerScreen)
            || mc.gui.screen() instanceof InventoryScreen
            || mc.gui.screen() instanceof CreativeModeInventoryScreen) {
            timer = 0;
            lastSlot = -1;
            lastCount = -1;
            inventoryFull = false;
            return;
        }
        if (inventoryFull) {
            return;
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
}
