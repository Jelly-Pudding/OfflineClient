package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;

public final class AutoClicker extends Module {

    public enum Left { OFF, HOLD, PRESS, CROSSHAIR }

    public enum Right { OFF, HOLD, PRESS }

    private final EnumSetting<Left> left = new EnumSetting<>("Left click",
        "What happens to the attack button.", Left.PRESS)
        .describe(Left.OFF, "Leaves the attack button alone.")
        .describe(Left.HOLD, "Keeps the attack button held down. Blocks are mined as if you held it yourself.")
        .describe(Left.PRESS, "Taps the attack button on a timer.")
        .describe(Left.CROSSHAIR,
            "Hits the mob or player under your crosshair whilst you hold the attack button. "
                + "Waits for the full attack cooldown between hits and never hits a friend.");
    private final NumberSetting leftDelay = new NumberSetting("Left delay",
        "Ticks between taps of the attack button.", 2, 0, 60, 1, " ticks").min(0)
        .under(left, Left.PRESS);
    private final EnumSetting<Right> right = new EnumSetting<>("Right click",
        "What happens to the use button.", Right.OFF)
        .describe(Right.OFF, "Leaves the use button alone.")
        .describe(Right.HOLD, "Keeps the use button held down. Blocks are placed and food eaten as if you held it yourself.")
        .describe(Right.PRESS, "Taps the use button on a timer.");
    private final NumberSetting rightDelay = new NumberSetting("Right delay",
        "Ticks between taps of the use button.", 2, 0, 60, 1, " ticks").min(0)
        .under(right, Right.PRESS);
    private final BoolSetting inScreens = new BoolSetting("In screens",
        "Keeps clicking whilst an inventory or another screen is open.", true);

    private int leftTimer;
    private int rightTimer;
    private boolean holdingAttack;
    private boolean holdingUse;

    public AutoClicker() {
        super("AutoClicker", "Clicks the mouse buttons for you.", Category.COMBAT);
        addSettings(left, leftDelay, right, rightDelay, inScreens);
        searchTags("auto click", "clicker", "spam click");
    }

    @Override
    public String getSuffix() {
        return left.getValueString() + " " + right.getValueString();
    }

    @Override
    protected void onEnable() {
        leftTimer = 0;
        rightTimer = 0;
    }

    @Override
    protected void onDisable() {
        letGo();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            letGo();
            return;
        }
        if (!inScreens.isOn() && mc.gui.screen() != null) {
            letGo();
            return;
        }
        tickLeft();
        tickRight();
    }

    private void tickLeft() {
        if (!left.is(Left.HOLD)) {
            holdingAttack = release(mc.options.keyAttack, holdingAttack);
        }
        switch (left.getValue()) {
            case OFF -> {
            }
            case HOLD -> {
                InputUtil.hold(mc.options.keyAttack);
                holdingAttack = true;
            }
            case PRESS -> {
                if (++leftTimer > leftDelay.getInt()) {
                    leftTimer = 0;
                    mc.startAttack();
                }
            }
            case CROSSHAIR -> hitCrosshair();
        }
    }

    private void tickRight() {
        if (!right.is(Right.HOLD)) {
            holdingUse = release(mc.options.keyUse, holdingUse);
        }
        switch (right.getValue()) {
            case OFF -> {
            }
            case HOLD -> {
                InputUtil.hold(mc.options.keyUse);
                holdingUse = true;
            }
            case PRESS -> {
                if (++rightTimer > rightDelay.getInt()) {
                    rightTimer = 0;
                    mc.startUseItem();
                }
            }
        }
    }

    // The old helper. Swings on every full cooldown whilst the button is held
    // and lands the hit only on a living target that is not a friend.
    private void hitCrosshair() {
        if (!mc.options.keyAttack.isDown()) {
            return;
        }
        if (mc.player.isUsingItem() || mc.gameMode.isDestroying() || Modules.eating()) {
            return;
        }
        if (mc.player.getAttackStrengthScale(0.5f) < 1) {
            return;
        }
        LivingEntity target = null;
        if (mc.hitResult instanceof EntityHitResult hit
            && hit.getEntity() instanceof LivingEntity living && living.isAlive()) {
            target = living;
        }
        if (target != null && !EntityUtil.isFriend(target)) {
            mc.gameMode.attack(mc.player, target);
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void letGo() {
        holdingAttack = release(mc.options.keyAttack, holdingAttack);
        holdingUse = release(mc.options.keyUse, holdingUse);
    }

    // Always false afterwards. Only a key this module pressed is let go of.
    private static boolean release(KeyMapping key, boolean held) {
        if (held) {
            InputUtil.release(key);
        }
        return false;
    }
}
