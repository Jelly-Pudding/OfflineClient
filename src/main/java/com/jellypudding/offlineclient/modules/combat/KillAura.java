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
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TargetFilter;
import com.jellypudding.offlineclient.util.TargetPriority;
import com.jellypudding.offlineclient.util.TickRate;
import com.jellypudding.offlineclient.util.WeaponUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class KillAura extends Module {

    public enum Rotate { NONE, ON_HIT, ALWAYS, CAMERA }

    public enum Shields { NONE, BREAK, IGNORE }

    public enum Ages { ADULTS, BABIES, BOTH }

    public enum Holding { ANYTHING, WEAPONS }

    // How long the server may go quiet before hits are held back.
    private static final long LAG_MILLIS = 1000;

    private static final List<Item> WEAPONS = List.of(Items.WOODEN_SWORD, Items.STONE_SWORD,
        Items.COPPER_SWORD, Items.IRON_SWORD, Items.GOLDEN_SWORD, Items.DIAMOND_SWORD,
        Items.NETHERITE_SWORD, Items.WOODEN_AXE, Items.STONE_AXE, Items.COPPER_AXE, Items.IRON_AXE,
        Items.GOLDEN_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE, Items.MACE, Items.TRIDENT,
        Items.WOODEN_SPEAR, Items.STONE_SPEAR, Items.COPPER_SPEAR, Items.IRON_SPEAR,
        Items.GOLDEN_SPEAR, Items.DIAMOND_SPEAR, Items.NETHERITE_SPEAR);

    private final NumberSetting range = new NumberSetting("Range",
        "Maximum reach in blocks.", 4.2, 1, 6, 0.05);
    private final NumberSetting wallsRange = new NumberSetting("Walls range",
        "Shorter reach for targets you cannot see.", 3.5, 0, 6, 0.05);
    private final BoolSetting walls = new BoolSetting("Through walls",
        "Also swing at targets you cannot see.", true);
    private final NumberSetting fov = new NumberSetting("FOV",
        "Only hit targets within this angle of your view.", 360, 30, 360, 5, " degrees")
        .min(1).max(360);
    private final BoolSetting onlyOnLook = new BoolSetting("Only on look",
        "Only hits whatever your crosshair is already on.", false);

    private final BoolSetting players = new BoolSetting("Players",
        "Swing at other players.", true);
    private final BoolSetting hostile = new BoolSetting("Hostile mobs",
        "Swing at anything that hunts you.", false);
    private final EnumSetting<Ages> hostileAges = new EnumSetting<>("Hostile ages",
        "Which hostile mobs by age.", Ages.BOTH)
        .describe(Ages.ADULTS, "Grown mobs only.")
        .describe(Ages.BABIES, "Baby zombies and piglins only.")
        .describe(Ages.BOTH, "Any age.")
        .under(hostile);
    private final BoolSetting slimes = new BoolSetting("Slimes",
        "Swing at slimes and magma cubes.", true)
        .under(hostile);
    private final BoolSetting shulkers = new BoolSetting("Shulkers",
        "Swing at shulkers.", true)
        .under(hostile);
    private final BoolSetting zombieVillagers = new BoolSetting("Zombie villagers",
        "Swing at zombie villagers. Turn off to keep one for curing.", true)
        .under(hostile);
    private final BoolSetting passive = new BoolSetting("Passive mobs",
        "Swing at animals and villagers and other harmless mobs.", false);
    private final EnumSetting<Ages> passiveAges = new EnumSetting<>("Passive ages",
        "Which passive mobs by age.", Ages.ADULTS)
        .describe(Ages.ADULTS, "Grown animals only.")
        .describe(Ages.BABIES, "Baby animals only.")
        .describe(Ages.BOTH, "Any age.")
        .under(passive);
    private final BoolSetting waterMobs = new BoolSetting("Water mobs",
        "Swing at fish and squid and dolphins.", true)
        .under(passive);
    private final BoolSetting bats = new BoolSetting("Bats",
        "Swing at bats.", true)
        .under(passive);
    private final BoolSetting villagers = new BoolSetting("Villagers",
        "Swing at villagers and wandering traders.", true)
        .under(passive);
    private final BoolSetting golems = new BoolSetting("Golems",
        "Swing at iron golems and snow golems and copper golems.", true)
        .under(passive);
    private final BoolSetting allays = new BoolSetting("Allays",
        "Swing at allays.", true)
        .under(passive);
    private final TargetFilter filter = new TargetFilter().withoutAges()
        .nest(players, hostile, () -> hostile.isOn() || passive.isOn());
    private final RegistryListSetting<EntityType<?>> extraMobs = new RegistryListSetting<>("Also hit",
        "Kinds of entity to hit whatever the switches above say.", BuiltInRegistries.ENTITY_TYPE, List.of());
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
    private final BoolSetting weaponSwapBack = new BoolSetting("Swap back",
        "Returns to the slot you had once nothing is left to hit.", false)
        .under(autoWeapon);
    private final EnumSetting<Shields> shields = new EnumSetting<>("Shields",
        "What to do about a raised shield.", Shields.BREAK)
        .describe(Shields.NONE, "Hits the shield like anything else.")
        .describe(Shields.BREAK, "Reaches for an axe to knock the shield down.")
        .describe(Shields.IGNORE, "Leaves anyone blocking alone.");
    private final EnumSetting<Holding> holding = new EnumSetting<>("Attack when holding",
        "What has to be in your hand for a swing.", Holding.ANYTHING)
        .describe(Holding.ANYTHING, "Swings with whatever you hold.")
        .describe(Holding.WEAPONS, "Only swings whilst you hold one of the weapons below.");
    private final RegistryListSetting<Item> weapons = new RegistryListSetting<>("Weapons",
        "The items that count as a weapon.", BuiltInRegistries.ITEM, WEAPONS)
        .under(holding, Holding.WEAPONS);
    private final BoolSetting onlyOnClick = new BoolSetting("Only on click",
        "Only swing whilst you hold the attack key down.", false);
    private final BoolSetting ignoreCooldown = new BoolSetting("Ignore cooldown",
        "Swings before the attack cooldown ends. Hits land weaker but far more often.", false);
    private final NumberSetting switchDelay = new NumberSetting("Switch delay",
        "Ticks to wait after a hotbar swap before hitting.", 0, 0, 10, 1, " ticks");
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);

    private final BoolSetting pauseOnUse = new BoolSetting("Pause on use",
        "Hold off whilst eating or blocking or drawing a bow or mining.", true);
    private final BoolSetting pauseOnContainers = new BoolSetting("Pause in containers",
        "No swinging whilst a chest or furnace is open. Your own inventory and the menus carry on.", true);
    private final BoolSetting pauseOnCrystals = new BoolSetting("Pause on crystals",
        "Stands aside whilst CrystalAura places or breaks.", true);
    private final BoolSetting pauseOnLag = new BoolSetting("Pause on lag",
        "Holds off whilst the server has stopped ticking.", true);
    private final BoolSetting showTarget = new BoolSetting("Show target",
        "Draws a box inside each target that shrinks and reddens as it loses health.", true);

    private final AttackTimer timer = new AttackTimer();
    private final SlotSwap slots = new SlotSwap();
    private final List<LivingEntity> targets = new ArrayList<>();
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
        addSettings(autoWeapon, weaponSwapBack, shields, holding, weapons, onlyOnClick,
            ignoreCooldown, switchDelay, swing, pauseOnUse, pauseOnContainers, pauseOnCrystals,
            pauseOnLag, showTarget);
        searchTags("aura", "multi aura", "aimbot", "legit aura");
    }

    @Override
    public String getSuffix() {
        return targets.isEmpty() ? range.getValueString() : targets.getFirst().getName().getString();
    }

    // The entity being hit right now. Null between fights.
    public LivingEntity getTarget() {
        return targets.isEmpty() ? null : targets.getFirst();
    }

    @Override
    protected void onEnable() {
        timer.clear();
        slots.forget();
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
        if (!inGame() || mc.player.isSpectator() || holdingFire()) {
            return;
        }
        targets.addAll(pickTargets());
        if (targets.isEmpty()) {
            putWeaponAway();
            return;
        }
        LivingEntity primary = targets.getFirst();
        boolean armed = armWeapon(primary);
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
        return ignoreCooldown.isOn() || mc.player.getAttackStrengthScale(0.5f) >= 1;
    }

    // Every reason the aura sits this tick out.
    private boolean holdingFire() {
        if (pauseOnUse.isOn() && (mc.player.isUsingItem() || mc.gameMode.isDestroying())) {
            return true;
        }
        if (pauseOnContainers.isOn() && InventoryUtil.containerOpen()) {
            return true;
        }
        if (onlyOnClick.isOn() && !mc.options.keyAttack.isDown()) {
            return true;
        }
        if (holding.is(Holding.WEAPONS) && !weapons.contains(mc.player.getMainHandItem().getItem())) {
            return true;
        }
        if (pauseOnLag.isOn() && TickRate.INSTANCE.lagging(LAG_MILLIS)) {
            return true;
        }
        return Modules.eating() || (pauseOnCrystals.isOn() && crystalsBusy());
    }

    // Crystals hurt far more than a sword. Only the ticks around a real
    // place or break are given up.
    private boolean crystalsBusy() {
        CrystalAura crystals = Modules.active(CrystalAura.class);
        return crystals != null && crystals.isActing();
    }

    // Hits everything in reach on one swing then sets the next wait.
    private void strike() {
        LivingEntity primary = targets.getFirst();
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
        // One swing animation covers every entity hit on the tick.
        for (LivingEntity target : targets) {
            mc.gameMode.attack(mc.player, target);
        }
        swing.getValue().swing(InteractionHand.MAIN_HAND);
        timer.spent();
    }

    private static Vec3 aimPoint(LivingEntity target) {
        return target.getBoundingBox().getCenter();
    }

    // Moves the real view towards the target by at most the turn speed.
    private void turnCamera(LivingEntity target) {
        RotationManager.turnCamera(aimPoint(target), rotateSpeed.getFloat());
    }

    // True once the real crosshair rests on the target's box within reach.
    private boolean crosshairOn(LivingEntity target) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1f);
        Vec3 end = eye.add(look.scale(range.getValue()));
        return target.getBoundingBox().clip(eye, end).isPresent();
    }

    // Takes up the best weapon for the target. The server judges a hit by the item held
    // at the end of the last tick. A hit in the tick of a swap waits for the next one.
    // False whilst it waits or when a raised shield is left alone.
    private boolean armWeapon(LivingEntity target) {
        boolean blocking = target.isBlocking();
        if (blocking && shields.is(Shields.IGNORE)) {
            return false;
        }
        if (!autoWeapon.isOn()) {
            return true;
        }
        // An axe staggers a raised shield whilst a sword bounces off it.
        int best = -1;
        if (blocking && shields.is(Shields.BREAK)) {
            best = WeaponUtil.bestAxeSlot(target, true);
        }
        if (best == -1) {
            AutoWeapon chooser = Modules.active(AutoWeapon.class);
            best = chooser != null ? chooser.bestSlot(target) : WeaponUtil.bestWeaponSlot(target);
        }
        if (best == -1 || best == InventoryUtil.selectedSlot()) {
            return true;
        }
        slots.select(best);
        if (!weaponSwapBack.isOn()) {
            slots.forget();
        }
        return false;
    }

    private void putWeaponAway() {
        if (weaponSwapBack.isOn() && inGame()) {
            slots.restoreIfMine();
        } else {
            slots.forget();
        }
    }

    private List<LivingEntity> pickTargets() {
        List<LivingEntity> found = new ArrayList<>();
        if (onlyOnLook.isOn()) {
            if (mc.crosshairPickEntity instanceof LivingEntity living && wanted(living)) {
                found.add(living);
            }
            return found;
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living && wanted(living)) {
                found.add(living);
            }
        }
        TargetPriority order = priority.getValue();
        found.sort((a, b) -> Double.compare(order.score(a), order.score(b)));
        int limit = maxTargets.getInt();
        return found.size() <= limit ? found : found.subList(0, limit);
    }

    private boolean wanted(LivingEntity living) {
        if (living == mc.player || !living.isAlive() || living.isSpectator()
            || EntityUtil.isFriend(living) || ignoredMobs.contains(living.getType())) {
            return false;
        }
        double furthest = walls.isOn() ? Math.max(range.getValue(), wallsRange.getValue()) : range.getValue();
        double distance = EntityUtil.reachDistance(mc.player, living);
        if (distance > furthest || !inFov(living)) {
            return false;
        }
        if (!extraMobs.contains(living.getType()) && !kindWanted(living) || !filter.allows(living)) {
            return false;
        }
        // The line of sight raycast costs the most.
        boolean visible = canSee(living);
        if (!visible && !walls.isOn()) {
            return false;
        }
        return distance <= (visible ? range.getValue() : wallsRange.getValue());
    }

    // Whether the switches above pick out this kind of entity.
    private boolean kindWanted(LivingEntity living) {
        if (living instanceof Player) {
            return players.isOn();
        }
        if (!(living instanceof Mob mob) || !speciesAllowed(mob)) {
            return false;
        }
        if (EntityUtil.kindOf(mob) == EntityUtil.Kind.HOSTILE) {
            return hostile.isOn() && allowedAge(mob, hostileAges.getValue());
        }
        return passive.isOn() && allowedAge(mob, passiveAges.getValue());
    }

    // The switches that pick out one family from the wider hostile or passive group.
    private boolean speciesAllowed(Mob mob) {
        return switch (EntityUtil.speciesOf(mob)) {
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
    private boolean canSee(LivingEntity target) {
        if (mc.player.hasLineOfSight(target)) {
            return true;
        }
        Vec3 eye = mc.player.getEyePosition();
        Vec3 feet = target.position().add(0, 0.1, 0);
        return mc.level.clip(new ClipContext(eye, feet, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    private boolean inFov(LivingEntity target) {
        double limit = fov.getValue();
        return limit >= 360 || EntityUtil.lookAngleTo(target) <= limit / 2;
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
        for (LivingEntity target : targets) {
            float share = EntityUtil.healthShare(target);
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
