package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.gui.GuiScreenBase;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;

// Reuses the game's own menu blur pass. The screen mixins ask for the blur
// stratum and GameRendererMixin feeds the radius in place of the video option.
public final class Blur extends Module {

    private static final String OWN_GUI_PACKAGE = GuiScreenBase.class.getPackageName();

    private final NumberSetting strength = new NumberSetting("Strength",
        "How far the blur spreads.", 5, 1, GameRenderer.MAX_BLUR_RADIUS, 1, "")
        .min(1).max(GameRenderer.MAX_BLUR_RADIUS);
    private final NumberSetting fadeTime = new NumberSetting("Fade time",
        "How long the blur takes to come and go.", 100, 0, 500, 10, " ms").min(0).max(2000);
    private final BoolSetting ownMenus = new BoolSetting("Own menus",
        "Blur behind the ClickGUI and the other menus of this client.", true);
    private final BoolSetting inventories = new BoolSetting("Inventories",
        "Blur behind your inventory and chests and every other container.", true);
    private final BoolSetting chat = new BoolSetting("Chat",
        "Blur behind the chat box.", false);
    private final BoolSetting others = new BoolSetting("Other screens",
        "Blur behind every other kind of screen such as the pause menu.", true);

    // How far the blur has faded in from nought to one.
    private float level;
    private long lastFrame;

    public Blur() {
        super("Blur", "Blurs the world behind menus and fades the blur in and out.", Category.RENDER);
        addSettings(strength, fadeTime, ownMenus, inventories, chat, others);
        searchTags("background", "menu");
    }

    @Override
    protected void onDisable() {
        level = 0;
    }

    // The radius the blur shader gets this frame. Runs once a frame before the GUI draws.
    public int radius(int vanilla) {
        if (!isEnabled()) {
            return vanilla;
        }
        long now = System.currentTimeMillis();
        float step = fadeTime.getInt() == 0 ? 1 : (now - lastFrame) / (float) fadeTime.getInt();
        lastFrame = now;
        float target = wants(mc.gui.screen()) ? 1 : 0;
        level = Math.clamp(level + Math.copySign(Math.min(step, 1), target - level), 0, 1);
        if (level == target && target == 0) {
            return vanilla;
        }
        return Math.max(1, Math.round(level * strength.getInt()));
    }

    // Whether the given screen sits on a blurred world.
    public boolean wants(Screen screen) {
        if (!isEnabled() || screen == null) {
            return false;
        }
        if (screen.getClass().getPackageName().startsWith(OWN_GUI_PACKAGE)) {
            return ownMenus.isOn();
        }
        if (screen instanceof AbstractContainerScreen<?>) {
            return inventories.isOn();
        }
        if (screen instanceof ChatScreen) {
            return chat.isOn();
        }
        return others.isOn();
    }

    // True whilst the blur is still fading out after a screen has closed.
    public boolean fadingWithoutScreen() {
        return isEnabled() && level > 0 && mc.gui.screen() == null;
    }

    // Asks for the blur pass before whatever the screen draws next.
    public void blurHere(GuiGraphicsExtractor context) {
        if (isEnabled() && level > 0) {
            context.blurBeforeThisStratum();
        }
    }
}
