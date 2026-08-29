package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * EntityMixin feeds the expansion into the crosshair pick and
 * AttackRangeMixin widens the matching reach check.
 */
public final class Hitboxes extends Module {

    private final NumberSetting expand = new NumberSetting("Expand",
        "How far the hitbox grows on every side.", 0.25, 0.05, 1, 0.05, " blocks")
        .min(0).max(2);
    private final BoolSetting players = new BoolSetting("Players",
        "Grow player hitboxes.", true);
    private final BoolSetting mobs = new BoolSetting("Mobs",
        "Grow mob hitboxes.", false);

    public Hitboxes() {
        super("Hitboxes", "Makes entities easier to hit by growing their hitboxes.", Category.COMBAT);
        addSettings(expand, players, mobs);
        searchTags("aim", "reach", "pick");
    }

    @Override
    public String getSuffix() {
        return expand.getValueString();
    }

    // Extra pick radius for one entity. Zero keeps it vanilla.
    public double expansionFor(Entity entity) {
        if (!isEnabled() || entity == mc.player) {
            return 0;
        }
        if (entity instanceof Player player) {
            if (!players.isOn()) {
                return 0;
            }
            if (EntityUtil.isFriend(player)) {
                return 0;
            }
            return expand.getValue();
        }
        if (entity instanceof LivingEntity) {
            return mobs.isOn() ? expand.getValue() : 0;
        }
        return 0;
    }

    // Extra margin for the attack range check.
    public float margin() {
        if (!isEnabled() || (!players.isOn() && !mobs.isOn())) {
            return 0;
        }
        return expand.getFloat();
    }
}
