package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.gui.ClickGuiScreen;
import com.jellypudding.offlineclient.gui.RegistryPickerScreen;
import com.jellypudding.offlineclient.gui.WindowGuiScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.setting.BoolSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;

import java.util.ArrayList;
import java.util.List;

// The real keyboard state is fed into the movement key mappings every tick.
public final class InvWalk extends Module {

    private final BoolSetting sneak = new BoolSetting("Sneak",
        "Also let the sneak key work.", true);
    private final BoolSetting sprint = new BoolSetting("Sprint",
        "Also let the sprint key work.", true);
    private final BoolSetting jump = new BoolSetting("Jump",
        "Also let the jump key work.", true);

    public InvWalk() {
        super("InvWalk", "Lets you walk about whilst a screen is open.", Category.PLAYER);
        addSettings(sneak, sprint, jump);
        searchTags("inventory walk", "inv move", "menu walk");
    }

    @Override
    protected void onDisable() {
        release();
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!inGame()) {
            return;
        }
        Screen screen = mc.gui.screen();
        if (screen == null) {
            return;
        }
        if (!allowed(screen)) {
            release();
            return;
        }
        for (KeyMapping mapping : keys()) {
            mapping.setDown(InputUtil.physicallyHeld(mapping));
        }
    }

    // Screens that take typing keep the keyboard to themselves.
    public static boolean allowed(Screen screen) {
        if (screen instanceof ChatScreen || screen instanceof AbstractSignEditScreen) {
            return false;
        }
        if (screen instanceof ClickGuiScreen || screen instanceof WindowGuiScreen
            || screen instanceof RegistryPickerScreen) {
            return false;
        }
        for (GuiEventListener child : screen.children()) {
            if (child instanceof EditBox) {
                return false;
            }
        }
        return true;
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

    private void release() {
        List<KeyMapping> all = List.of(mc.options.keyUp, mc.options.keyDown,
            mc.options.keyLeft, mc.options.keyRight, mc.options.keyShift,
            mc.options.keySprint, mc.options.keyJump);
        for (KeyMapping mapping : all) {
            InputUtil.release(mapping);
        }
    }
}
