package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.gui.HudEditorScreen;
import com.jellypudding.offlineclient.hud.GamePart;
import com.jellypudding.offlineclient.hud.GamePartElement;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.HudManager;
import com.jellypudding.offlineclient.hud.elements.ArmourElement;
import com.jellypudding.offlineclient.hud.elements.ClientLinesElement;
import com.jellypudding.offlineclient.hud.elements.CombatElement;
import com.jellypudding.offlineclient.hud.elements.CompassElement;
import com.jellypudding.offlineclient.hud.elements.CoverElement;
import com.jellypudding.offlineclient.hud.elements.HoleElement;
import com.jellypudding.offlineclient.hud.elements.InfoBarElement;
import com.jellypudding.offlineclient.hud.elements.InventoryElement;
import com.jellypudding.offlineclient.hud.elements.ItemCounterElement;
import com.jellypudding.offlineclient.hud.elements.KeystrokesElement;
import com.jellypudding.offlineclient.hud.elements.KillStatsElement;
import com.jellypudding.offlineclient.hud.elements.LagNotifierElement;
import com.jellypudding.offlineclient.hud.elements.LeaksElement;
import com.jellypudding.offlineclient.hud.elements.MapElement;
import com.jellypudding.offlineclient.hud.elements.MinimapElement;
import com.jellypudding.offlineclient.hud.elements.ModuleListElement;
import com.jellypudding.offlineclient.hud.elements.ModuleStatusElement;
import com.jellypudding.offlineclient.hud.elements.MountElement;
import com.jellypudding.offlineclient.hud.elements.PlayerListElement;
import com.jellypudding.offlineclient.hud.elements.PlayerModelElement;
import com.jellypudding.offlineclient.hud.elements.PotionTimersElement;
import com.jellypudding.offlineclient.hud.elements.RadarElement;
import com.jellypudding.offlineclient.hud.elements.ServerInfoElement;
import com.jellypudding.offlineclient.hud.elements.StatsElement;
import com.jellypudding.offlineclient.hud.elements.TextElement;
import com.jellypudding.offlineclient.hud.elements.WatermarkElement;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

// The overlay itself lives in the hud package. The elements belong to this module.
// Their settings save and show in the ClickGUI like any other.
public final class HudModule extends Module {

    private final HudManager manager = new HudManager();
    private final BoolSetting textShadow = new BoolSetting("Text shadow",
        "Draws a shadow behind all overlay text.", true);
    private final BoolSetting hideInMenus = new BoolSetting("Hide in menus",
        "Hides the overlay whilst a menu or a container is open.", false);

    public HudModule() {
        super("HUD", "The overlay you see whilst playing and the game's own bars. Drag the pieces"
            + " about with .hud edit.", Category.MISC);
        addSettings(textShadow, hideInMenus);
        add(new WatermarkElement(), new ModuleListElement(), new ModuleStatusElement(), new InfoBarElement(),
            new ArmourElement(), new PotionTimersElement(), new ItemCounterElement(),
            new CombatElement(), new KeystrokesElement(), new KillStatsElement(), new StatsElement(),
            new LagNotifierElement(), new CompassElement(), new MapElement(), new RadarElement(),
            new MinimapElement(), new PlayerListElement(), new LeaksElement(), new ClientLinesElement(),
            new ServerInfoElement(), new InventoryElement(), new PlayerModelElement(), new HoleElement(),
            new MountElement());
        for (int number = 1; number <= TextElement.COUNT; number++) {
            add(new TextElement(number));
        }
        // Covers come after every other element and sit over all of them.
        for (int number = 1; number <= CoverElement.COUNT; number++) {
            add(new CoverElement(number));
        }
        for (GamePart part : GamePart.values()) {
            add(new GamePartElement(part));
        }
        searchTags("overlay", "watermark", "module list", "info", "hud editor", "hotbar", "hearts",
            "hunger", "armour", "experience", "statistics", "stats", "play time", "cover", "censor",
            "streamer", "hide coordinates", "horse", "mount", "elytra", "coordinate leaks", "module status",
            "keystrokes", "cps", "clicks per second", "map", "radar", "minimap");
    }

    private void add(HudElement... elements) {
        for (HudElement element : elements) {
            manager.add(element);
            addSettings(element.getSettings().toArray(new Setting<?>[0]));
        }
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    public HudManager getManager() {
        return manager;
    }

    public boolean textShadow() {
        return textShadow.isOn();
    }

    // The game's own bars follow their elements whilst the HUD is on and always in
    // the editor where they are being placed.
    public boolean placesGameParts() {
        return isEnabled() || HudElement.editing();
    }

    // True whilst the overlay drawn with the game's own HUD steps aside. It gives way to the
    // debug screen and Hide in menus hides it under any screen but the chat box.
    public boolean overlayHidden() {
        Screen screen = mc.gui.screen();
        return mc.debugEntries.isOverlayVisible()
            || hideInMenus.isOn() && screen != null && !(screen instanceof ChatScreen);
    }

    // A key on the HUD opens the editor. The switch in the GUI still turns it all off.
    @Override
    public void onKeybind() {
        mc.schedule(() -> mc.gui.setScreen(new HudEditorScreen(manager)));
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        renderOverlay(event.getContext());
    }

    // The overlay drawn along with the game's own HUD. Whilst it steps aside only the elements
    // that stay are drawn. The editor draws all of it itself.
    public void renderOverlay(GuiGraphicsExtractor context) {
        if (!HudElement.editing()) {
            manager.render(context, mc.font, overlayHidden());
        }
    }

    // The last draw of each frame. The debug text still draws over a hidden HUD whilst a
    // screen is open and the covers stay with it. Hide in menus leaves them alone. The editor
    // draws them itself.
    public void renderOnTop(GuiGraphicsExtractor context) {
        if (!isEnabled() || mc.level == null || HudElement.editing()) {
            return;
        }
        if (mc.gui.hud.isHidden() && mc.gui.screen() == null) {
            return;
        }
        manager.renderOnTop(context, mc.font);
    }
}
