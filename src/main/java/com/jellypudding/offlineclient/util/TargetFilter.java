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

import java.util.function.Supplier;

// The state a target has to be in before a combat module acts on it. Goes
// alongside an EntityFilter or a module's own switches which decide the kind of entity.
public final class TargetFilter {

    private final BoolSetting creative = new BoolSetting("Ignore creative",
        "Leaves players in creative mode alone. They cannot be hurt.", true);
    private final BoolSetting sleeping = new BoolSetting("Ignore sleeping",
        "Leaves players lying in a bed alone.", false);
    private final NumberSetting flying = new NumberSetting("Ignore flying",
        "Leaves players with no block this far below them alone. Zero turns it off.",
        0, 0, 2, 0.05, " blocks").min(0);
    private final BoolSetting babies = new BoolSetting("Ignore babies",
        "Leaves baby mobs alone.", true);
    private final BoolSetting pets = new BoolSetting("Ignore pets",
        "Leaves tamed and saddled and trusting animals alone.", true);
    private final BoolSetting named = new BoolSetting("Ignore named",
        "Leaves name tagged mobs alone.", true);
    private final EnumSetting<NeutralMobs> neutral = NeutralMobs.setting(NeutralMobs.WHEN_ANGRY);
    private final BoolSetting invisible = new BoolSetting("Ignore invisible",
        "Leaves invisible entities alone.", false);

    private boolean ownAges;

    // For a module that picks mob ages itself. The babies row goes away.
    public TargetFilter withoutAges() {
        ownAges = true;
        return this;
    }

    // Hangs the player rows under one setting and the mob rows under another.
    public TargetFilter nest(BoolSetting players, Setting<?> mobs, Supplier<Boolean> mobsShown) {
        for (Setting<?> row : playerRows()) {
            row.under(players);
        }
        for (Setting<?> row : mobRows()) {
            row.under(mobs, mobsShown);
        }
        return this;
    }

    public Setting<?>[] settings() {
        Setting<?>[] players = playerRows();
        Setting<?>[] mobs = mobRows();
        Setting<?>[] all = new Setting<?>[players.length + mobs.length + 1];
        System.arraycopy(players, 0, all, 0, players.length);
        System.arraycopy(mobs, 0, all, players.length, mobs.length);
        all[all.length - 1] = invisible;
        return all;
    }

    public Setting<?>[] playerRows() {
        return new Setting<?>[] {creative, sleeping, flying};
    }

    public Setting<?>[] mobRows() {
        return ownAges ? new Setting<?>[] {pets, named, neutral}
            : new Setting<?>[] {babies, pets, named, neutral};
    }

    public BoolSetting invisibleRow() {
        return invisible;
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
            return !(creative.isOn() && player.isCreative())
                && !(sleeping.isOn() && player.isSleeping())
                && !(flying.getValue() > 0 && floating(player));
        }
        if (!(entity instanceof Mob mob)) {
            return true;
        }
        if (!ownAges && babies.isOn() && mob.isBaby() || pets.isOn() && EntityUtil.isPet(mob)
            || named.isOn() && mob.hasCustomName()) {
            return false;
        }
        return neutral.getValue().admit(mob);
    }

    private boolean floating(Player player) {
        return OfflineClient.MC.level.noCollision(player,
            player.getBoundingBox().expandTowards(0, -flying.getValue(), 0));
    }
}
