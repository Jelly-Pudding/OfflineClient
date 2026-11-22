package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.NameStore;
import com.jellypudding.offlineclient.config.RememberedEnderChests;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

// The copy is taken again every tick the chest stays open and is kept in a file for the server and
// the name you play under.
public final class EnderChest {

    public static final EnderChest INSTANCE = new EnderChest();

    private final RememberedEnderChests store = new RememberedEnderChests();

    private EnderChest() {
    }

    // The slots in order with the empty ones in place. Empty outside a world and until you
    // have opened your ender chest on this server.
    public List<ItemStack> contents() {
        return OfflineClient.MC.level == null ? List.of() : store.of(NameStore.ownName());
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        Minecraft mc = OfflineClient.MC;
        if (mc.level == null || Modules.dormant()) {
            return;
        }
        // The file is read as you arrive. A chest opened before it is in is copied a tick later.
        store.ready();
        if (!(mc.gui.screen() instanceof ContainerScreen screen) || !InventoryUtil.isEnderChest(screen)) {
            return;
        }
        Container container = screen.getMenu().getContainer();
        if (container.getContainerSize() < RememberedEnderChests.SLOTS) {
            return;
        }
        List<ItemStack> held = new ArrayList<>();
        for (int i = 0; i < RememberedEnderChests.SLOTS; i++) {
            held.add(container.getItem(i).copy());
        }
        store.update(NameStore.ownName(), held);
    }
}
