package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.phys.Vec3;

/**
 * Holds the effect timers still whilst the player stands still. The count is
 * client side and a server keeps its own.
 */
public final class PotionSaver extends Module {

    // Friction leaves a small drift for a few ticks after the keys go up.
    private static final double STILL = 1.0E-3;

    private final BoolSetting harmful = new BoolSetting("Bad effects",
        "Also freeze harmful effects. Poison and wither run out normally when this is off.",
        false);
    private final BoolSetting inAir = new BoolSetting("In the air",
        "Keep freezing whilst you are falling or swimming.", false);

    public PotionSaver() {
        super("PotionSaver", "Stops your effect timers whilst you stand still.",
            Category.PLAYER);
        addSettings(harmful, inAir);
        searchTags("potion saver", "effect timer", "freeze effects");
    }

    /**
     * Called from the effect tick for every living entity. A single player world
     * runs that tick on the server thread where the client effect map must not
     * be read.
     */
    public boolean shouldFreeze(MobEffectInstance effect) {
        if (!isEnabled() || !mc.isSameThread() || !inGame()) {
            return false;
        }
        // Other entities carry their own instances of the same effect.
        if (mc.player.getActiveEffectsMap().get(effect.getEffect()) != effect) {
            return false;
        }
        if (!harmful.isOn()
            && effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
            return false;
        }
        return standingStill();
    }

    private boolean standingStill() {
        if (!inAir.isOn() && !mc.player.onGround()) {
            return false;
        }
        Vec3 motion = mc.player.getDeltaMovement();
        return Math.abs(motion.x) < STILL && Math.abs(motion.z) < STILL;
    }
}
