package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.render.EntityPreview;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.joml.Vector3f;

// Your own body drawn the way the inventory screen draws it.
public final class PlayerModelElement extends HudElement {

    // The inventory screen fits a body of thirty into a box seventy tall.
    private static final float FIT = 30f / 70f;
    // The same box is narrower than it is tall.
    private static final float NARROW = 49f / 70f;
    // Lifts the feet clear of the bottom edge the way the inventory does.
    private static final float FOOTING = 0.0625f;

    private final NumberSetting size = new NumberSetting("Model size",
        "How big the box round your body is.", 60, 30, 160, 5, " px").min(20);
    private final BoolSetting follow = new BoolSetting("Model follows you",
        "The body turns as you turn. Off holds a fixed angle.", true);
    private final NumberSetting angle = new NumberSetting("Model angle",
        "The angle it holds whilst it does not follow you.", 20, -180, 180, 5, " degrees")
        .min(-180).max(180).unless(follow);
    private final BoolSetting background = new BoolSetting("Model background",
        "Draw a panel behind it.", false);
    private final ColorSetting backgroundColor = new ColorSetting("Model background colour",
        "Colour of that panel.", 240, 0.3f, 0.1f, false).under(background);

    public PlayerModelElement() {
        super("Player model", "Your own body drawn on the screen.", false, 4, 55);
        add(size, follow, angle, background, backgroundColor);
    }

    @Override
    public boolean visible() {
        return isActive() && OfflineClient.MC.player != null;
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (background.isOn()) {
            context.fill(0, 0, width(font), height(font), backgroundColor.getColor());
        }
        LivingEntityRenderState state = EntityPreview.flatState(player,
            OfflineClient.MC.getDeltaTracker().getGameTimeDeltaPartialTick(true));
        if (state == null) {
            return;
        }
        // Facing south faces the screen. The state already turns the head on top of the body.
        if (follow.isOn()) {
            state.bodyRot += 180;
        } else {
            state.bodyRot = angle.getFloat();
            state.yRot = 0;
            state.xRot = 0;
        }
        // A body scaled by a potion has to be measured at its normal size.
        state.boundingBoxWidth /= state.scale;
        state.boundingBoxHeight /= state.scale;
        state.scale = 1;

        // The box is given in screen pixels because this draw ignores the pose.
        int left = boxLeft();
        int top = boxTop();
        EntityPreview.draw(context, state, boxHeight() * FIT,
            new Vector3f(0, state.boundingBoxHeight / 2 + FOOTING, 0),
            left, top, left + boxWidth(), top + boxHeight());
    }

    @Override
    public int width(Font font) {
        return Math.round(size.getInt() * NARROW);
    }

    @Override
    public int height(Font font) {
        return size.getInt();
    }
}
