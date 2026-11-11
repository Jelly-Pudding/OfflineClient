package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;

import java.lang.ref.WeakReference;

// Desync tells the server a menu closed whilst its screen stays open. Resync makes the server
// send every slot of the menu again.
public final class MenuTools extends Module {

    // The server counts the state of a menu from nought to 32767. A click carrying this never
    // matches and the server answers it with the whole menu.
    private static final int STALE_STATE = -1;

    private final BoolSetting buttons = new BoolSetting("Buttons",
        "Shows the Desync and Resync buttons above chests and other menus.", true);
    private final BoolSetting slotNumbers = new BoolSetting("Slot numbers",
        "Writes each slot's number over it in menus.", false);

    // The menu the server was last told closed. Ids come round again and only the menu itself
    // tells two apart. Held weakly because a menu reaches its player and through it the world.
    private WeakReference<AbstractContainerMenu> desynced = new WeakReference<>(null);

    public MenuTools() {
        super("MenuTools", "Adds buttons to menus that desync them or fetch your items from the server again.",
            Category.PLAYER);
        addSettings(buttons, slotNumbers);
        searchTags("desync", "resync", "slot numbers", "ghost items", "close without packet");
    }

    // The creative screen keeps a menu of its own the server never sees.
    public boolean showsButtons(Screen screen) {
        return isEnabled() && buttons.isOn() && !(screen instanceof CreativeModeInventoryScreen);
    }

    // Your own inventory has nothing to desync. The server goes straight back to it after a close.
    public boolean showsDesync(Screen screen) {
        return showsButtons(screen) && !(screen instanceof InventoryScreen);
    }

    public boolean showsSlotNumbers() {
        return isEnabled() && slotNumbers.isOn();
    }

    // The server closes the menu and the screen stays open on your side.
    public void desync(AbstractContainerMenu menu) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return;
        }
        connection.send(new ServerboundContainerClosePacket(menu.containerId));
        desynced = new WeakReference<>(menu);
        tell("told the server the menu closed.");
    }

    // A middle click outside the window does nothing whatever the server thinks the cursor holds.
    // Its stale state makes the server send the whole menu after it. Once the server has shut a
    // menu it only answers for your own inventory.
    public void resync(AbstractContainerMenu menu) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return;
        }
        boolean shut = menu == desynced.get();
        connection.send(new ServerboundContainerClickPacket(shut ? InventoryMenu.CONTAINER_ID : menu.containerId,
            STALE_STATE, (short) AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, (byte) 0, ContainerInput.CLONE,
            Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
        if (shut) {
            tell("can only fetch your own items as the server shut this menu. "
                + "Open it again to see what it holds.");
        }
    }
}
