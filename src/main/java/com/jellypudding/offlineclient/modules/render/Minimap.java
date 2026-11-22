package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.MapOverlay;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ExploredLand;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.TerrainCache;
import com.jellypudding.offlineclient.util.TerrainLayer;
import net.minecraft.core.SectionPos;

// The Minimap element of the HUD draws it whilst this is on. The land comes from the record of what
// you explored.
public final class Minimap extends Module {

    private static final double CAVE_REACH = 72;
    private static final int MIN_CAVE_CHUNKS = 2;
    private static final int MAX_CAVE_CHUNKS = 8;
    // Along the edge of a roof the map would flip back and forth.
    private static final int ROOF_HOLD_TICKS = 40;

    private final NumberSetting zoom = new NumberSetting("Zoom",
        "How many blocks each pixel of the map covers.", 1, 0.25, 8, 0.25).min(0.05);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns the map with you instead of keeping north up.", true);
    private final BoolSetting northLetter = new BoolSetting("North letter",
        "Writes an N on the edge of the map the way north lies.", true).under(rotate);
    private final BoolSetting caveView = new BoolSetting("Cave view",
        "Shows the layer you stand on whilst a roof is over your head.", true);
    private final MapOverlay overlay = new MapOverlay();

    private boolean roofed;
    private int roofHold;

    public Minimap() {
        super("Minimap", "Draws a map of the land around you where you place it with .hud edit.", Category.RENDER);
        addSettings(zoom, rotate, northLetter, caveView);
        addSettings(overlay.settings());
        searchTags("terrain", "cave map");
    }

    @Override
    protected void onEnable() {
        ExploredLand.keep(this);
        Modules.tellHudOff(this);
    }

    @Override
    protected void onDisable() {
        ExploredLand.wantCave(0);
        ExploredLand.release(this);
        roofed = false;
        roofHold = 0;
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (caveView.isOn() && inGame() && TerrainCache.underRoof(mc.level, mc.player)) {
            roofHold = ROOF_HOLD_TICKS;
        } else if (roofHold > 0) {
            roofHold--;
        }
        roofed = caveView.isOn() && inGame() && roofHold > 0;
        ExploredLand.wantCave(roofed ? caveChunks() : 0);
    }

    private int caveChunks() {
        int chunks = (int) Math.ceil(CAVE_REACH * zoom.getValue() / SectionPos.SECTION_SIZE) + 1;
        return Math.clamp(chunks, MIN_CAVE_CHUNKS, MAX_CAVE_CHUNKS);
    }

    public double blocksPerPixel() {
        return zoom.getValue();
    }

    public boolean turns() {
        return rotate.isOn();
    }

    // A map that keeps north up needs no letter for it.
    public boolean marksNorth() {
        return rotate.isOn() && northLetter.isOn();
    }

    public MapOverlay overlay() {
        return overlay;
    }

    // Null whilst there is no roof over you.
    public TerrainLayer caveLand() {
        return roofed ? ExploredLand.cave() : null;
    }
}
