package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.render.Freecam;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import org.lwjgl.glfw.GLFW;

public final class AirJump extends Module {

    private final BoolSetting holdHeight = new BoolSetting("Hold height",
        "Holding jump keeps you at the height of your last jump.", false);

    // The block height the last jump started from.
    private int level;

    public AirJump() {
        super("AirJump", "Lets you jump again whilst in the air.", Category.MOVEMENT);
        addSettings(holdHeight);
        searchTags("double jump", "mid air");
    }

    @Override
    protected void onEnable() {
        if (mc.player != null) {
            level = mc.player.blockPosition().getY();
        }
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.getAction() != GLFW.GLFW_PRESS || !airborne()) {
            return;
        }
        if (InputUtil.isKey(mc.options.keyJump, event.getKey())) {
            level = mc.player.blockPosition().getY();
            mc.player.jumpFromGround();
        } else if (InputUtil.isKey(mc.options.keyShift, event.getKey())) {
            level--;
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!holdHeight.isOn() || !airborne() || !mc.options.keyJump.isDown()) {
            return;
        }
        if (mc.player.blockPosition().getY() == level) {
            mc.player.jumpFromGround();
        }
    }

    private boolean airborne() {
        return inGame() && mc.gui.screen() == null && !mc.player.onGround()
            && !Modules.enabled(Freecam.class);
    }
}
