package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;

public final class AutoTotem extends Module {

    private static final int OFFHAND_SLOT = 45;

    private final NumberSetting health = new NumberSetting("Health",
        "Only equip a totem at or below this many hearts. 0 = always.", 0, 0, 10, 0.5, " hearts");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait before equipping the next totem.", 0, 0, 20, 1, " ticks");

    private int returnSlot = -1;
    private int totems;
    private int timer;
    private boolean hadTotem;

    public AutoTotem() {
        super("AutoTotem", "Keeps a totem of undying in your offhand.", Category.COMBAT);
        addSettings(health, delay);
    }

    @Override
    public String getSuffix() {
        return totems + " left";
    }

    @Override
    protected void onEnable() {
        returnSlot = -1;
        timer = 0;
        hadTotem = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }

        // Finish the swap that started last tick.
        if (returnSlot != -1) {
            click(returnSlot);
            returnSlot = -1;
        }

        int totemSlot = findTotem();

        if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
            hadTotem = true;
            return;
        }
        if (hadTotem) {
            timer = delay.getInt();
            hadTotem = false;
        }
        if (totemSlot == -1) {
            return;
        }

        float minHealth = health.getFloat();
        if (minHealth > 0 && mc.player.getHealth() > minHealth * 2f) {
            return;
        }

        // Don't touch slots while a chest or similar container is open.
        if (mc.gui.screen() instanceof AbstractContainerScreen
            && !(mc.gui.screen() instanceof InventoryScreen
                || mc.gui.screen() instanceof CreativeModeInventoryScreen)) {
            return;
        }

        if (timer > 0) {
            timer--;
            return;
        }

        boolean offhandEmpty = mc.player.getOffhandItem().isEmpty();
        click(totemSlot);
        click(OFFHAND_SLOT);
        if (!offhandEmpty) {
            returnSlot = totemSlot;
        }
    }

    private void click(int networkSlot) {
        mc.gameMode.handleContainerInput(0, networkSlot, 0, ContainerInput.PICKUP, mc.player);
    }

    /** Returns the network slot of the first totem in the inventory or minus one. */
    private int findTotem() {
        // The one already equipped counts toward the total on the HUD.
        totems = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
            ? mc.player.getOffhandItem().getCount() : 0;
        int found = -1;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) {
                totems += mc.player.getInventory().getItem(i).getCount();
                if (found == -1) {
                    // A hotbar slot maps to network slot 36 plus its index.
                    found = i < 9 ? 36 + i : i;
                }
            }
        }
        return found;
    }
}
