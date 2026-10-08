package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.modules.render.Minimap;
import com.jellypudding.offlineclient.render.MapMarks;
import com.jellypudding.offlineclient.render.MapView;
import com.jellypudding.offlineclient.render.TerrainPicture;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.TerrainLayer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

// The land around you the Minimap module asks for. The module picks what shows and this decides
// where it sits and how big it is.
public final class MinimapElement extends HudElement {

    // The square before any stretch.
    private static final int SIZE = 100;

    private final TerrainPicture land = new TerrainPicture();
    // The layer at your height over the land whilst a roof is over you. What it has not read is
    // clear and the land shows through.
    private final TerrainPicture cave = new TerrainPicture();

    public MinimapElement() {
        super("Minimap", "Shows the Minimap module's map whilst that module is on.", true, 0, 0, Background.ON);
    }

    @Override
    public boolean visible() {
        return isActive() && Modules.active(Minimap.class) != null && OfflineClient.MC.player != null
            && OfflineClient.MC.level != null;
    }

    // Laid out in GUI pixels. A stretch shows more land and leaves the marks their size.
    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        Minimap minimap = Modules.active(Minimap.class);
        LocalPlayer player = OfflineClient.MC.player;
        TerrainLayer explored = minimap == null ? null : minimap.explored();
        if (explored == null || player == null) {
            return;
        }
        int width = boxWidth();
        int height = boxHeight();
        context.pose().pushMatrix();
        context.pose().scale((float) (1 / scaleX()), (float) (1 / scaleY()));
        Vec3 at = player.getPosition(OfflineClient.MC.getDeltaTracker().getGameTimeDeltaPartialTick(true));
        float yaw = player.getYRot();
        MapView view = new MapView(at.x, at.z, minimap.blocksPerPixel(),
            minimap.turns() ? MapView.facingUp(yaw) : 0, 0, 0, width, height);
        // Only the top picture is tinted. A tint on a clear texel still lies over the land under it.
        TerrainLayer underRoof = minimap.caveLand();
        land.draw(context, view, explored, underRoof == null ? minimap.tints() : null, 0);
        if (underRoof != null) {
            cave.draw(context, view, underRoof, minimap.tints(), 0);
        }
        boolean shadow = textShadow();
        context.enableScissor(0, 0, width, height);
        if (minimap.drawsMarks()) {
            MapMarks.marks(context, font, view, minimap.marks(), true, false, shadow);
        }
        if (minimap.drawsEntities()) {
            MapMarks.entities(context, font, view, shadow);
        }
        MapMarks.self(context, view, at.x, at.z, yaw);
        if (minimap.turns()) {
            MapMarks.north(context, font, view, shadow);
        }
        context.disableScissor();
        context.pose().popMatrix();
    }

    @Override
    public int width(Font font) {
        return SIZE;
    }

    @Override
    public int height(Font font) {
        return SIZE;
    }
}
