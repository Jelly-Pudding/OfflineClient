package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.modules.render.Radar;
import com.jellypudding.offlineclient.render.MapMarks;
import com.jellypudding.offlineclient.render.MapView;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

// The map of nearby entities the Radar module asks for. The module picks what shows and this
// decides where it sits and how big it is.
public final class RadarElement extends HudElement {

    // The square before any stretch.
    private static final int SIZE = 100;
    private static final int GRID = 0x40FFFFFF;
    private static final int SELF = 0xFFFFFFFF;
    // How far the cross through the middle stops short of the edges.
    private static final int INSET = 2;

    public RadarElement() {
        super("Radar", "Shows the Radar module's map whilst that module is on.", true, 100, 0, Background.ON);
    }

    @Override
    public boolean visible() {
        return isActive() && Modules.active(Radar.class) != null && OfflineClient.MC.player != null
            && OfflineClient.MC.level != null;
    }

    // Laid out in GUI pixels. A stretch gives the map more room and leaves the dots and names
    // their size.
    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        Radar radar = Modules.active(Radar.class);
        LocalPlayer player = OfflineClient.MC.player;
        if (radar == null || player == null) {
            return;
        }
        int width = boxWidth();
        int height = boxHeight();
        context.pose().pushMatrix();
        context.pose().scale((float) (1 / scaleX()), (float) (1 / scaleY()));
        int middleX = width / 2;
        int middleY = height / 2;
        context.fill(middleX, INSET, middleX + 1, height - INSET, GRID);
        context.fill(INSET, middleY, width - INSET, middleY + 1, GRID);
        // The range reaches from you to the nearest edge.
        double zoom = radar.range() / (Math.min(width, height) / 2.0);
        float turn = radar.turns() ? MapView.facingUp(player.getYRot()) : 0;
        MapMarks.entities(context, font, new MapView(player.getX(), player.getZ(), zoom, turn, 0, 0, width, height),
            textShadow());
        context.fill(middleX - 1, middleY - 1, middleX + 2, middleY + 2, SELF);
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
