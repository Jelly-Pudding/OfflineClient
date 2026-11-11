package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.joml.Matrix3x2fStack;

// The map always draws at its native size of one hundred and twenty eight
// pixels and the pose does the scaling.
public final class MapPreview implements ClientTooltipComponent {

    private static final int NATIVE_SIZE = 128;
    private static final int PADDING = 2;

    private final MapRenderState state;
    private final int size;

    public MapPreview(MapRenderState state, int size) {
        this.state = state;
        this.size = size;
    }

    // The picture on a filled map ready to draw. Null for any other item and for a map the
    // server has not sent yet.
    public static MapRenderState stateOf(ItemStack stack) {
        MapId id = stack.get(DataComponents.MAP_ID);
        MapItemSavedData data = dataOf(id);
        return data == null ? null : stateOf(id, data);
    }

    // What the server has sent of a map. Null for no map and for one it has not sent yet.
    public static MapItemSavedData dataOf(MapId id) {
        ClientLevel level = OfflineClient.MC.level;
        return level == null ? null : MapItem.getSavedData(id, level);
    }

    public static MapRenderState stateOf(MapId id, MapItemSavedData data) {
        MapRenderState state = new MapRenderState();
        OfflineClient.MC.getMapRenderer().extractRenderState(id, data, state);
        // A map drawn on the screen keeps only the markers a frame shows. A map in hand shows every one.
        state.decorations.forEach(mark -> mark.renderOnFrame = true);
        return state;
    }

    // Draws the map with its top left corner on the point and the given width and height.
    public static void draw(GuiGraphicsExtractor context, MapRenderState state, float x, float y, int size) {
        float scale = size / (float) NATIVE_SIZE;
        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale, scale);
        context.map(state);
        pose.popMatrix();
    }

    @Override
    public int getWidth(Font font) {
        return size + PADDING;
    }

    @Override
    public int getHeight(Font font) {
        return size + PADDING;
    }

    @Override
    public void extractImage(Font font, int x, int y, int tooltipWidth, int tooltipHeight,
                             GuiGraphicsExtractor context) {
        draw(context, state, x + PADDING / 2f, y + PADDING / 2f, size);
    }
}
