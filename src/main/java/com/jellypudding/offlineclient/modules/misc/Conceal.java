package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.Minecraft;

// Only what the client draws itself is hidden. XRay and Chams and Fullbright change how the game
// draws and stay until they are switched off.
public final class Conceal extends Module {

    public enum Part { WORLD, OVERLAY, SCREENS }

    private final BoolSetting world = new BoolSetting("World",
        "Hides the boxes and lines the client draws in the world.", true);
    private final BoolSetting overlay = new BoolSetting("Overlay",
        "Hides the HUD and the connect log and the labels the client writes over the world.", true);
    private final BoolSetting screens = new BoolSetting("Screens",
        "Hides the buttons the client adds to the game's menus and book screens and what it adds to containers and "
            + "item tooltips.", true);

    public Conceal() {
        super("Conceal", "Hides what the client draws whilst its modules keep working.", Category.MISC);
        addSettings(world, overlay, screens);
        searchTags("hide client", "streamer", "stream", "recording", "screenshot", "clean screen");
    }

    // True whilst Conceal is on and hides that part. Whatever draws a part asks before it draws.
    // A dormant client hides every part. A panorama shot shows the world as the game draws it.
    public static boolean hides(Part part) {
        if (Modules.dormant()) {
            return true;
        }
        Minecraft mc = OfflineClient.MC;
        if (part == Part.WORLD && mc != null && mc.gameRenderer != null
            && mc.gameRenderer.mainCamera().isPanoramicMode()) {
            return true;
        }
        Conceal conceal = Modules.active(Conceal.class);
        if (conceal == null) {
            return false;
        }
        return switch (part) {
            case WORLD -> conceal.world.isOn();
            case OVERLAY -> conceal.overlay.isOn();
            case SCREENS -> conceal.screens.isOn();
        };
    }
}
