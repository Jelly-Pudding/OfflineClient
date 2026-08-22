package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

public final class TriggerBot extends Module {

    private final BoolSetting players = new BoolSetting("Players",
        "Swing at players under your crosshair.", true);
    private final BoolSetting mobs = new BoolSetting("Mobs",
        "Swing at mobs under your crosshair.", true);

    public TriggerBot() {
        super("TriggerBot", "Swings at whatever your crosshair is on.", Category.COMBAT);
        addSettings(players, mobs);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.gui.screen() != null || mc.player.isSpectator()) {
            return;
        }
        // No swinging while eating or blocking or drawing a bow.
        if (mc.player.isUsingItem()) {
            return;
        }
        if (mc.player.getAttackStrengthScale(0.5f) < 1) {
            return;
        }
        if (!(mc.hitResult instanceof EntityHitResult hit)) {
            return;
        }
        if (!(hit.getEntity() instanceof LivingEntity target) || !target.isAlive()) {
            return;
        }
        if (target instanceof Player player) {
            if (!players.isOn()) {
                return;
            }
            if (OfflineClient.INSTANCE.getFriendManager().isFriend(player.getGameProfile().name())) {
                return;
            }
        } else if (!mobs.isOn()) {
            return;
        }

        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }
}
