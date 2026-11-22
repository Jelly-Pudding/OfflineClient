package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

// The minimap and the world map have one each. The waypoints and finds and painted chunks are
// gathered again once a second whilst a map draws them.
public final class MapOverlay {

    private static final long GATHER_MS = 1000;

    private final BoolSetting entities = new BoolSetting("Entities",
        "Draws the entities Radar picks on the map.", true);
    private final BoolSetting waypoints = new BoolSetting("Waypoints",
        "Draws your waypoints and the hunting finds on the map.", true);
    private final BoolSetting chunkMarks = new BoolSetting("Chunk marks",
        "Paints the chunks marked by the hunting modules that are on.", true);

    private final ChunkTints tints = new ChunkTints();
    private List<MapMarks.Mark> marks = List.of();
    private long gatheredAt;

    public Setting<?>[] settings() {
        return new Setting<?>[] {entities, waypoints, chunkMarks};
    }

    public void refresh() {
        gatheredAt = System.currentTimeMillis();
        marks = waypoints.isOn() ? MapMarks.gather() : List.of();
        if (chunkMarks.isOn()) {
            tints.set(MapMarks.chunkColours());
        }
    }

    private void keepFresh() {
        if (System.currentTimeMillis() - gatheredAt >= GATHER_MS) {
            refresh();
        }
    }

    // Null whilst Chunk marks is off.
    public ChunkTints tints() {
        keepFresh();
        return chunkMarks.isOn() ? tints : null;
    }

    public void draw(GuiGraphicsExtractor context, Font font, MapView view, boolean edges, boolean names,
                     boolean shadow) {
        keepFresh();
        if (waypoints.isOn()) {
            MapMarks.marks(context, font, view, marks, edges, names, shadow);
        }
        if (entities.isOn()) {
            MapMarks.entities(context, font, view, shadow);
        }
    }

    public MapMarks.Mark nearest(MapView view, double screenX, double screenY, double reach) {
        return waypoints.isOn() ? MapMarks.nearest(view, marks, screenX, screenY, reach) : null;
    }
}
