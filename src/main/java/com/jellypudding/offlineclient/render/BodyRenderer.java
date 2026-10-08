package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector3fc;

import java.util.List;

// Draws one kind of body into its own picture the way the game draws the inventory body.
public final class BodyRenderer<T extends BodyPicture> extends PictureInPictureRenderer<T> {

    private final Class<T> kind;
    private final EntityRenderDispatcher dispatcher;

    private BodyRenderer(Class<T> kind, EntityRenderDispatcher dispatcher) {
        this.kind = kind;
        this.dispatcher = dispatcher;
    }

    // One renderer for each kind of body.
    public static List<PictureInPictureRenderer<?>> forEveryBody(EntityRenderDispatcher dispatcher) {
        return List.of(new BodyRenderer<>(BodyPicture.CombatModel.class, dispatcher),
            new BodyRenderer<>(BodyPicture.PlayerModel.class, dispatcher),
            new BodyRenderer<>(BodyPicture.TooltipMob.class, dispatcher));
    }

    @Override
    public Class<T> getRenderStateClass() {
        return kind;
    }

    @Override
    protected void renderToTexture(T picture, PoseStack pose, SubmitNodeCollector collector) {
        OfflineClient.MC.gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
        BodyPicture.Scene scene = picture.scene();
        Vector3fc translation = scene.translation();
        pose.translate(translation.x(), translation.y(), translation.z());
        pose.rotate(scene.rotation());
        dispatcher.submit(scene.renderState(), new CameraRenderState(), 0, 0, 0, pose, collector);
    }

    // The body stands in the middle of its picture.
    @Override
    protected float getTranslateY(int height, int guiScale) {
        return height / 2f;
    }

    @Override
    protected String getTextureLabel() {
        return kind.getSimpleName();
    }
}
