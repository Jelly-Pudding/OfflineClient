package com.jellypudding.offlineclient.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.state.MapRenderState;
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
        float scale = size / (float) NATIVE_SIZE;
        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.translate(x + PADDING / 2f, y + PADDING / 2f);
        pose.scale(scale, scale);
        context.map(state);
        pose.popMatrix();
    }
}
