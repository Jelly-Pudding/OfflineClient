package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.SpawnUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class SpawnEsp extends Module {

    private static final int COLOR = 0xFFFF4040;

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

    private List<BlockPos> spots = List.of();
    private int timer;

    public SpawnEsp() {
        super("SpawnESP", "Marks the dark floor where mobs can appear.", Category.RENDER);
        addSettings(horizontal, vertical, light, limit, refresh, cross);
        searchTags("light overlay", "mob spawn", "spawn proof");
    }

    @Override
    public String getSuffix() {
        return count(spots.size());
    }

    @Override
    protected void onEnable() {
        timer = 0;
        spots = List.of();
    }

    @Override
    protected void onDisable() {
        spots = List.of();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            spots = List.of();
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        timer = refresh.getInt();
        spots = SpawnUtil.spotsAround(horizontal.getInt(), vertical.getInt(),
            light.getInt(), limit.getInt());
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        boolean x = cross.isOn();
        for (BlockPos pos : spots) {
            double y = pos.getY() + LIFT;
            double west = pos.getX();
            double east = pos.getX() + 1;
            double north = pos.getZ();
            double south = pos.getZ() + 1;

            batch.flatRect(west, north, east, south, y, COLOR, true);
            if (x) {
                batch.line(new Vec3(west, y, north), new Vec3(east, y, south), COLOR, true);
                batch.line(new Vec3(east, y, north), new Vec3(west, y, south), COLOR, true);
            }
        }
    }
}
