package com.jellypudding.offlineclient.render;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;

// A body the client draws on the screen in a picture of its own. The game keeps one picture for
// each class of state and fills them all before the frame. Two bodies of one class would share it.
public sealed interface BodyPicture extends PictureInPictureRenderState {

    // The body with its turn and lift inside a box given in screen pixels.
    record Scene(EntityRenderState renderState, Vector3fc translation, Quaternionfc rotation,
                 int x0, int y0, int x1, int y1, float scale) {
    }

    record CombatModel(Scene scene) implements BodyPicture {
    }

    record PlayerModel(Scene scene) implements BodyPicture {
    }

    record TooltipMob(Scene scene) implements BodyPicture {
    }

    Scene scene();

    @Override
    default int x0() {
        return scene().x0();
    }

    @Override
    default int y0() {
        return scene().y0();
    }

    @Override
    default int x1() {
        return scene().x1();
    }

    @Override
    default int y1() {
        return scene().y1();
    }

    @Override
    default float scale() {
        return scene().scale();
    }

    // The overlay and tooltips draw with nothing clipped.
    @Override
    default ScreenRectangle scissorArea() {
        return null;
    }

    @Override
    default ScreenRectangle bounds() {
        return PictureInPictureRenderState.getBounds(x0(), y0(), x1(), y1(), null);
    }
}
