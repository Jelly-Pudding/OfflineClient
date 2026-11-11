package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
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
        0, 0, 2, 0.05, " blocks");
    private final BoolSetting teamMates = new BoolSetting("Ignore team mates",
        "Leaves players on your scoreboard team alone and players whose name has the colour of yours.",
        false);
    private final BoolSetting babies = new BoolSetting("Ignore babies",
        "Leaves young animals and villagers alone. A baby zombie never grows up and still counts.", true);
    private final BoolSetting pets;
    private final BoolSetting named = new BoolSetting("Ignore named",
        "Leaves name tagged mobs alone.", true);
    private final NeutralMobs neutral = new NeutralMobs(NeutralMobs.When.WHEN_ANGRY);
    private final BoolSetting invisible = new BoolSetting("Ignore invisible",
        "Leaves invisible entities alone.", false);

    // Mobs the pets row has no say over.
    private final Predicate<Mob> spokenFor;

    private boolean ownAges;

    public TargetFilter() {
        spokenFor = mob -> false;
        pets = new BoolSetting("Ignore pets", "Leaves mobs alone that " + EntityUtil.KEPT_WORDS, true);
    }

    // For a module with switches of its own for whole species. A mob such a switch speaks
    // for is not asked about again by the pets row.
    public TargetFilter(Predicate<Mob> spokenFor) {
        this.spokenFor = spokenFor;
        pets = new BoolSetting("Ignore pets", "Leaves mobs alone that " + EntityUtil.KEPT_WORDS
            + " A species with a switch of its own goes by that switch.", true);
    }

    // For a module that picks mob ages itself. The babies row goes away.
    public TargetFilter withoutAges() {
        ownAges = true;
        return this;
    }

    // Hangs the player rows under one setting and the mob rows under another. The neutral
    // rule has a supplier of its own for a module that only takes neutral mobs some of the time.
    public TargetFilter nest(BoolSetting players, Setting<?> mobs, Supplier<Boolean> mobsShown,
                             Supplier<Boolean> neutralShown) {
        for (Setting<?> row : playerRows()) {
            row.under(players);
        }
        for (Setting<?> row : new Setting<?>[] {babies, pets, named}) {
            row.under(mobs, mobsShown);
        }
        neutral.under(mobs, neutralShown);
        return this;
    }

    public Setting<?>[] settings() {
        List<Setting<?>> all = new ArrayList<>(List.of(playerRows()));
        all.addAll(List.of(mobRows()));
        all.add(invisible);
        return all.toArray(Setting<?>[]::new);
    }

    public Setting<?>[] playerRows() {
        return new Setting<?>[] {creative, sleeping, flying, teamMates};
    }

    public Setting<?>[] mobRows() {
        List<Setting<?>> rows = new ArrayList<>();
        if (!ownAges) {
            rows.add(babies);
        }
        rows.add(pets);
        rows.add(named);
        rows.addAll(List.of(neutral.settings()));
        return rows.toArray(Setting<?>[]::new);
    }

    public BoolSetting invisibleRow() {
        return invisible;
    }

    // A target that is no friend and passes the kind filter as well.
    public boolean attackable(Entity entity, EntityFilter filter) {
        return !EntityUtil.isFriend(entity) && filter.matches(entity) && allows(entity);
    }

    // The copy Blink leaves of you is never a target. The practice dummy is.
    public boolean allows(Entity entity) {
        if (Modules.isBot(entity) || Modules.isGhostBody(entity) || invisible.isOn() && entity.isInvisible()
            || EntityUtil.ridesWithYou(entity)) {
            return false;
        }
        if (!(entity instanceof LivingEntity)) {
            return strikeable(entity);
        }
        if (entity instanceof Player player) {
            return !(creative.isOn() && player.isCreative())
                && !(sleeping.isOn() && player.isSleeping())
                && !(flying.getValue() > 0 && floating(player))
                && !(teamMates.isOn() && Teams.isMate(player));
        }
        if (!(entity instanceof Mob mob)) {
            return true;
        }
        if (!ownAges && babies.isOn() && EntityUtil.isYoung(mob) || named.isOn() && mob.hasCustomName()
            || pets.isOn() && !spokenFor.test(mob) && EntityUtil.isKept(mob)) {
            return false;
        }
        return neutral.admit(mob);
    }

    // A thing with no life passes when a swing can land on it. Whilst CrystalAura has someone
    // to fight it times every crystal itself and a stray hit would set one off too soon.
    private static boolean strikeable(Entity entity) {
        if (!EntityUtil.canBeStruck(entity)) {
            return false;
        }
        return !(entity instanceof EndCrystal) || !Modules.crystalsHaveTarget();
    }

    private boolean floating(Player player) {
        return OfflineClient.MC.level.noCollision(player,
            player.getBoundingBox().expandTowards(0, -flying.getValue(), 0));
    }
}
