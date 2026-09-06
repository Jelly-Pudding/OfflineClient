package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.world.phys.Vec3;

public final class AutoJump extends Module {

    public enum When { SPRINTING, MOVING, ALWAYS }

    public enum Mode { JUMP, LOW_HOP }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the jump is made.", Mode.JUMP)
        .describe(Mode.JUMP, "A normal full height jump.")
        .describe(Mode.LOW_HOP, "A short hop that lifts you by the hop height below.");
    private final NumberSetting hopHeight = new NumberSetting("Hop height",
        "Upward speed of a low hop. A real jump is 0.42.", 0.25, 0, 2, 0.01, "")
        .min(0).under(mode, Mode.LOW_HOP);
    private final EnumSetting<When> when = new EnumSetting<>("Jump when",
        "When a jump is due.", When.SPRINTING)
        .describe(When.SPRINTING, "Jumps only whilst you sprint.")
        .describe(When.MOVING, "Jumps whilst you move at all.")
        .describe(When.ALWAYS, "Jumps even whilst you stand still.");

    public AutoJump() {
        super("AutoJump", "Jumps for you whenever you are on the ground.", Category.MOVEMENT);
        addSettings(mode, hopHeight, when);
        searchTags("bunnyhop", "bhop", "bunny hop");
    }

    @Override
    public String getSuffix() {
        return when.getValueString();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || !mc.player.onGround() || mc.player.isShiftKeyDown()) {
            return;
        }
        if (mc.player.isInWater() || mc.player.isInLava() || mc.player.onClimbable()
            || mc.player.getAbilities().flying || mc.player.isPassenger()) {
            return;
        }
        boolean moving = MovementUtil.inputDirection().lengthSqr() > 0;
        boolean go = switch (when.getValue()) {
            case SPRINTING -> moving && mc.player.isSprinting();
            case MOVING -> moving;
            case ALWAYS -> true;
        };
        if (!go) {
            return;
        }
        if (mode.is(Mode.JUMP)) {
            mc.player.jumpFromGround();
            return;
        }
        Vec3 motion = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(motion.x, hopHeight.getValue(), motion.z);
    }
}
