package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.gui.HudEditorScreen;
import com.jellypudding.offlineclient.gui.WindowGuiScreen;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.HudManager;
import com.jellypudding.offlineclient.hud.elements.ArmourElement;
import com.jellypudding.offlineclient.hud.elements.CombatElement;
import com.jellypudding.offlineclient.hud.elements.CompassElement;
import com.jellypudding.offlineclient.hud.elements.HoleElement;
import com.jellypudding.offlineclient.hud.elements.InfoBarElement;
import com.jellypudding.offlineclient.hud.elements.InventoryElement;
import com.jellypudding.offlineclient.hud.elements.ItemCounterElement;
import com.jellypudding.offlineclient.hud.elements.LagNotifierElement;
import com.jellypudding.offlineclient.hud.elements.ModuleListElement;
import com.jellypudding.offlineclient.hud.elements.PlayerListElement;
import com.jellypudding.offlineclient.hud.elements.PlayerModelElement;
import com.jellypudding.offlineclient.hud.elements.PotionTimersElement;
import com.jellypudding.offlineclient.hud.elements.ServerInfoElement;
import com.jellypudding.offlineclient.hud.elements.TextElement;
import com.jellypudding.offlineclient.hud.elements.WatermarkElement;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.Setting;

// The overlay itself lives in the hud package. This module owns the elements and
// their settings save and show in the ClickGUI like any other module.
public final class HudModule extends Module {

    private final HudManager manager = new HudManager();
    private final WatermarkElement watermark = new WatermarkElement();

    // addSettings is final and files the settings away without handing out the module.
    @SuppressWarnings("this-escape")
    public HudModule() {
        super("HUD", "The overlay you see whilst playing. Drag the pieces about with .hud edit.",
            Category.MISC);
        add(watermark, new ModuleListElement(), new InfoBarElement(),
            new ArmourElement(), new PotionTimersElement(), new ItemCounterElement(),
            new TextElement(), new CombatElement(), new LagNotifierElement(),
            new CompassElement(), new PlayerListElement(), new ServerInfoElement(),
            new InventoryElement(), new PlayerModelElement(), new HoleElement());
        searchTags("overlay", "watermark", "module list", "info", "hud editor");
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

    // A key on the HUD opens the editor. The switch in the GUI still turns it all off.
    @Override
    public void onKeybind() {
        OfflineClient.MC.schedule(() -> OfflineClient.MC.gui.setScreen(new HudEditorScreen(manager)));
    }

    // The windowed ClickGUI draws the client name in the same corner as the watermark.
    private boolean cornerTaken() {
        return OfflineClient.MC.gui.screen() instanceof WindowGuiScreen
            && watermark.xPercent() < 25 && watermark.yPercent() < 25;
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (OfflineClient.MC.gui.screen() instanceof HudEditorScreen || cornerTaken()) {
            return;
        }
        manager.render(event.getContext(), mc.font, false);
    }
}
