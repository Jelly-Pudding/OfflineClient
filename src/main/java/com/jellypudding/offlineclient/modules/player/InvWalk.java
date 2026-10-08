package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.gui.ClientScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.InputUtil;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

import java.util.ArrayList;
import java.util.List;

// The real keyboard state is fed into the movement key mappings every tick whilst a screen allows it.
public final class InvWalk extends Module {

    public enum Screens { INVENTORY, GAME, CLIENT, BOTH }

    private final EnumSetting<Screens> screens = new EnumSetting<>("Screens",
        "Which open screens let you keep walking.", Screens.GAME)
        .describe(Screens.INVENTORY, "Only your own inventory and the creative menu.")
        .describe(Screens.GAME, "Inventories and chests and other game screens.")
        .describe(Screens.CLIENT, "Only the ClickGUI and the other screens of this client.")
        .describe(Screens.BOTH, "Every screen that does not take typing.");
    private final BoolSetting sneak = new BoolSetting("Sneak",
        "Also let the sneak key work.", true);
    private final BoolSetting sprint = new BoolSetting("Sprint",
        "Also let the sprint key work.", true);
    private final BoolSetting jump = new BoolSetting("Jump",
        "Also let the jump key work.", true);

    // The mappings set from the keyboard on the last tick. The game sets them itself with no screen open.
    private List<KeyMapping> fed = List.of();

    public InvWalk() {
        super("InvWalk", "Lets you walk about whilst a screen is open.", Category.PLAYER);
        addSettings(screens, sneak, sprint, jump);
        searchTags("inventory walk", "inv move", "menu walk", "gui move");
    }

    @Override
    protected void onDisable() {
        letGo();
    }

    // Puts up every key it holds. GuiMixin calls this before a screen opens or closes. The game
    // then sorts out its toggle keys from the keyboard alone.
    public void letGo() {
        feed(List.of());
    }

    // Read by Sprint. True whilst the sprint key still works in a screen.
    public boolean allowsSprint() {
        return sprint.isOn();
    }

    // Read by ScreenMixin. A space press should jump rather than press a focused button.
    public boolean takesSpace() {
        return isEnabled() && jump.isOn() && mc.options.keyJump.isDefault()
            && mc.gui.screen() != null && allows(mc.gui.screen());
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        Screen screen = mc.gui.screen();
        if (!inGame() || screen == null) {
            fed = List.of();
            return;
        }
        feed(allows(screen) ? keys() : List.of());
    }

    // Copies the keyboard into the given mappings. A mapping fed on the last tick and not
    // on this one goes up once and is then left alone.
    private void feed(List<KeyMapping> keys) {
        for (KeyMapping mapping : fed) {
            if (!keys.contains(mapping)) {
                InputUtil.setHeld(mapping, false);
            }
        }
        for (KeyMapping mapping : keys) {
            InputUtil.setHeld(mapping, InputUtil.physicallyHeld(mapping));
        }
        fed = keys;
    }

    // True whilst the screen is one the setting covers and nothing on it is typing.
    public boolean allows(Screen screen) {
        if (!walkable(screen)) {
            return false;
        }
        boolean own = screen instanceof ClientScreen;
        return switch (screens.getValue()) {
            case INVENTORY -> !own && isInventory(screen);
            case GAME -> !own;
            case CLIENT -> own;
            case BOTH -> true;
        };
    }

    // Screens that take typing keep the keyboard to themselves.
    public static boolean walkable(Screen screen) {
        if (screen instanceof ChatScreen || screen instanceof AbstractSignEditScreen) {
            return false;
        }
        if (screen instanceof ClientScreen own) {
            return !own.isTyping();
        }
        return !hasTextBox(screen.children(), screen instanceof CreativeModeInventoryScreen);
    }

    // A box to type in counts even inside a scrolling panel. The creative screen carries its
    // search box in every tab and only shows it in the search one.
    private static boolean hasTextBox(List<? extends GuiEventListener> children, boolean creative) {
        for (GuiEventListener child : children) {
            if (child instanceof MultiLineEditBox || (child instanceof EditBox box && (!creative || box.isVisible()))) {
                return true;
            }
            if (child instanceof ContainerEventHandler nested && hasTextBox(nested.children(), creative)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInventory(Screen screen) {
        return screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen;
    }

    private List<KeyMapping> keys() {
        List<KeyMapping> keys = new ArrayList<>(List.of(mc.options.keyUp,
            mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight));
        if (sneak.isOn()) {
            keys.add(mc.options.keyShift);
        }
        if (sprint.isOn()) {
            keys.add(mc.options.keySprint);
        }
        if (jump.isOn()) {
            keys.add(mc.options.keyJump);
        }
        return keys;
    }
}
