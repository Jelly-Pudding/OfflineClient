package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.core.BlockPos;

public final class SelfWeb extends Module {

    public enum Mode {
        ALWAYS("Always"),
        ENEMY_NEAR("Enemy near");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Web right away or wait until somebody comes close.", Mode.ALWAYS);
    private final NumberSetting range = new NumberSetting("Enemy range",
        "How close an enemy has to be.", 4, 1, 10, 0.5, " blocks")
        .visibleWhen(() -> mode.is(Mode.ENEMY_NEAR));
    private final BoolSetting doubles = new BoolSetting("Upper body",
        "Also web the block your head is in.", false);
    private final BoolSetting toggleOff = new BoolSetting("Toggle off when done",
        "Turn off once the web is down.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward the web.", true);

    public SelfWeb() {
        super("SelfWeb", "Webs your own block to stop knockback.", Category.COMBAT);
        addSettings(mode, range, doubles, toggleOff, rotate);
        searchTags("cobweb", "web", "anti knockback");
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (mode.is(Mode.ENEMY_NEAR) && EntityUtil.nearestEnemy(range.getValue()) == null) {
            return;
        }

        BlockPos feet = mc.player.blockPosition();
        AutoWeb.placeWeb(feet, rotate.isOn());
        if (doubles.isOn()) {
            AutoWeb.placeWeb(feet.above(), rotate.isOn());
        }
        // The client puts its own copy down.
        boolean done = !AutoWeb.webbable(feet)
            && (!doubles.isOn() || !AutoWeb.webbable(feet.above()));
        if (toggleOff.isOn() && done) {
            setEnabled(false);
        }
    }
}
