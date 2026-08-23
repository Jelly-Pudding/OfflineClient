package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.player.AutoEat;
import com.jellypudding.offlineclient.modules.player.AutoGap;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Random;

public final class TriggerBot extends Module {

    private final NumberSetting range = new NumberSetting("Range",
        "Maximum reach in blocks.", 4.2, 1, 6, 0.05).max(6);
    private final BoolSetting players = new BoolSetting("Players",
        "Swing at players under your crosshair.", true);
    private final BoolSetting mobs = new BoolSetting("Mobs",
        "Swing at mobs under your crosshair.", true);
    private final NumberSetting hitDelay = new NumberSetting("Hit delay",
        "Extra ticks to wait once the attack cooldown is full.", 0, 0, 10, 1, " ticks")
        .min(0);
    private final NumberSetting randomise = new NumberSetting("Randomise",
        "Adds up to this many more ticks to each wait.", 2, 0, 10, 1, " ticks")
        .min(0);

    private final Random random = new Random();

    // Ticks left of the extra wait after the vanilla cooldown fills.
    private int wait;

    public TriggerBot() {
        super("TriggerBot", "Swings at whatever your crosshair is on.", Category.COMBAT);
        addSettings(range, players, mobs, hitDelay, randomise);
    }

    @Override
    protected void onEnable() {
        wait = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.gui.screen() != null || mc.player.isSpectator()) {
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
        if (wait > 0) {
            wait--;
            return;
        }
        if (!(mc.hitResult instanceof EntityHitResult hit)) {
            return;
        }
        if (!(hit.getEntity() instanceof LivingEntity target) || !target.isAlive()) {
            return;
        }
        // The crosshair reaches further than the server allows a hit.
        if (EntityUtil.reachDistance(mc.player, target) > range.getValue()) {
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

        int spread = randomise.getInt();
        wait = hitDelay.getInt() + (spread > 0 ? random.nextInt(spread + 1) : 0);
    }
}
