package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.AttackTimer;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.HeldKey;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

import java.util.ArrayList;
import java.util.List;

public final class AutoClicker extends Module {

    // Marked blocks are outlined in this hue unless the colour is changed.
    private static final float MARKED_HUE = 50;

    public enum Left { OFF, HOLD, PRESS, CROSSHAIR }

    public enum Right { OFF, HOLD, PRESS }

    public enum Target { CROSSHAIR, MARKED }

    private final EnumSetting<Left> left = new EnumSetting<>("Left click",
        "What happens to the attack button.", Left.PRESS)
        .describe(Left.OFF, "Leaves the attack button alone.")
        .describe(Left.HOLD, "Keeps the attack button held down. Blocks are mined as if you held it yourself.")
        .describe(Left.PRESS, "Taps the attack button on a timer.")
        .describe(Left.CROSSHAIR,
            "Hits the mob or player under your crosshair whilst you hold the attack button. "
                + "Waits for the full attack cooldown between hits and never hits a friend.");
    private final NumberSetting leftDelay = new NumberSetting("Left delay",
        "Ticks between taps of the attack button.", 2, 0, 60, 1, " ticks")
        .under(left, Left.PRESS);
    private final EnumSetting<Right> right = new EnumSetting<>("Right click",
        "What happens to the use button.", Right.OFF)
        .describe(Right.OFF, "Leaves the use button alone.")
        .describe(Right.HOLD, "Keeps the use button held down. Blocks are placed and food eaten as if you held it yourself.")
        .describe(Right.PRESS, "Taps the use button on a timer.");
    private final NumberSetting rightDelay = new NumberSetting("Right delay",
        "Ticks between taps of the use button.", 2, 0, 60, 1, " ticks")
        .under(right, Right.PRESS);
    private final EnumSetting<Target> useTarget = new EnumSetting<>("Target",
        "What the use button clicks.", Target.CROSSHAIR)
        .describe(Target.CROSSHAIR, "Whatever your crosshair is on.")
        .describe(Target.MARKED, "The blocks you marked one after another wherever you look.")
        .under(right, Right.PRESS);
    private final KeybindSetting markKey = new KeybindSetting("Mark key",
        "Adds or removes the block you look at from the marked blocks.", KeybindSetting.UNBOUND)
        .heardWhilstOff().under(useTarget, Target.MARKED);
    private final BoxStyle markedStyle = new BoxStyle("Marked", BoxStyle.Shape.LINES, MARKED_HUE)
        .under(useTarget, Target.MARKED);
    private final NumberSetting stopAfter = new NumberSetting("Stop after",
        "Switches off after this many clicks. Zero keeps clicking.", 0, 0, 500, 1, " clicks")
        .visibleWhen(this::tapping);
    private final BoolSetting inScreens = new BoolSetting("In screens",
        "Keeps clicking whilst an inventory or another screen is open.", true);

    private int leftTimer;
    private int rightTimer;
    private final HeldKey attackKey = new HeldKey(options -> options.keyAttack);
    private final HeldKey useKey = new HeldKey(options -> options.keyUse);
    private int clicks;

    // The marked blocks in the order they were marked and the one clicked next. They belong to
    // the world they were marked in.
    private final List<BlockPos> marked = new ArrayList<>();
    private int nextMarked;
    private final WorldWatch world = new WorldWatch();

    // Blocks are marked and outlined whilst the clicker is off too.
    private final Object marker = new Object() {
        @Subscribe
        private void onKeyPress(KeyPressEvent event) {
            if (clicksMarked() && inGame() && mc.gui.screen() == null && event.presses(markKey)) {
                markAimed();
            }
        }

        @Subscribe
        private void onRender3D(Render3DEvent event) {
            if (clicksMarked()) {
                syncWorld();
                markedStyle.drawAll(event.getBatch(), marked, false);
            }
        }
    };

    public AutoClicker() {
        super("AutoClicker", "Clicks the mouse buttons for you.", Category.COMBAT);
        addSettings(left, leftDelay, right, rightDelay, useTarget, markKey);
        addSettings(markedStyle.settings());
        addSettings(stopAfter, inScreens);
        searchTags("auto click", "clicker", "spam click");
        watch(marker);
    }

    @Override
    public String getSuffix() {
        return left.getValueString() + " " + right.getValueString();
    }

    @Override
    protected void onEnable() {
        leftTimer = 0;
        rightTimer = 0;
        clicks = 0;
    }

    @Override
    protected void onDisable() {
        letGo();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (Hands.tied()) {
            letGo();
            return;
        }
        if (!inScreens.isOn() && mc.gui.screen() != null) {
            letGo();
            return;
        }
        tickLeft();
        // Stop after may have switched the clicker off.
        if (isEnabled()) {
            tickRight();
        }
    }

    private void tickLeft() {
        if (!left.is(Left.HOLD)) {
            attackKey.letGo();
        }
        switch (left.getValue()) {
            case OFF -> {
            }
            case HOLD -> attackKey.hold();
            case PRESS -> {
                if (++leftTimer > leftDelay.getInt()) {
                    leftTimer = 0;
                    mc.startAttack();
                    counted();
                }
            }
            case CROSSHAIR -> hitCrosshair();
        }
    }

    private void tickRight() {
        if (!right.is(Right.HOLD)) {
            useKey.letGo();
        }
        switch (right.getValue()) {
            case OFF -> {
            }
            case HOLD -> useKey.hold();
            case PRESS -> {
                if (++rightTimer > rightDelay.getInt()) {
                    rightTimer = 0;
                    useOnce();
                }
            }
        }
    }

    // Swings on every full cooldown whilst the button is held and lands the hit
    // only on a living target that is not a friend or a bot.
    private void hitCrosshair() {
        if (!mc.options.keyAttack.isDown()) {
            return;
        }
        if (mc.player.isUsingItem() || BlockMiner.handMines() || Modules.feedersPauseCombat()
            || Modules.maceComboAirborne()) {
            return;
        }
        if (!AttackTimer.charged()) {
            return;
        }
        LivingEntity target = null;
        if (mc.hitResult instanceof EntityHitResult hit
            && hit.getEntity() instanceof LivingEntity living && living.isAlive()) {
            target = living;
        }
        if (target != null && !EntityUtil.isFriend(target) && !Modules.isBot(target)) {
            mc.gameMode.attack(mc.player, target);
        }
        // A click of the plain game swings and punches whether it hit or not.
        SwingMode.BOTH.hit();
        counted();
    }

    // One tap of the use button on the crosshair or on the next marked block in reach.
    private void useOnce() {
        if (useTarget.is(Target.CROSSHAIR)) {
            mc.startUseItem();
            counted();
            return;
        }
        syncWorld();
        for (int tried = 0; tried < marked.size(); tried++) {
            BlockPos pos = marked.get(nextMarked++ % marked.size());
            if (BlockUtil.serverReaches(pos)) {
                BlockUtil.useOn(pos, true, true);
                counted();
                return;
            }
        }
    }

    // Switches the clicker off once Stop after is reached.
    private void counted() {
        if (stopAfter.getInt() > 0 && ++clicks >= stopAfter.getInt()) {
            tell("stopped after §f" + clicks + "§7 clicks.");
            setEnabled(false);
        }
    }

    // Only taps and hits are counted. A held button makes no clicks of its own.
    private boolean tapping() {
        return left.isAny(Left.PRESS, Left.CROSSHAIR) || right.is(Right.PRESS);
    }

    // The use button taps the marked blocks. The Mark key and the outlines only work then.
    private boolean clicksMarked() {
        return right.is(Right.PRESS) && useTarget.is(Target.MARKED);
    }

    private void markAimed() {
        BlockHitResult hit = BlockUtil.aimedBlock();
        if (hit == null) {
            return;
        }
        syncWorld();
        BlockPos pos = hit.getBlockPos().immutable();
        if (marked.remove(pos)) {
            tell("unmarked §f" + BlockUtil.text(pos) + "§7.");
        } else {
            marked.add(pos);
            tell("marked §f" + BlockUtil.text(pos) + "§7.");
        }
    }

    // Blocks marked in another world point at nothing here.
    private void syncWorld() {
        if (world.changed()) {
            marked.clear();
            nextMarked = 0;
        }
    }

    private void letGo() {
        attackKey.letGo();
        useKey.letGo();
    }
}
