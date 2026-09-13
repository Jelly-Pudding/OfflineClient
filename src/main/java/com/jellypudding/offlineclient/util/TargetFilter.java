package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

// The state a target has to be in before a combat module acts on it. Goes
// alongside an EntityFilter which only decides the kind of entity.
public final class TargetFilter {

    public enum Neutral { ALWAYS, WHEN_ANGRY, NEVER }

    private final BoolSetting sleeping = new BoolSetting("Ignore sleeping",
        "Leaves players lying in a bed alone.", false);
    private final NumberSetting flying = new NumberSetting("Ignore flying",
        "Leaves players with no block this far below them alone. Zero turns it off.",
        0, 0, 2, 0.05, " blocks").min(0);
    private final BoolSetting babies = new BoolSetting("Ignore babies",
        "Leaves baby mobs alone.", true);
    private final BoolSetting pets = new BoolSetting("Ignore pets",
        "Leaves tamed and saddled animals alone.", true);
    private final BoolSetting invisible = new BoolSetting("Ignore invisible",
        "Leaves invisible entities alone.", false);
    private final BoolSetting named = new BoolSetting("Ignore named",
        "Leaves name tagged mobs alone.", false);
    private final EnumSetting<Neutral> neutral = new EnumSetting<>("Neutral mobs",
        "When a mob that only fights back counts.", Neutral.ALWAYS)
        .describe(Neutral.ALWAYS, "Like any other mob.")
        .describe(Neutral.WHEN_ANGRY, "Only once it has turned on someone.")
        .describe(Neutral.NEVER, "Left alone even whilst angry.");

    public Setting<?>[] settings() {
        return new Setting<?>[] {sleeping, flying, babies, pets, invisible, named, neutral};
    }

    // A living target that is no friend and passes the kind filter as well.
    public boolean attackable(Entity entity, EntityFilter filter) {
        return entity instanceof LivingEntity living && living.isAlive()
            && !EntityUtil.isFriend(entity) && filter.matches(entity) && allows(entity);
    }

    public boolean allows(Entity entity) {
        if (invisible.isOn() && entity.isInvisible()) {
            return false;
        }
        if (entity instanceof Player player) {
            return !(sleeping.isOn() && player.isSleeping())
                && !(flying.getValue() > 0 && floating(player));
        }
        if (!(entity instanceof Mob mob)) {
            return true;
        }
        if (babies.isOn() && mob.isBaby() || pets.isOn() && EntityUtil.isPet(mob)
            || named.isOn() && mob.hasCustomName()) {
            return false;
        }
        return !EntityUtil.isNeutral(mob) || neutral.is(Neutral.ALWAYS)
            || neutral.is(Neutral.WHEN_ANGRY) && !EntityUtil.isCalm(mob);
    }

    private boolean floating(Player player) {
        return OfflineClient.MC.level.noCollision(player,
            player.getBoundingBox().expandTowards(0, -flying.getValue(), 0));
    }
}
