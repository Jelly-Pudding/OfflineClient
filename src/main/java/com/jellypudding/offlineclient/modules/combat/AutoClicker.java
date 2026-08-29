package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
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
        if (EntityUtil.isFriend(target)) {
            target = null;
        }

        if (target != null) {
            mc.gameMode.attack(mc.player, target);
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
    }
}
