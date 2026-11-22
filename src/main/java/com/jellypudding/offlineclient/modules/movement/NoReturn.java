package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.config.ClosedAreas;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.List;

// An area you stand inside stays open until you leave it.
public final class NoReturn extends Module {

    // The server sends chunks one ring past your render distance.
    private static final int SENT_PAST_VIEW = 1;

    private static final double EDGE_BELOW = 1;
    private static final double EDGE_ABOVE = 3;
    private static final float EDGE_FILL = 0.3f;

    private final NumberSetting radius = new NumberSetting("Radius",
        "How many chunks round the spot stay closed. Nought matches your render distance.",
        0, 0, 32, 1, " chunks");
    private final BoolSetting showEdge = new BoolSetting("Show edge",
        "Draws the edge of the closed area.", true);
    private final ColorSetting edgeColour = new ColorSetting("Edge colour",
        "Colour of the edge.", 0, false).under(showEdge);

    private List<ClosedAreas.Area> here = List.of();
    private List<ClosedAreas.Area> walls = List.of();

    public NoReturn() {
        super("NoReturn", "Keeps you from going back within sight of the spot where you switched it on.",
            Category.MOVEMENT);
        addSettings(radius, showEdge, edgeColour);
        searchTags("no go zone", "closed area", "keep out", "wall");
    }

    @Override
    public String getSuffix() {
        return count(here.size());
    }

    public int radius() {
        return radius.getInt();
    }

    @Override
    protected void onEnable() {
        here = List.of();
        walls = List.of();
        if (!inGame()) {
            return;
        }
        ChunkPos at = mc.player.chunkPosition();
        if (ClosedAreas.get().close(at.x(), at.z(), radius.getInt())) {
            tell("closed the area round §f" + BlockUtil.text(mc.player.blockPosition())
                + "§7.");
        }
    }

    @Override
    protected void onDisable() {
        here = List.of();
        walls = List.of();
    }

    // Read for every block you could bump into.
    public boolean closed(BlockPos pos) {
        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
        for (ClosedAreas.Area area : walls) {
            if (within(area, chunkX, chunkZ)) {
                return true;
            }
        }
        return false;
    }

    // Nought keeps every chunk the server would send you of the centre shut.
    public int reach(ClosedAreas.Area area) {
        return area.radius() > 0 ? area.radius() : Modules.renderDistance() + SENT_PAST_VIEW;
    }

    private boolean within(ClosedAreas.Area area, int chunkX, int chunkZ) {
        return Mth.chessboardDistance(chunkX, chunkZ, area.chunkX(), area.chunkZ()) <= reach(area);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        here = ClosedAreas.get().here();
        ChunkPos me = mc.player.chunkPosition();
        walls = here.stream().filter(area -> !within(area, me.x(), me.z())).toList();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!showEdge.isOn() || !inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        int colour = edgeColour.getColor();
        double y = mc.player.getY();
        for (ClosedAreas.Area area : here) {
            int reach = reach(area);
            double x1 = SectionPos.sectionToBlockCoord(area.chunkX() - reach);
            double z1 = SectionPos.sectionToBlockCoord(area.chunkZ() - reach);
            double x2 = SectionPos.sectionToBlockCoord(area.chunkX() + reach + 1);
            double z2 = SectionPos.sectionToBlockCoord(area.chunkZ() + reach + 1);
            batch.gradientSides(new AABB(x1, y - EDGE_BELOW, z1, x2, y + EDGE_ABOVE, z2),
                ColorUtil.fade(colour, EDGE_FILL), ColorUtil.fade(colour, 0), false);
            batch.flatRect(x1, z1, x2, z2, y, colour, false);
        }
    }
}
