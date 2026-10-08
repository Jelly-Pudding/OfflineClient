package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

import java.util.function.Function;

// A slowly turning mob drawn inside a tooltip.
public final class EntityPreview implements ClientTooltipComponent {

    // A body turned this far faces out of the screen.
    public static final float FACING_YOU = 180;

    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;
    private static final int TOP_GAP = 4;
    private static final float SCALE = Math.max(WIDTH, HEIGHT) / 2f * 1.25f;
    private static final float TURN_PER_TICK = 3;
    // A half turn in the plane of the screen stands the body upright.
    private static final Quaternionfc UPRIGHT = new Quaternionf().rotateZ((float) Math.PI);

    // Shared. Every preview turns together and a fresh tooltip does not reset it.
    private static float spin;

    private final LivingEntity entity;

    public EntityPreview(LivingEntity entity) {
        this.entity = entity;
    }

    @Override
    public int getWidth(Font font) {
        return WIDTH;
    }

    @Override
    public int getHeight(Font font) {
        return HEIGHT;
    }

    @Override
    public void extractImage(Font font, int x, int y, int tooltipWidth, int tooltipHeight,
                             GuiGraphicsExtractor context) {
        LivingEntityRenderState state = flatState(entity, 1);
        if (state == null) {
            return;
        }
        state.bodyRot = spin % 360;
        state.yRot = 0;
        state.xRot = 0;

        int left = x + (tooltipWidth - WIDTH) / 2;
        int top = y + TOP_GAP;
        draw(context, BodyPicture.TooltipMob::new, state, SCALE, new Vector3f(0, 0.1f, 0),
            left, top, left + WIDTH, top + HEIGHT);
        spin += TURN_PER_TICK * OfflineClient.MC.getDeltaTracker().getGameTimeDeltaTicks();
    }

    // A living entity lit fully with no shadow or outline. Null for anything else.
    public static LivingEntityRenderState flatState(LivingEntity entity, float partialTick) {
        EntityRenderState raw = OfflineClient.MC.getEntityRenderDispatcher().getRenderer(entity)
            .createRenderState(entity, partialTick);
        if (!(raw instanceof LivingEntityRenderState state)) {
            return null;
        }
        state.lightCoords = LightCoordsUtil.FULL_BRIGHT;
        state.shadowPieces.clear();
        state.outlineColor = EntityRenderState.NO_OUTLINE;
        return state;
    }

    // Draws the entity upright inside the box. Each kind of body has a picture of its own and
    // two bodies of one kind cannot share a frame.
    public static void draw(GuiGraphicsExtractor context, Function<BodyPicture.Scene, BodyPicture> kind,
                            LivingEntityRenderState state, float scale, Vector3f offset,
                            int left, int top, int right, int bottom) {
        context.guiRenderState.addPicturesInPictureState(kind.apply(
            new BodyPicture.Scene(state, offset, UPRIGHT, left, top, right, bottom, scale)));
    }
}
