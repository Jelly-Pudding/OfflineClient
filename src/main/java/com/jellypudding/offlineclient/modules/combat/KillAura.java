package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.AttackTimer;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityKind;
import com.jellypudding.offlineclient.util.EntitySpecies;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TargetFilter;
import com.jellypudding.offlineclient.util.TargetPriority;
import com.jellypudding.offlineclient.util.Targeting;
import com.jellypudding.offlineclient.util.TickRate;
import com.jellypudding.offlineclient.util.WeaponKinds;
import com.jellypudding.offlineclient.util.WeaponUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class KillAura extends Module implements Targeting {

    public enum Rotate { NONE, ON_HIT, ALWAYS, CAMERA }

    public enum Shields { NONE, BREAK, IGNORE }

    public enum Ages { ADULTS, BABIES, BOTH }

    private final NumberSetting range = new NumberSetting("Range",
        "Maximum reach.", 4.2, 1, 6, 0.05, " blocks");
    private final NumberSetting wallsRange = new NumberSetting("Walls range",
        "Shorter reach for targets you cannot see.", 3.5, 0, 6, 0.05, " blocks");
    private final BoolSetting walls = new BoolSetting("Through walls",
        "Also swings at targets you cannot see.", true);
    private final NumberSetting fov = new NumberSetting("FOV",
        "Only hits targets within this angle of your view.", 360, 30, 360, 5, " degrees")
        .min(1).max(360);
    private final BoolSetting onlyOnLook = new BoolSetting("Only on look",
        "Only hits whatever your crosshair is already on.", false);

    private final BoolSetting players = new BoolSetting("Players",
        "Swings at other players.", true);
    private final BoolSetting hostile = new BoolSetting("Hostile mobs",
        "Swings at anything that hunts you.", false);
    private final EnumSetting<Ages> hostileAges = new EnumSetting<>("Hostile ages",
        "Which hostile mobs by age.", Ages.BOTH)
        .describe(Ages.ADULTS, "Grown mobs only.")
        .describe(Ages.BABIES, "Baby zombies and piglins only.")
        .describe(Ages.BOTH, "Any age.")
        .under(hostile);
    private final BoolSetting slimes = new BoolSetting("Slimes",
        "Swings at slimes and magma cubes.", true)
        .under(hostile);
    private final BoolSetting shulkers = new BoolSetting("Shulkers",
        "Swings at shulkers.", true)
        .under(hostile);
    private final BoolSetting zombieVillagers = new BoolSetting("Zombie villagers",
        "Swings at zombie villagers. Turn off to keep one for curing.", true)
        .under(hostile);
    private final BoolSetting passive = new BoolSetting("Passive mobs",
        "Swings at animals and villagers and other harmless mobs.", false);
    private final EnumSetting<Ages> passiveAges = new EnumSetting<>("Passive ages",
        "Which passive mobs by age.", Ages.ADULTS)
        .describe(Ages.ADULTS, "Grown animals only.")
        .describe(Ages.BABIES, "Baby animals only.")
        .describe(Ages.BOTH, "Any age.")
        .under(passive);
    private final BoolSetting waterMobs = new BoolSetting("Water mobs",
        "Swings at fish and squid and dolphins and axolotls and turtles.", true)
        .under(passive);
    private final BoolSetting bats = new BoolSetting("Bats",
        "Swings at bats.", true)
        .under(passive);
    private final BoolSetting villagers = new BoolSetting("Villagers",
        "Swings at villagers and wandering traders.", true)
        .under(passive);
    private final BoolSetting golems = new BoolSetting("Golems",
        "Swings at iron golems and snow golems and copper golems.", true)
        .under(passive);
    private final BoolSetting allays = new BoolSetting("Allays",
        "Swings at allays.", true)
        .under(passive);
    private final TargetFilter filter =
        new TargetFilter(mob -> EntitySpecies.of(mob) != EntitySpecies.OTHER).withoutAges()
            .nest(players, hostile, () -> hostile.isOn() || passive.isOn(), this::neutralRuleActs);
    private final RegistryListSetting<EntityType<?>> extraMobs = new RegistryListSetting<>("Also hit",
        "Kinds of entity to hit whatever the switches above say. Crystals and boats and other things"
            + " with no life are only hit from this list.", BuiltInRegistries.ENTITY_TYPE, List.of());
    private final RegistryListSetting<EntityType<?>> ignoredMobs = new RegistryListSetting<>("Never hit",
        "Kinds of entity to leave alone whatever the switches above say.", BuiltInRegistries.ENTITY_TYPE, List.of());
    private final EnumSetting<TargetPriority> priority = TargetPriority.setting("Attacks", TargetPriority.NEAREST);
    private final NumberSetting maxTargets = new NumberSetting("Max targets",
        "How many entities to swing at in one go.", 1, 1, 8, 1);

    private final EnumSetting<Rotate> rotate = new EnumSetting<>("Rotate",
        "How you turn towards the target.", Rotate.ALWAYS)
        .describe(Rotate.NONE, "Never turns.")
        .describe(Rotate.ON_HIT, "A look packet only on the tick a hit goes out.")
        .describe(Rotate.ALWAYS, "Look packets follow the target the whole time. Your view stays put.")
        .describe(Rotate.CAMERA, "Turns your real view and only hits once the crosshair rests on the target.");
    private final NumberSetting rotateSpeed = new NumberSetting("Rotate speed",
        "How many degrees the turn may cover in one tick.", 45, 5, 180, 5, " degrees")
        .min(1).max(180)
        .under(rotate, Rotate.ON_HIT, Rotate.ALWAYS, Rotate.CAMERA);

    private final BoolSetting autoWeapon = new BoolSetting("Auto weapon",
        "Holds your best weapon whilst there is something to hit.", false);
    private final BoolSetting weaponFromInventory = new BoolSetting("Search inventory",
        "Also borrows a better weapon from the rest of your inventory and puts it back afterwards.",
        false).under(autoWeapon);
    private final BoolSetting weaponSwapBack = new BoolSetting("Swap back",
        "Returns to the slot you had once nothing is left to hit.", false)
        .under(autoWeapon);
    private final EnumSetting<Shields> shields = new EnumSetting<>("Shields",
        "What to do about a raised shield.", Shields.BREAK)
        .describe(Shields.NONE, "Hits the shield like anything else.")
        .describe(Shields.BREAK, "Takes up an axe to knock the shield down then goes back to your weapon.")
        .describe(Shields.IGNORE, "Leaves anyone blocking alone.");
    private final BoolSetting onlyWithWeapon = new BoolSetting("Only with weapon",
        "Only swings whilst one of the ticked weapons is in your main hand.", false);
    private final WeaponKinds weapons = new WeaponKinds(onlyWithWeapon);
    private final BoolSetting onlyOnClick = new BoolSetting("Only on click",
        "Only swings whilst you hold the attack key down.", false);
    private final BoolSetting ignoreCooldown = new BoolSetting("Ignore cooldown",
        "Swings before the attack cooldown ends. Hits land weaker but far more often.", false);
    private final NumberSetting switchDelay = new NumberSetting("Switch delay",
        "Ticks to wait after a hotbar swap before hitting.", 0, 0, 10, 1, " ticks");
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);

    private final BoolSetting pauseOnUse = new BoolSetting("Pause on use",
        "Holds off whilst eating or blocking or drawing a bow.", true);
    private final BoolSetting pauseOnMine = new BoolSetting("Pause on mine",
        "Holds off whilst you mine a block by hand. A module that mines never holds it off.", true);
    private final BoolSetting pauseOnContainers = new BoolSetting("Pause in containers",
        "No swinging whilst a chest or furnace is open. Your own inventory and the menus carry on.", true);
    private final BoolSetting pauseOnCrystals = new BoolSetting("Pause on crystals",
        "Stands aside whilst CrystalAura places or breaks.", true);
    private final BoolSetting pauseOnLag = new BoolSetting("Pause on lag",
        "Holds off whilst the server has stopped ticking.", true);
    private final BoolSetting showTarget = new BoolSetting("Show target",
        "Draws a box inside each target that shrinks and reddens as it loses health.", true);

    private final AttackTimer timer = new AttackTimer();
    private final HotbarLoan loan = new HotbarLoan();
    private final List<Entity> targets = new ArrayList<>();
    private int switchTimer;

    public KillAura() {
        super("KillAura", "Automatically swings at nearby mobs and players.", Category.COMBAT);
        addSettings(range, wallsRange, walls, fov, onlyOnLook, players);
        addSettings(filter.playerRows());
        addSettings(hostile, hostileAges, slimes, shulkers, zombieVillagers, passive, passiveAges,
            waterMobs, bats, villagers, golems, allays);
        addSettings(filter.mobRows());
        addSettings(extraMobs, ignoredMobs, filter.invisibleRow(), priority, maxTargets, rotate,
            rotateSpeed);
        addSettings(timer.settings());
        addSettings(autoWeapon, weaponFromInventory, weaponSwapBack, shields, onlyWithWeapon);
        addSettings(weapons.settings());
        addSettings(onlyOnClick, ignoreCooldown, switchDelay, swing, pauseOnUse, pauseOnMine, pauseOnContainers,
            pauseOnCrystals, pauseOnLag, showTarget);
        searchTags("aura", "multi aura", "aimbot", "legit aura");
    }

    @Override
    public String getSuffix() {
        return targets.isEmpty() ? range.getValueString() : EntityUtil.displayNameOf(targets.getFirst());
    }

    // The first of the things being hit right now. Null between fights.
    @Override
    public Entity getFought() {
        return targets.isEmpty() ? null : targets.getFirst();
    }

    // The first living thing being hit right now. Null between fights and whilst only crystals
    // or boats are in reach.
    @Override
    public LivingEntity getTarget() {
        for (Entity target : targets) {
            if (target instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    // The neutral rule judges the mobs of both big switches and any neutral kind on the
    // Also hit list. Its rows show whilst one of those gives it something to judge.
    private boolean neutralRuleActs() {
        return hostile.isOn() || passive.isOn() || extraMobs.resolved().stream()
            .anyMatch(type -> EntityKind.of(type) == EntityKind.NEUTRAL);
    }

    @Override
    protected void onEnable() {
        timer.clear();
        loan.forget();
        switchTimer = 0;
        targets.clear();
    }

    @Override
    protected void onDisable() {
        targets.clear();
        putWeaponAway();
    }

    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        targets.clear();
        if (switchTimer > 0) {
            switchTimer--;
        }
        if (!inGame() || Hands.tied() || holdingFire()) {
            return;
        }
        targets.addAll(pickTargets());
        if (targets.isEmpty()) {
            putWeaponAway();
            return;
        }
        Entity primary = targets.getFirst();
        boolean armed = armWeapon(getTarget());
        if (rotate.is(Rotate.CAMERA)) {
            turnCamera(primary);
        } else if (rotate.is(Rotate.ALWAYS)) {
            RotationManager.look(aimPoint(primary), RotationPriority.ATTACK,
                RotationManager.ENTITY_TOLERANCE, rotateSpeed.getFloat());
        }
        if (armed && charged() && timer.ready() && switchTimer == 0) {
            strike();
        }
    }

    // Hits before the attack cooldown ends deal reduced damage.
    private boolean charged() {
        return ignoreCooldown.isOn() || AttackTimer.charged();
    }

    // Every reason the aura sits this tick out.
    private boolean holdingFire() {
        if (pauseOnUse.isOn() && mc.player.isUsingItem() || pauseOnMine.isOn() && BlockMiner.handMines()) {
            return true;
        }
        if (pauseOnContainers.isOn() && InventoryUtil.containerOpen()) {
            return true;
        }
        if (onlyOnClick.isOn() && !mc.options.keyAttack.isDown()) {
            return true;
        }
        if (onlyWithWeapon.isOn() && !weapons.matches(mc.player.getMainHandItem())) {
            return true;
        }
        if (pauseOnLag.isOn() && TickRate.INSTANCE.stalled()) {
            return true;
        }
        // The smash MaceCombo is falling towards hurts far more than a sword.
        if (Modules.maceComboAirborne()) {
            return true;
        }
        // Crystals hurt far more than a sword.
        return Modules.feedersPauseCombat() || (pauseOnCrystals.isOn() && Modules.crystalsActing());
    }

    // Hits everything in reach on one swing then sets the next wait.
    private void strike() {
        Entity primary = targets.getFirst();
        if (rotate.is(Rotate.CAMERA) && !crosshairOn(primary)) {
            return;
        }
        // The swing waits for the turn to land on the server.
        if (rotate.is(Rotate.ALWAYS) && !RotationManager.look(aimPoint(primary),
            RotationPriority.ATTACK, RotationManager.ENTITY_TOLERANCE, rotateSpeed.getFloat())) {
            return;
        }
        if (rotate.is(Rotate.ON_HIT)) {
            RotationManager.look(aimPoint(primary), RotationPriority.ATTACK,
                RotationManager.ENTITY_TOLERANCE, RotationManager.NO_STEP);
        }
        timer.strike(targets, swing.getValue());
    }

    private static Vec3 aimPoint(Entity target) {
        return target.getBoundingBox().getCenter();
    }

    private void turnCamera(Entity target) {
        RotationManager.turnCamera(aimPoint(target), rotateSpeed.getFloat());
    }

    // True once the real crosshair rests on the target's box within reach.
    private boolean crosshairOn(Entity target) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1f);
        Vec3 end = eye.add(look.scale(range.getValue()));
        return target.getBoundingBox().clip(eye, end).isPresent();
    }

    // Takes up the best weapon for the target. A hit never goes out in the tick of a swap. A
    // plain server only counts the damage of the weapon swapped in after its next tick and Paper
    // charges the hit by that weapon's speed at once. False whilst it waits or when a raised
    // shield is left alone. A swap that cannot be made leaves the held item to hit with. A swing
    // with nothing alive among its targets keeps it too since a crystal or a boat falls to any hit.
    private boolean armWeapon(LivingEntity target) {
        if (target == null) {
            return true;
        }
        boolean blocking = target.isBlocking();
        if (blocking && shields.is(Shields.IGNORE)) {
            return false;
        }
        int limit = autoWeapon.isOn() && weaponFromInventory.isOn()
            ? InventoryUtil.WHOLE_INVENTORY : InventoryUtil.HOTBAR_SIZE;
        // An axe staggers a raised shield whilst a sword bounces off it.
        int best = blocking && shields.is(Shields.BREAK) ? WeaponUtil.bestAxeSlot(target, true, limit) : -1;
        if (best == -1 && !autoWeapon.isOn()) {
            // Without Auto weapon an axe is only borrowed whilst the shield is up.
            int held = InventoryUtil.selectedSlot();
            loan.giveBack(loan.stillMine());
            return InventoryUtil.selectedSlot() == held;
        }
        if (best == -1) {
            AutoWeapon chooser = Modules.active(AutoWeapon.class);
            best = chooser != null ? chooser.bestSlot(target, limit) : WeaponUtil.bestWeaponSlot(target, limit);
        }
        if (best == -1 || best == InventoryUtil.selectedSlot()) {
            return true;
        }
        return !loan.select(best);
    }

    // A borrowed weapon always goes home. The slot held before is taken up again unless the
    // player has picked another since. An axe taken for a shield alone always goes back.
    private void putWeaponAway() {
        loan.release((weaponSwapBack.isOn() || !autoWeapon.isOn()) && loan.stillMine());
    }

    // Wanted measures the reach to the hitbox. A cut by the distance to the feet would drop a big
    // mob whose box is in reach.
    private List<Entity> pickTargets() {
        if (onlyOnLook.isOn()) {
            Entity aimed = mc.crosshairPickEntity;
            return aimed != null && wanted(aimed) ? List.of(aimed) : List.of();
        }
        List<Entity> found = EntityUtil.ranked(Double.MAX_VALUE, priority.getValue(), this::wanted);
        int limit = maxTargets.getInt();
        return found.size() <= limit ? found : found.subList(0, limit);
    }

    @Override
    public boolean wouldFight(Entity entity) {
        return candidate(entity) && picked(entity);
    }

    // Never yourself or the dead or a spectator or a friend or a mob on the ignore list.
    private boolean candidate(Entity entity) {
        return entity != mc.player && entity.isAlive() && !entity.isSpectator()
            && !EntityUtil.isFriend(entity) && !ignoredMobs.contains(entity.getType());
    }

    // Whether the rows pick this entity out wherever it stands.
    private boolean picked(Entity entity) {
        return (extraMobs.contains(entity.getType()) || kindWanted(entity)) && filter.allows(entity);
    }

    private boolean wanted(Entity entity) {
        if (!candidate(entity)) {
            return false;
        }
        double furthest = walls.isOn() ? Math.max(range.getValue(), wallsRange.getValue()) : range.getValue();
        double distance = EntityUtil.reachDistance(mc.player, entity);
        if (distance > furthest || !inFov(entity)) {
            return false;
        }
        if (!picked(entity)) {
            return false;
        }
        // The line of sight raycast costs the most.
        boolean visible = canSee(entity);
        if (!visible && !walls.isOn()) {
            return false;
        }
        return distance <= (visible ? range.getValue() : wallsRange.getValue());
    }

    // Whether the switches above pick out this kind of entity.
    private boolean kindWanted(Entity entity) {
        if (entity instanceof Player) {
            return players.isOn();
        }
        if (!(entity instanceof Mob mob) || !speciesAllowed(mob)) {
            return false;
        }
        return goesWithHostile(mob) ? hostile.isOn() && allowedAge(mob, hostileAges.getValue())
            : passive.isOn() && allowedAge(mob, passiveAges.getValue());
    }

    // Which of the two big switches a mob answers to. A species with a row of its own goes
    // with the switch that row hangs under whatever the single mob is like. Every other mob
    // goes by whether the game counts it a monster. An enderman is one and a wolf is not and
    // the neutral rule then says when either is hit.
    private static boolean goesWithHostile(Mob mob) {
        return switch (EntitySpecies.of(mob)) {
            case SLIME, SHULKER, ZOMBIE_VILLAGER -> true;
            case WATER, BAT, VILLAGER, GOLEM, ALLAY -> false;
            case OTHER -> mob instanceof Enemy;
        };
    }

    // The switches that pick out one family from the wider hostile or passive group.
    private boolean speciesAllowed(Mob mob) {
        return switch (EntitySpecies.of(mob)) {
            case WATER -> waterMobs.isOn();
            case BAT -> bats.isOn();
            case SLIME -> slimes.isOn();
            case SHULKER -> shulkers.isOn();
            case VILLAGER -> villagers.isOn();
            case ZOMBIE_VILLAGER -> zombieVillagers.isOn();
            case GOLEM -> golems.isOn();
            case ALLAY -> allays.isOn();
            case OTHER -> true;
        };
    }

    private static boolean allowedAge(Mob mob, Ages ages) {
        return switch (ages) {
            case ADULTS -> !mob.isBaby();
            case BABIES -> mob.isBaby();
            case BOTH -> true;
        };
    }

    // Either the head or the feet being visible is enough to land a hit.
    private boolean canSee(Entity target) {
        return mc.player.hasLineOfSight(target) || BlockUtil.canSee(target.position().add(0, 0.1, 0));
    }

    private boolean inFov(Entity target) {
        double limit = fov.getValue();
        return limit >= 360 || EntityUtil.lookAngleToNearest(target) <= limit / 2;
    }

    // A hotbar swap the server has to hear about before a hit is believed.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (event.getPacket() instanceof ServerboundSetCarriedItemPacket) {
            switchTimer = switchDelay.getInt();
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!showTarget.isOn() || targets.isEmpty()) {
            return;
        }
        for (Entity target : targets) {
            // A thing with no health gets the full green box.
            float share = target instanceof LivingEntity living ? EntityUtil.healthShare(living) : 1;
            AABB box = EntityUtil.lerpedBox(target, event.getPartialTicks());
            Vec3 centre = box.getCenter();
            AABB inner = new AABB(centre, centre).inflate(box.getXsize() / 2 * share,
                box.getYsize() / 2 * share, box.getZsize() / 2 * share);
            int color = ColorUtil.redToGreen(share);
            event.getBatch().outlineBox(inner, color, true);
            event.getBatch().solidBox(inner, ColorUtil.withAlpha(color, 60), true);
        }
    }
}
