package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.network.chat.Component;

public final class AutoLog extends Module {

    private final NumberSetting health = new NumberSetting("Health",
        "Disconnect at or below this many hearts.", 3, 0.5, 9.5, 0.5, " hearts");

    public AutoLog() {
        super("AutoLog", "Logs you out when your health gets low.", Category.MISC);
        addSettings(health);
    }

    @Override
    public String getSuffix() {
        return health.getValueString();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isDeadOrDying()) {
            return;
        }
        // Absorption hearts from golden apples and totems count as health.
        float total = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (total > health.getFloat() * 2f) {
            return;
        }
        // Turning off first stops an instant logout after reconnecting.
        setEnabled(false);
        mc.player.connection.getConnection().disconnect(
            Component.literal("§b[§3Offline§b] §fAutoLog saved you.\n§7Health was at or below §c"
                + health.getValueString() + "§7."));
    }
}
