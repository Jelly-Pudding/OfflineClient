package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.modules.render.Minimap;
import com.jellypudding.offlineclient.render.ChunkTints;
import com.jellypudding.offlineclient.render.MapMarks;
import com.jellypudding.offlineclient.render.MapView;
import com.jellypudding.offlineclient.render.TerrainPicture;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ExploredLand;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.TerrainLayer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

// The land around you. It is on whilst the Minimap module is. The module picks what shows and
// this decides where it sits and how big it is.
public final class MinimapElement extends HudElement {

    // The square before any stretch.
    private static final int SIZE = 100;

    private final TerrainPicture land = new TerrainPicture();
    // The layer at your height over the land whilst a roof is over you. What it has not read is
    // clear and the land shows through.
    private final TerrainPicture cave = new TerrainPicture();
    private final NumberSetting arrowSize = new NumberSetting("Minimap arrow size",
        "How big the arrow that marks you is drawn next to its normal size.", 1, 0.5, 3, 0.1, "x").min(0.1);

    public MinimapElement() {
        super("Minimap", "A map of the land around you. Its other options are in the Minimap module."
            + " Turn on WorldMap to open the whole map.", Minimap.class, 0, 0, Background.ON);
        add(arrowSize);
    }

    // A minimap that is switched off holds no textures. The next draw makes them afresh.
    @Override
    public boolean visible() {
        if (!isActive()) {
            land.close();
            cave.close();
            return false;
        }
        return OfflineClient.MC.player != null && OfflineClient.MC.level != null;
    }

    // Laid out in GUI pixels. A stretch shows more land and leaves the marks their size.
    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        Minimap minimap = Modules.active(Minimap.class);
        LocalPlayer player = OfflineClient.MC.player;
        TerrainLayer explored = ExploredLand.here();
        if (minimap == null || explored == null || player == null) {
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
        ChunkTints tints = minimap.overlay().tints();
        TerrainLayer underRoof = minimap.caveLand();
        land.draw(context, view, explored, underRoof == null ? tints : null, 0);
        if (underRoof != null) {
            cave.draw(context, view, underRoof, tints, 0);
        }
        boolean shadow = textShadow();
        context.enableScissor(0, 0, width, height);
        minimap.overlay().draw(context, font, view, true, false, shadow);
        MapMarks.self(context, view, at.x, at.z, yaw, arrowSize.getValue());
        if (minimap.marksNorth()) {
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
