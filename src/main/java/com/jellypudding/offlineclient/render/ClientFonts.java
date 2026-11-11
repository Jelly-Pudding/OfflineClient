package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.gui.LatchedScale;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.Font;

// The font the client's own text is drawn and measured in. The screens of the client ask gui() and
// the overlay asks hud(). Whilst a switch is off the answer is the game's own font itself.
public final class ClientFonts {

    private static final SmoothFont SMOOTH = new SmoothFont(ClientFonts::smoothWanted);

    private ClientFonts() {
    }

    // Screens drawn through the ClickGUI scale. Nearly every screen of the client is.
    public static Font gui() {
        return guiSmooth() ? SMOOTH.at(pixels(LatchedScale.guiScale())) : OfflineClient.MC.font;
    }

    // A screen of the client drawn at the game's own GUI scale such as the list picker.
    public static Font guiAtGameScale() {
        return guiAt(1);
    }

    // Client text drawn this many times the size of the game's own GUI.
    public static Font guiAt(float scale) {
        return guiSmooth() ? SMOOTH.at(pixels(scale)) : OfflineClient.MC.font;
    }

    public static Font hud() {
        return hudSmooth() ? SMOOTH.at(pixels(1)) : OfflineClient.MC.font;
    }

    // Screen pixels to one GUI pixel of text drawn at this scale. The smooth font is made for it.
    private static int pixels(float scale) {
        return Math.round(OfflineClient.MC.getWindow().getGuiScale() * scale);
    }

    private static boolean guiSmooth() {
        ClickGuiModule gui = Modules.get(ClickGuiModule.class);
        return gui != null && gui.smoothFont();
    }

    private static boolean hudSmooth() {
        HudModule hud = Modules.get(HudModule.class);
        return hud != null && hud.smoothFont();
    }

    private static boolean smoothWanted() {
        return guiSmooth() || hudSmooth();
    }
}
