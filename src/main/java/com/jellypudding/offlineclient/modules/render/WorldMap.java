package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.gui.WorldMapScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.MapOverlay;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ExploredLand;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

// The map of everything you explored on the server you play on. Whilst this is on the land is
// recorded as you go and the map key opens the map.
public final class WorldMap extends Module {

    private final KeybindSetting mapKey = new KeybindSetting("Map key",
        "Opens the map of everything you explored on this server.", InputConstants.KEY_M);
    private final MapOverlay overlay = new MapOverlay();

    public WorldMap() {
        super("WorldMap", "Records the land you explore and opens a map of all of it with its Map key.",
            Category.RENDER);
        addSettings(mapKey, ExploredLand.saveSetting());
        addSettings(overlay.settings());
        searchTags("world map", "full map", "explored", "terrain");
    }

    @Override
    protected void onEnable() {
        ExploredLand.keep(this);
        if (!mapKey.isBound()) {
            tell("has no Map key. Bind one in its settings to open the map.");
        }
    }

    @Override
    protected void onDisable() {
        ExploredLand.release(this);
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.presses(mapKey) && mc.gui.screen() == null && inGame()) {
            mc.schedule(() -> mc.gui.setScreen(new WorldMapScreen(this)));
        }
    }

    public boolean isMapKey(KeyEvent event) {
        return mapKey.answers(event);
    }

    public boolean isMapKey(MouseButtonEvent event) {
        return mapKey.answers(event);
    }

    public MapOverlay overlay() {
        return overlay;
    }
}
