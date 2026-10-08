package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

// What your ender chest held the last time you had it open. The copy is taken again every
// tick it stays open and is dropped when you leave the world since it belongs to that server.
public final class EnderChest {

    public static final EnderChest INSTANCE = new EnderChest();

    private static final int SLOTS = 27;

    private final List<ItemStack> remembered = new ArrayList<>();

    private EnderChest() {
    }

    // The slots in order with the empty ones in place. Empty until you open your ender chest.
    public List<ItemStack> contents() {
        return List.copyOf(remembered);
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        Minecraft mc = OfflineClient.MC;
        if (mc.level == null) {
            remembered.clear();
            return;
        }
        if (!(mc.gui.screen() instanceof ContainerScreen screen) || !InventoryUtil.isEnderChest(screen)) {
            return;
        }
        Container container = screen.getMenu().getContainer();
        if (container.getContainerSize() < SLOTS) {
            return;
        }
        remembered.clear();
        for (int i = 0; i < SLOTS; i++) {
            remembered.add(container.getItem(i).copy());
        }
    }
}
