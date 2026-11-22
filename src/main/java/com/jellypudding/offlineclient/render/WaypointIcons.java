package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.config.WaypointStore.Icon;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

public final class WaypointIcons {

    private static final int REACH = 4;
    private static final int RIM_REACH = REACH + 1;
    private static final int BAR = 1;
    private static final int RIM_BAR = BAR + 1;
    public static final int ROOM_BELOW = RIM_REACH + 3;

    private static final int RIM = 0xFF000000;
    private static final float EIGHTH_TURN = (float) (Math.PI / 4);
    // The side of a square whose corners reach as far as its turned twin's sides do.
    private static final double TURNED_SHARE = Math.sqrt(0.5);
    private static final double ROUND_SHARE = 0.45;

    private WaypointIcons() {
    }

    // The colour has to be solid.
    public static void draw(GuiGraphicsExtractor context, Icon icon, double x, double y, float scale, int color) {
        if (icon == Icon.NONE) {
            return;
        }
        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.translate((float) x, (float) y);
        pose.scale(scale, scale);
        shape(context, icon, RIM_REACH, RIM_BAR, RIM);
        context.guiRenderState.up();
        shape(context, icon, REACH, BAR, color);
        context.guiRenderState.up();
        pose.popMatrix();
    }

    private static void shape(GuiGraphicsExtractor context, Icon icon, int reach, int bar, int color) {
        int inner = (int) Math.round(reach * TURNED_SHARE);
        switch (icon) {
            case SQUARE -> square(context, reach, color);
            case DIAMOND -> turned(context, () -> square(context, inner, color));
            case STAR -> {
                square(context, inner, color);
                turned(context, () -> square(context, inner, color));
            }
            case CIRCLE -> {
                int narrow = (int) Math.round(reach * ROUND_SHARE);
                square(context, inner, color);
                context.fill(-narrow, -reach, narrow, reach, color);
                context.fill(-reach, -narrow, reach, narrow, color);
            }
            case TRIANGLE -> RenderUtil.triangle(context, 0, -reach, reach, reach, -reach, reach, color);
            case CROSS -> turned(context, () -> {
                context.fill(-reach, -bar, reach, bar, color);
                context.fill(-bar, -reach, bar, reach, color);
            });
            case NONE -> {
            }
        }
    }

    private static void square(GuiGraphicsExtractor context, int half, int color) {
        context.fill(-half, -half, half, half, color);
    }

    private static void turned(GuiGraphicsExtractor context, Runnable drawing) {
        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.rotate(EIGHTH_TURN);
        drawing.run();
        pose.popMatrix();
    }
}
