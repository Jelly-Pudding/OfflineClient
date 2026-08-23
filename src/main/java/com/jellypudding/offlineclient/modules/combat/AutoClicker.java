package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.player.AutoEat;
import com.jellypudding.offlineclient.modules.player.AutoGap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

public final class AutoClicker extends Module {

    public AutoClicker() {
        super("AutoClicker", "Keeps swinging at whatever your crosshair is on whilst you hold the attack key.",
            Category.COMBAT);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.gui.screen() != null || mc.player.isSpectator()) {
            return;
        }
        if (!mc.options.keyAttack.isDown()) {
            return;
        }
        // No swinging whilst eating or blocking or drawing a bow or mining.
        if (mc.player.isUsingItem() || mc.gameMode.isDestroying()) {
            return;
        }
        if (OfflineClient.INSTANCE.getModuleManager()
            .get(AutoEat.class).isEating()
            || OfflineClient.INSTANCE.getModuleManager()
            .get(AutoGap.class).isEating()) {
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
        if (target instanceof Player player && OfflineClient.INSTANCE.getFriendManager()
            .isFriend(player.getGameProfile().name())) {
            target = null;
        }

        if (target != null) {
            mc.gameMode.attack(mc.player, target);
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
    }
}
