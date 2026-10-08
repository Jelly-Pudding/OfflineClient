package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ChunkDataEvent;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.gui.WorldMapScreen;
import com.jellypudding.offlineclient.hud.elements.MinimapElement;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.ChunkTints;
import com.jellypudding.offlineclient.render.MapMarks;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.TerrainCache;
import com.jellypudding.offlineclient.util.TerrainLayer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.SectionPos;

import java.util.List;

// The land around you in the colours of a held map. The Minimap element of the HUD draws it
// whilst this is on and the map key opens everything you explored on this server.
public final class Minimap extends Module {

    // How often the waypoints and finds and painted chunks are gathered again.
    private static final int GATHER_TICKS = 20;
    // The layer at your height reaches this many pixels of the map from you until it meets the
    // most chunks it may read.
    private static final double CAVE_REACH = 72;
    private static final int MIN_CAVE_CHUNKS = 2;
    private static final int MAX_CAVE_CHUNKS = 8;
    // The roof must be gone this long before the map shows the land from above again. Along the
    // edge of a roof it would flip back and forth.
    private static final int ROOF_HOLD_TICKS = 40;

    private final KeybindSetting mapKey = new KeybindSetting("Map key",
        "Opens the map of everything you explored on this server.", InputConstants.KEY_M);
    private final NumberSetting zoom = new NumberSetting("Zoom",
        "How many blocks each pixel of the map covers.", 1, 0.25, 8, 0.25).min(0.05);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns the map with you instead of keeping north up.", true);
    private final BoolSetting caveView = new BoolSetting("Cave view",
        "Shows the layer you stand on whilst a roof is over your head.", true);
    private final BoolSetting entities = new BoolSetting("Entities",
        "Draws the entities Radar picks on the map.", true);
    private final BoolSetting waypoints = new BoolSetting("Waypoints",
        "Draws your waypoints and the hunting finds on the map.", true);
    private final BoolSetting chunkMarks = new BoolSetting("Chunk marks",
        "Paints the chunks NewChunks and BaseFinder marked.", true);
    private final BoolSetting saveMap = new BoolSetting("Save map",
        "Keeps what you explored for the next visit.", true);

    private final TerrainCache terrain = new TerrainCache(saveMap);
    private final ChunkTints tints = new ChunkTints();
    private List<MapMarks.Mark> marks = List.of();
    private boolean roofed;
    // Ticks left before the map leaves the layer at your height.
    private int roofHold;
    private int ticks;

    public Minimap() {
        super("Minimap", "Draws a map of the land around you where you place it with .hud edit.",
            Category.RENDER);
        addSettings(mapKey, zoom, rotate, caveView, entities, waypoints, chunkMarks, saveMap);
        searchTags("world map", "terrain", "explored", "cave map");
    }

    @Override
    protected void onEnable() {
        terrain.start();
        ticks = 0;
        if (!Modules.hudShows(MinimapElement.class)) {
            ChatUtil.message("§bMinimap §7shows on the HUD. Turn on the HUD and its Minimap element to see it.");
        }
    }

    @Override
    protected void onDisable() {
        terrain.stop();
        roofed = false;
        roofHold = 0;
    }

    @Subscribe
    private void onChunkData(ChunkDataEvent event) {
        terrain.arrived(event.getChunk());
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        terrain.noteChange(event.getPacket());
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (caveView.isOn() && inGame() && TerrainCache.underRoof(mc.level, mc.player)) {
            roofHold = ROOF_HOLD_TICKS;
        } else if (roofHold > 0) {
            roofHold--;
        }
        roofed = caveView.isOn() && inGame() && roofHold > 0;
        terrain.tick(roofed ? caveChunks() : 0);
        if (inGame() && ticks++ % GATHER_TICKS == 0) {
            refreshMarks();
        }
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.presses(mapKey) && mc.gui.screen() == null && inGame()) {
            mc.schedule(() -> mc.gui.setScreen(new WorldMapScreen(this)));
        }
    }

    // How many chunks round you the layer at your height covers. It reaches as far on the map
    // at any zoom until it meets the most it may read. The explored land shows past it.
    private int caveChunks() {
        int chunks = (int) Math.ceil(CAVE_REACH * zoom.getValue() / SectionPos.SECTION_SIZE) + 1;
        return Math.clamp(chunks, MIN_CAVE_CHUNKS, MAX_CAVE_CHUNKS);
    }

    // Gathers the waypoints and finds and painted chunks again. Saving a waypoint shows it at once.
    public void refreshMarks() {
        marks = waypoints.isOn() ? MapMarks.gather() : List.of();
        if (chunkMarks.isOn()) {
            tints.set(MapMarks.chunkColours());
        }
    }

    public boolean isMapKey(int key) {
        return mapKey.isBound() && key == mapKey.getValue();
    }

    public double blocksPerPixel() {
        return zoom.getValue();
    }

    public boolean turns() {
        return rotate.isOn();
    }

    public boolean drawsEntities() {
        return entities.isOn();
    }

    public boolean drawsMarks() {
        return waypoints.isOn();
    }

    public List<MapMarks.Mark> marks() {
        return marks;
    }

    // Null whilst Chunk marks is off.
    public ChunkTints tints() {
        return chunkMarks.isOn() ? tints : null;
    }

    // The land at your height whilst a roof is over you with Cave view on. The minimap draws it
    // over the explored land. Null whilst there is no roof.
    public TerrainLayer caveLand() {
        return roofed ? terrain.cave() : null;
    }

    // The land of everything you explored in the dimension you are in. Null before the first tick.
    public TerrainLayer explored() {
        return terrain.here();
    }

    // The height to stand at in a column of the map.
    public int standY(int x, int z) {
        return terrain.standY(x, z);
    }
}
