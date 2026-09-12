package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.RotationManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import java.util.Locale;

// Changes how the first person hands look and move.
// Every hook lives in ItemInHandRendererMixin and LivingEntityMixin.
public final class HandView extends Module {

    public enum SwingHand { NORMAL, MAIN_HAND, OFF_HAND }

    private final BoolSetting serverRotations = new BoolSetting("Server rotations",
        "The hands follow the angle the server sees rather than your camera.", false);
    private final BoolSetting oldAnimations = new BoolSetting("Old animations",
        "Hits and swaps snap the way they did in older versions.", false);
    private final BoolSetting skipSwap = new BoolSetting("Skip swap animation",
        "A new item appears in hand at once instead of rising into view.", false);
    private final BoolSetting noEating = new BoolSetting("No eating animation",
        "Food stays still whilst you eat instead of bobbing off screen.", false);
    private final BoolSetting swordSlash = new BoolSetting("Sword slash",
        "A sword swing uses the resting pose and the blade never fills the screen.", false);
    private final EnumSetting<SwingHand> swingHand = new EnumSetting<>("Swing hand",
        "Which hand is swung on a hit.", SwingHand.NORMAL)
        .describe(SwingHand.NORMAL, "Whichever hand the game would swing.")
        .describe(SwingHand.MAIN_HAND, "Always the main hand.")
        .describe(SwingHand.OFF_HAND, "Always the off hand.");
    private final NumberSetting swingSpeed = new NumberSetting("Swing speed",
        "Ticks one swing takes.", 6, 1, 20, 1, " ticks").min(1);
    private final NumberSetting mainSwing = new NumberSetting("Main hand progress",
        "Adds to how far through a swing the main hand is drawn.", 0, -1, 1, 0.05);
    private final NumberSetting offSwing = new NumberSetting("Off hand progress",
        "Adds to how far through a swing the off hand is drawn.", 0, -1, 1, 0.05);

    private final Adjust mainHand = new Adjust("Main hand", "the item in your main hand");
    private final Adjust offHand = new Adjust("Off hand", "the item in your off hand");
    private final Adjust arm = new Adjust("Arm", "your bare arm");

    public HandView() {
        super("HandView", "Reshapes and repositions your first person hands.", Category.RENDER);
        addSettings(serverRotations, oldAnimations, skipSwap, noEating, swordSlash,
            swingHand, swingSpeed, mainSwing, offSwing);
        addSettings(mainHand.settings());
        addSettings(offHand.settings());
        addSettings(arm.settings());
        searchTags("hand position", "viewmodel", "swing");
    }

    // Runs just before the held item is drawn.
    public void adjustItem(InteractionHand hand, PoseStack pose) {
        if (!isEnabled()) {
            return;
        }
        (hand == InteractionHand.MAIN_HAND ? mainHand : offHand).apply(pose);
        followServer(pose);
    }

    // Runs just before an empty arm is drawn.
    public void adjustArm(PoseStack pose) {
        if (!isEnabled()) {
            return;
        }
        arm.apply(pose);
        followServer(pose);
    }

    // Turns the hand by the gap between the camera and the angle the server holds.
    private void followServer(PoseStack pose) {
        if (!serverRotations.isOn() || mc.player == null) {
            return;
        }
        pose.mulPose(Axis.XP.rotationDegrees(mc.player.getXRot() - RotationManager.serverPitch()));
        pose.mulPose(Axis.YP.rotationDegrees(mc.player.getYRot() - RotationManager.serverYaw()));
    }

    // How far through its swing the hand is drawn. Zero is the resting pose.
    public float adjustSwing(float progress, ItemStack mainItem, ItemStack offItem) {
        if (!isEnabled() || mc.player == null) {
            return progress;
        }
        InteractionHand hand = mc.player.swingingArm == null
            ? InteractionHand.MAIN_HAND : mc.player.swingingArm;
        if (hand == InteractionHand.MAIN_HAND) {
            if (swordSlash.isOn() && mainItem.is(ItemTags.SWORDS)) {
                return 0;
            }
            // The slider reaches minus one and the pose has no meaning below zero.
            return mainItem.isEmpty() ? progress : Math.max(0f, progress + mainSwing.getFloat());
        }
        return offItem.isEmpty() ? progress : Math.max(0f, progress + offSwing.getFloat());
    }

    public boolean skipsSwap() {
        return isEnabled() && skipSwap.isOn();
    }

    public boolean usesOldAnimations() {
        return isEnabled() && oldAnimations.isOn();
    }

    public boolean hidesEating() {
        return isEnabled() && noEating.isOn();
    }

    // The hand a hit swings. Only your own hits are changed.
    public InteractionHand swingHand(InteractionHand asked) {
        if (!isEnabled() || swingHand.is(SwingHand.NORMAL)) {
            return asked;
        }
        return swingHand.is(SwingHand.OFF_HAND) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    // Ticks one swing lasts in first person.
    public int swingDuration(int vanilla) {
        if (!isEnabled() || !mc.options.getCameraType().isFirstPerson()) {
            return vanilla;
        }
        return swingSpeed.getInt();
    }

    // Scale and offset and turn for one drawn thing.
    private static final class Adjust {

        private final BoolSetting enabled;
        private final NumberSetting scaleX;
        private final NumberSetting scaleY;
        private final NumberSetting scaleZ;
        private final NumberSetting x;
        private final NumberSetting y;
        private final NumberSetting z;
        private final NumberSetting pitch;
        private final NumberSetting yaw;
        private final NumberSetting roll;

        Adjust(String label, String what) {
            enabled = new BoolSetting("Adjust " + label.toLowerCase(Locale.ROOT),
                "Reshapes " + what + ".", false);
            scaleX = new NumberSetting(label + " scale x",
                "How wide it is drawn.", 1, 0.1, 3, 0.05, "x").min(0.01).max(5).under(enabled);
            scaleY = new NumberSetting(label + " scale y",
                "How tall it is drawn.", 1, 0.1, 3, 0.05, "x").min(0.01).max(5).under(enabled);
            scaleZ = new NumberSetting(label + " scale z",
                "How deep it is drawn.", 1, 0.1, 3, 0.05, "x").min(0.01).max(5).under(enabled);
            x = new NumberSetting(label + " x",
                "Sideways offset.", 0, -2, 2, 0.05).under(enabled);
            y = new NumberSetting(label + " y",
                "Up and down offset.", 0, -2, 2, 0.05).under(enabled);
            z = new NumberSetting(label + " z",
                "Toward and away offset.", 0, -2, 2, 0.05).under(enabled);
            pitch = new NumberSetting(label + " pitch",
                "Tilt up or down.", 0, -180, 180, 5, "°").under(enabled);
            yaw = new NumberSetting(label + " yaw",
                "Turn left or right.", 0, -180, 180, 5, "°").under(enabled);
            roll = new NumberSetting(label + " roll",
                "Roll about the forward axis.", 0, -180, 180, 5, "°").under(enabled);
        }

        Setting<?>[] settings() {
            return new Setting<?>[] { enabled, scaleX, scaleY, scaleZ, x, y, z, pitch, yaw, roll };
        }

        void apply(PoseStack pose) {
            if (!enabled.isOn()) {
                return;
            }
            pose.mulPose(Axis.XP.rotationDegrees(pitch.getFloat()));
            pose.mulPose(Axis.YP.rotationDegrees(yaw.getFloat()));
            pose.mulPose(Axis.ZP.rotationDegrees(roll.getFloat()));
            pose.scale(scaleX.getFloat(), scaleY.getFloat(), scaleZ.getFloat());
            pose.translate(x.getFloat(), y.getFloat(), z.getFloat());
        }
    }
}
