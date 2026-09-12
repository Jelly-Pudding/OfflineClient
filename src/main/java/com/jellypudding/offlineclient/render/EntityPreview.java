package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

// A slowly turning mob drawn inside a tooltip.
public final class EntityPreview implements ClientTooltipComponent {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;
    private static final int TOP_GAP = 4;
    private static final int FULL_LIGHT = 0xF000F0;
    private static final float SCALE = Math.max(WIDTH, HEIGHT) / 2f * 1.25f;
    private static final float TURN_PER_TICK = 3;

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
        EntityRenderState raw = OfflineClient.MC.getEntityRenderDispatcher().getRenderer(entity)
            .createRenderState(entity, 1f);
        if (!(raw instanceof LivingEntityRenderState state)) {
            return;
        }
        state.lightCoords = FULL_LIGHT;
        state.shadowPieces.clear();
        state.outlineColor = EntityRenderState.NO_OUTLINE;
        state.bodyRot = spin % 360;
        state.yRot = 0;
        state.xRot = 0;

        int left = x + (tooltipWidth - WIDTH) / 2;
        int top = y + TOP_GAP;
        context.entity(state, SCALE, new Vector3f(0, 0.1f, 0),
            new Quaternionf().rotateZ((float) Math.PI), null, left, top, left + WIDTH, top + HEIGHT);
        spin += TURN_PER_TICK * OfflineClient.MC.getDeltaTracker().getGameTimeDeltaTicks();
    }
}
