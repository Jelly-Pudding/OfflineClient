package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.SpawnUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class SpawnEsp extends Module {

    // Night marks a spot the sun is keeping clear for now.
    private record Spot(BlockPos pos, boolean night) {
    }

    // How far above the floor the marker is drawn.
    private static final double LIFT = 0.02;

    private final NumberSetting horizontal = new NumberSetting("Horizontal range",
        "How far sideways to look.", 16, 4, 48, 2, " blocks").max(64);
    private final NumberSetting vertical = new NumberSetting("Vertical range",
        "How far up and down to look.", 6, 1, 24, 1, " blocks").max(64);
    private final NumberSetting light = new NumberSetting("Light",
        "Highest block light a spot may have.", 0, 0, 15, 1).min(0).max(15);
    private final NumberSetting limit = new NumberSetting("Limit",
        "Most spots marked at once.", 600, 100, 4000, 100).min(1);
    private final NumberSetting refresh = new NumberSetting("Refresh",
        "Ticks between scans.", 10, 1, 60, 1, " ticks").min(1);
    private final BoolSetting cross = new BoolSetting("Cross",
        "Draw an X inside each square.", true);
    private final ColorSetting nowColor = new ColorSetting("Colour",
        "Colour of spots where a mob can appear right now.", 0, 0.89f, 0.88f, false);
    private final ColorSetting nightColor = new ColorSetting("Night colour",
        "Colour of spots that only the sky lights so mobs appear there at night.", 60, 0.89f, 0.88f, false);
    private final NumberSetting opacity = new NumberSetting("Opacity",
        "How solid the markers are drawn.", 50, 5, 100, 5, "%").min(1).max(100);
    private final BoolSetting hitbox = new BoolSetting("Hitbox check",
        "Also make sure a whole mob fits in the space. Slower but it rules out tight corners.", false);
    private final BoolSetting throughWalls = new BoolSetting("Through walls",
        "Show spots behind blocks.", false);

    private List<Spot> spots = List.of();
    private int timer;

    public SpawnEsp() {
        super("SpawnESP", "Marks the dark floor where mobs can appear.", Category.RENDER);
        addSettings(horizontal, vertical, light, limit, refresh, cross,
            nowColor, nightColor, opacity, hitbox, throughWalls);
        searchTags("light overlay", "mob spawn", "spawn proof");
    }

    @Override
    public String getSuffix() {
        return count(spots.size());
    }

    @Override
    protected void onEnable() {
        timer = 0;
        forget();
    }

    @Override
    protected void onDisable() {
        forget();
    }

    private void forget() {
        spots = List.of();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            forget();
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        timer = refresh.getInt();
        List<Spot> found = new ArrayList<>();
        for (BlockPos pos : SpawnUtil.spotsAround(horizontal.getInt(), vertical.getInt(),
            light.getInt(), limit.getInt(), hitbox.isOn())) {
            found.add(new Spot(pos, SpawnUtil.nightOnly(pos)));
        }
        spots = found;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        boolean x = cross.isOn();
        boolean through = throughWalls.isOn();
        float share = opacity.getFloat() / 100f;
        int now = ColorUtil.fade(nowColor.getColor(), share);
        int night = ColorUtil.fade(nightColor.getColor(), share);
        for (Spot spot : spots) {
            BlockPos pos = spot.pos();
            int color = spot.night() ? night : now;
            double y = pos.getY() + LIFT;
            double west = pos.getX();
            double east = pos.getX() + 1;
            double north = pos.getZ();
            double south = pos.getZ() + 1;

            batch.flatRect(west, north, east, south, y, color, through);
            if (x) {
                batch.line(new Vec3(west, y, north), new Vec3(east, y, south), color, through);
                batch.line(new Vec3(east, y, north), new Vec3(west, y, south), color, through);
            }
        }
    }
}
