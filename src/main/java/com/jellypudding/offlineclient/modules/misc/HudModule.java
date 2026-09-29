package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.gui.HudEditorScreen;
import com.jellypudding.offlineclient.gui.WindowGuiScreen;
import com.jellypudding.offlineclient.hud.GamePart;
import com.jellypudding.offlineclient.hud.GamePartElement;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.HudManager;
import com.jellypudding.offlineclient.hud.elements.ArmourElement;
import com.jellypudding.offlineclient.hud.elements.CombatElement;
import com.jellypudding.offlineclient.hud.elements.CompassElement;
import com.jellypudding.offlineclient.hud.elements.CoverElement;
import com.jellypudding.offlineclient.hud.elements.HoleElement;
import com.jellypudding.offlineclient.hud.elements.InfoBarElement;
import com.jellypudding.offlineclient.hud.elements.InventoryElement;
import com.jellypudding.offlineclient.hud.elements.ItemCounterElement;
import com.jellypudding.offlineclient.hud.elements.KillStatsElement;
import com.jellypudding.offlineclient.hud.elements.LagNotifierElement;
import com.jellypudding.offlineclient.hud.elements.LeaksElement;
import com.jellypudding.offlineclient.hud.elements.ModuleListElement;
import com.jellypudding.offlineclient.hud.elements.MountElement;
import com.jellypudding.offlineclient.hud.elements.PlayerListElement;
import com.jellypudding.offlineclient.hud.elements.PlayerModelElement;
import com.jellypudding.offlineclient.hud.elements.PotionTimersElement;
import com.jellypudding.offlineclient.hud.elements.ServerInfoElement;
import com.jellypudding.offlineclient.hud.elements.StatsElement;
import com.jellypudding.offlineclient.hud.elements.TextElement;
import com.jellypudding.offlineclient.hud.elements.WatermarkElement;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

// The overlay itself lives in the hud package. The elements belong to this module.
// Their settings save and show in the ClickGUI like any other.
public final class HudModule extends Module {

    private final HudManager manager = new HudManager();
    private final WatermarkElement watermark = new WatermarkElement();

    // addSettings is final and files the settings away without handing out the module.
    @SuppressWarnings("this-escape")
    public HudModule() {
        super("HUD", "The overlay you see whilst playing and the game's own bars. Drag the pieces"
            + " about with .hud edit.", Category.MISC);
        add(watermark, new ModuleListElement(), new InfoBarElement(),
            new ArmourElement(), new PotionTimersElement(), new ItemCounterElement(),
            new TextElement(), new CombatElement(), new KillStatsElement(), new StatsElement(),
            new LagNotifierElement(), new CompassElement(), new PlayerListElement(), new LeaksElement(),
            new ServerInfoElement(), new InventoryElement(), new PlayerModelElement(), new HoleElement(),
            new MountElement());
        // Covers come after every other element and sit over all of them.
        for (int number = 1; number <= CoverElement.COUNT; number++) {
            add(new CoverElement(number));
        }
        for (GamePart part : GamePart.values()) {
            add(new GamePartElement(part));
        }
        searchTags("overlay", "watermark", "module list", "info", "hud editor", "hotbar", "hearts",
            "hunger", "armour", "experience", "statistics", "stats", "play time", "cover", "censor",
            "streamer", "hide coordinates", "horse", "mount", "elytra", "coordinate leaks");
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

    // The game's own bars follow their elements whilst the HUD is on and always in
    // the editor where they are being placed.
    public boolean placesGameParts() {
        return isEnabled() || mc.gui.screen() instanceof HudEditorScreen;
    }

    // A key on the HUD opens the editor. The switch in the GUI still turns it all off.
    @Override
    public void onKeybind() {
        mc.schedule(() -> mc.gui.setScreen(new HudEditorScreen(manager)));
    }

    // The windowed ClickGUI draws the client name in the same corner as the watermark.
    private boolean cornerTaken() {
        return mc.gui.screen() instanceof WindowGuiScreen
            && watermark.xPercent() < 25 && watermark.yPercent() < 25;
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (mc.gui.screen() instanceof HudEditorScreen || cornerTaken()) {
            return;
        }
        manager.render(event.getContext(), mc.font);
    }

    // The last draw of each frame. The debug text still draws over a hidden HUD whilst a
    // screen is open and the covers stay with it. The editor draws them itself.
    public void renderOnTop(GuiGraphicsExtractor context) {
        if (!isEnabled() || mc.level == null || mc.gui.screen() instanceof HudEditorScreen) {
            return;
        }
        if (mc.gui.hud.isHidden() && mc.gui.screen() == null) {
            return;
        }
        manager.renderOnTop(context, mc.font);
    }
}
