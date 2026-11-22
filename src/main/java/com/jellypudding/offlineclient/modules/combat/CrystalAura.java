package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.EntityAddedEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.AttackTimer;
import com.jellypudding.offlineclient.util.BlastLimits;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.Crystals;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.Targeting;
import com.jellypudding.offlineclient.util.TickRate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CrystalAura extends Module implements Targeting {

    public enum Switch { NORMAL, SILENT, NONE }

    public enum Support { OFF, ACCURATE, FAST }

    public enum Pause { BOTH, PLACE, BREAK, NONE }

    public enum YawSteps { BREAK, ALL }

    public enum RenderMode { NORMAL, SMOOTH, FADING, GRADIENT, NONE }

    private static final double LEAD_TICKS = 1;

    private static final int OWN_MEMORY = 100;

    private static final int REFILL_DELAY = 4;

    private static final int REMOVED_MEMORY = 3;

    private static final int BUSY_TICKS = 8;

    private static final int SUPPORT_REACH = 3;
    private static final int SUPPORT_RISE = 2;

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away targets are considered.", 10, 2, 16, 0.5, " blocks");
    private final BoolSetting players = new BoolSetting("Players",
        "Attacks other players.", true);
    private final RegistryListSetting<EntityType<?>> mobs = new RegistryListSetting<>("Mobs",
        "Other kinds of entity to attack.", BuiltInRegistries.ENTITY_TYPE,
        List.of(EntityTypes.WARDEN, EntityTypes.WITHER));
    private final BoolSetting ignoreNakeds = new BoolSetting("Ignore nakeds",
        "Leaves players with no armour and empty hands alone.", false);

    private final NumberSetting placeRange = new NumberSetting("Place range",
        "Reach for placing crystals.", 4.5, 1, 6, 0.1, " blocks");
    private final NumberSetting placeWallsRange = new NumberSetting("Place walls range",
        "Reach for placing on a spot hidden behind blocks.", 3.5, 0, 6, 0.1, " blocks");
    private final NumberSetting breakRange = new NumberSetting("Break range",
        "Reach for hitting crystals.", 4.5, 1, 6, 0.1, " blocks");
    private final NumberSetting breakWallsRange = new NumberSetting("Break walls range",
        "Reach for crystals hidden behind blocks.", 3.5, 0, 6, 0.1, " blocks");

    private final BoolSetting doPlace = new BoolSetting("Place",
        "Places crystals near targets.", true);
    private final NumberSetting placeDelay = new NumberSetting("Place delay",
        "Ticks to wait between placements.", 2, 0, 10, 1, " ticks")
        .under(doPlace);
    private final BoolSetting multiPlace = new BoolSetting("Multi place",
        "Places another crystal whilst one worth hitting is still up.", false)
        .under(doPlace);
    private final BoolSetting oldPlacement = new BoolSetting("Old placement",
        "Requires two air blocks above the base like older servers.", false)
        .under(doPlace);
    private final EnumSetting<Support> support = new EnumSetting<>("Support",
        "Places obsidian under a target when there is nothing to crystal. Taken from the inventory when the hotbar has none.", Support.ACCURATE)
        .describe(Support.OFF, "Never places support blocks.")
        .describe(Support.ACCURATE, "Scores support spots against every target.")
        .describe(Support.FAST, "Scores support spots against the nearest target only.")
        .under(doPlace);
    private final NumberSetting supportDelay = new NumberSetting("Support delay",
        "Ticks to wait after a support block before the crystal goes on it. Zero does both in one tick.", 1, 0, 10, 1, " ticks")
        .under(support, Support.ACCURATE, Support.FAST);

    private final BoolSetting doBreak = new BoolSetting("Break",
        "Hits crystals to set them off.", true);
    private final NumberSetting breakDelay = new NumberSetting("Break delay",
        "Ticks to wait between hits.", 1, 0, 10, 1, " ticks")
        .under(doBreak);
    private final NumberSetting attackFrequency = new NumberSetting("Attack frequency",
        "Most hits a second.", 25, 1, 30, 1)
        .under(doBreak);
    private final NumberSetting breakAttempts = new NumberSetting("Break attempts",
        "Gives up on a crystal after this many hits.", 2, 1, 5, 1)
        .under(doBreak);
    private final NumberSetting ticksExisted = new NumberSetting("Ticks existed",
        "Youngest a crystal may be before it is hit.", 0, 0, 20, 1, " ticks")
        .under(doBreak);
    private final BoolSetting fastBreak = new BoolSetting("Fast break",
        "Hits a crystal the moment it appears rather than on the next tick.", true)
        .under(doBreak);
    private final BoolSetting smartDelay = new BoolSetting("Smart delay",
        "Skips a hit whilst the target is still in damage immunity.", true)
        .under(doBreak);
    private final BoolSetting antiWeakness = new BoolSetting("Anti weakness",
        "Swaps to a tool that still breaks crystals whilst you have weakness.", true)
        .under(doBreak);
    private final BoolSetting onlyOwn = new BoolSetting("Only own",
        "Only hits crystals this module placed in the last five seconds.", false)
        .under(doBreak);

    private final NumberSetting minDamage = new NumberSetting("Min damage",
        "Only acts when a target would take at least this much.", 6, 0, 20, 0.5);
    private final NumberSetting minRatio = new NumberSetting("Min ratio",
        "Only acts when a target would take at least this many times what you take. Zero switches the rule off.",
        0, 0, 5, 0.25, "x");
    private final BlastLimits limits = new BlastLimits(8, 20);
    private final BoolSetting predict = new BoolSetting("Predict",
        "Scores the damage where a moving target is heading.", true);

    private final BoolSetting finisher = new BoolSetting("Finisher",
        "Drops the minimum damage once a target is nearly dead or their armour is about to give out.", true);
    private final NumberSetting finisherHealth = new NumberSetting("Finisher health",
        "Health plus absorption at or below this counts as nearly dead.", 8, 1, 20, 0.5)
        .under(finisher);
    private final NumberSetting finisherArmour = new NumberSetting("Finisher armour",
        "Armour durability at or below this counts as giving out.", 15, 1, 100, 1, "%")
        .under(finisher);
    private final BoolSetting finisherMissing = new BoolSetting("Finisher missing armour",
        "An empty armour slot also counts.", false)
        .under(finisher);
    private final KeybindSetting finisherKey = new KeybindSetting("Finisher key",
        "Holding this key forces the finisher on.", KeybindSetting.UNBOUND);

    private final EnumSetting<Switch> autoSwitch = new EnumSetting<>("Auto switch",
        "How crystals get into your hand.", Switch.SILENT)
        .describe(Switch.NORMAL, "Swaps to the crystals and stays on them.")
        .describe(Switch.SILENT, "Swaps for the placement and straight back.")
        .describe(Switch.NONE, "Never swaps. Only places whilst you hold crystals.");
    private final NumberSetting switchDelay = new NumberSetting("Switch delay",
        "Ticks to wait after a hotbar swap before hitting.", 0, 0, 10, 1, " ticks")
        .under(autoSwitch, Switch.NORMAL, Switch.SILENT);
    private final BoolSetting noGapSwitch = new BoolSetting("No gap switch",
        "Never swaps away from a golden apple in either hand.", true)
        .under(autoSwitch, Switch.NORMAL);
    private final BoolSetting noBowSwitch = new BoolSetting("No bow switch",
        "Never swaps away from a bow in either hand.", true)
        .under(autoSwitch, Switch.NORMAL);

    private final BoolSetting onlyOnUse = new BoolSetting("Only on use key",
        "Only places and hits whilst you hold the use key. With food or a bow or a shield in hand the"
            + " key uses that item and Pause on use still applies.", false);
    private final EnumSetting<Pause> pauseOnUse = pauseRow("Pause on use",
        "What to hold off whilst eating or drawing a bow.", Pause.BOTH);
    private final EnumSetting<Pause> pauseOnMine = pauseRow("Pause on mine",
        "What to hold off whilst you mine a block.", Pause.NONE);
    private final BoolSetting pauseOnLag = new BoolSetting("Pause on lag",
        "Holds off whilst the server has stopped ticking.", true);
    private final NumberSetting lagThreshold = new NumberSetting("Lag threshold",
        "How far past its usual update the server may go quiet before that counts as lag.", 500, 0, 5000, 250, " ms")
        .under(pauseOnLag);
    private final ChoiceListSetting pauseModules = new ChoiceListSetting("Pause modules",
        "Holds off whilst any of these modules is on.",
        () -> OfflineClient.INSTANCE.getModuleManager().togglableNames(this));
    private final NumberSetting pauseHealth = new NumberSetting("Pause health",
        "Holds off whilst your health plus absorption is at or below this. Zero never pauses.", 5, 0, 36, 0.5);

    private final EnumSetting<FaceMode> faceTarget = FaceMode.setting(FaceMode.SERVER);
    private final NumberSetting yawSteps = new NumberSetting("Yaw steps",
        "Most degrees the server rotation turns in one tick.", 180, 1, 180, 1, " degrees")
        .under(faceTarget, FaceMode.SERVER);
    private final EnumSetting<YawSteps> yawStepsMode = new EnumSetting<>("Yaw steps mode",
        "Which turns the yaw steps limit applies to.", YawSteps.BREAK)
        .describe(YawSteps.BREAK, "Only the turn to hit a crystal.")
        .describe(YawSteps.ALL, "Placing turns too.")
        .under(faceTarget, FaceMode.SERVER);
    private final EnumSetting<SwingMode> swing = SwingMode.settingWithUses(SwingMode.BOTH);

    private final EnumSetting<RenderMode> renderMode = new EnumSetting<>("Render",
        "How the placement box is drawn.", RenderMode.NORMAL)
        .describe(RenderMode.NORMAL, "A box on the base block.")
        .describe(RenderMode.SMOOTH, "The box slides between spots.")
        .describe(RenderMode.FADING, "The box fades out after a placement.")
        .describe(RenderMode.GRADIENT, "A box that fades towards its top.")
        .describe(RenderMode.NONE, "Nothing is drawn.");
    private final BoxStyle boxStyle = new BoxStyle(BoxStyle.Shape.BOTH, 280)
        .under(renderMode, RenderMode.NORMAL, RenderMode.SMOOTH, RenderMode.FADING, RenderMode.GRADIENT);
    private final BoolSetting renderPlace = new BoolSetting("Show placements",
        "Keeps the box on the spot just placed on.", true)
        .under(renderMode, RenderMode.NORMAL, RenderMode.SMOOTH, RenderMode.FADING, RenderMode.GRADIENT);
    private final NumberSetting placeTime = new NumberSetting("Place time",
        "Ticks the box stays after a placement.", 10, 0, 20, 1, " ticks")
        .under(renderPlace);
    private final BoolSetting renderBreak = new BoolSetting("Show breaks",
        "Also draws the base of the crystal just hit.", false)
        .under(renderMode, RenderMode.NORMAL, RenderMode.SMOOTH, RenderMode.FADING, RenderMode.GRADIENT);
    private final NumberSetting breakTime = new NumberSetting("Break time",
        "Ticks the box stays after a hit.", 13, 0, 20, 1, " ticks")
        .under(renderBreak);
    private final NumberSetting smoothness = new NumberSetting("Smoothness",
        "Ticks the box takes to slide to a new spot.", 10, 1, 20, 1, " ticks")
        .under(renderMode, RenderMode.SMOOTH);
    private final NumberSetting gradientHeight = new NumberSetting("Gradient height",
        "How tall the fading box is.", 0.7, 0.1, 1, 0.05, " blocks")
        .under(renderMode, RenderMode.GRADIENT);
    private final NumberSetting fadeTime = new NumberSetting("Fade time",
        "Ticks the box takes to fade away.", 10, 1, 20, 1, " ticks")
        .under(renderMode, RenderMode.FADING);
    private final BoolSetting showDamage = new BoolSetting("Show damage",
        "Writes the expected damage over the box.", true)
        .under(renderMode, RenderMode.NORMAL, RenderMode.SMOOTH, RenderMode.FADING, RenderMode.GRADIENT);
    private final ColorSetting damageColor = new ColorSetting("Damage colour",
        "Colour of the damage number.", 0, 0, 1, false)
        .under(showDamage);
    private final NumberSetting damageScale = new NumberSetting("Damage scale",
        "Size of the damage number.", 1.25, 1, 4, 0.25, "x")
        .under(showDamage);

    private final SlotSwap slots = new SlotSwap();
    private final HotbarLoan loan = new HotbarLoan();

    private int placeTimer;
    private int breakTimer;
    private int supportTimer;
    private int refillTimer;
    private int busyTimer;
    private int switchTimer;
    private long lastHitAt;
    private String status;

    private boolean finishing;

    private final List<LivingEntity> targets = new ArrayList<>();
    private final List<Entity> entities = new ArrayList<>();

    private final Cooldowns<BlockPos> ownSpots = new Cooldowns<>();

    private final Cooldowns<Integer> removed = new Cooldowns<>();

    private final Map<Integer, Integer> attempts = new HashMap<>();

    private BlockPos drawn;
    private float drawnDamage;
    private int drawnTicks;
    private Vec3 slideFrom;
    private Vec3 slideTo;
    private int slideTicks;
    private Vec3 damageAt;

    public CrystalAura() {
        super("CrystalAura", "Places end crystals near enemies and blows them up.", Category.COMBAT);
        addSettings(targetRange, players, mobs, ignoreNakeds,
            placeRange, placeWallsRange, breakRange, breakWallsRange,
            doPlace, placeDelay, multiPlace, oldPlacement, support, supportDelay,
            doBreak, breakDelay, attackFrequency, breakAttempts, ticksExisted, fastBreak,
            smartDelay, antiWeakness, onlyOwn,
            minDamage, minRatio);
        addSettings(limits.settings());
        addSettings(predict,
            finisher, finisherHealth, finisherArmour, finisherMissing, finisherKey,
            autoSwitch, switchDelay, noGapSwitch, noBowSwitch,
            onlyOnUse, pauseOnUse, pauseOnMine, pauseOnLag, lagThreshold, pauseModules, pauseHealth,
            faceTarget, yawSteps, yawStepsMode, swing,
            renderMode);
        addSettings(boxStyle.settings());
        addSettings(renderPlace, placeTime, renderBreak, breakTime, smoothness, gradientHeight,
            fadeTime, showDamage, damageColor, damageScale);
        searchTags("end crystal", "cpvp", "ca");
    }

    @Override
    public String getSuffix() {
        LivingEntity target = getFought();
        return target == null ? null : suffix(EntityUtil.displayNameOf(target), status);
    }

    @Override
    public LivingEntity getFought() {
        return targets.isEmpty() ? null : targets.getFirst();
    }

    public boolean hasTarget() {
        return getFought() != null;
    }

    // Other combat modules stand aside whilst this is true.
    public boolean isActing() {
        return busyTimer > 0;
    }

    @Override
    protected void onEnable() {
        placeTimer = 0;
        breakTimer = 0;
        supportTimer = 0;
        refillTimer = 0;
        busyTimer = 0;
        switchTimer = 0;
        lastHitAt = 0;
        slots.forget();
        clearMemory();
    }

    @Override
    protected void onDisable() {
        busyTimer = 0;
        slots.restore();
        loan.giveBack();
        clearMemory();
    }

    private void clearMemory() {
        targets.clear();
        entities.clear();
        ownSpots.clear();
        removed.clear();
        attempts.clear();
        drawn = null;
        drawnTicks = 0;
        slideFrom = null;
        slideTo = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        entities.clear();
        targets.clear();
        if (Hands.tied()) {
            return;
        }
        tickTimers();
        forgetOld();

        collectTargets();
        status = null;
        if (targets.isEmpty()) {
            restoreSlot();
            return;
        }
        String pause = pauseReason();
        if (pause != null) {
            restoreSlot();
            status = pause;
            return;
        }

        collectEntities();
        finishing = finisherKey.isHeld() || targets.stream().anyMatch(this::finishing);

        boolean hitSomething = false;
        if (doBreak.isOn() && !paused(Pause.BREAK) && breakTimer == 0) {
            hitSomething = breakBest();
        }
        if (doPlace.isOn() && !paused(Pause.PLACE) && placeTimer == 0
            && (multiPlace.isOn() || !worthHitting())) {
            placeBest();
        } else if (!hitSomething && !worthHitting()) {
            status = status == null ? "(waiting)" : status;
        }
        restoreSlot();
    }

    private void tickTimers() {
        placeTimer = Math.max(0, placeTimer - 1);
        breakTimer = Math.max(0, breakTimer - 1);
        supportTimer = Math.max(0, supportTimer - 1);
        refillTimer = Math.max(0, refillTimer - 1);
        busyTimer = Math.max(0, busyTimer - 1);
        switchTimer = Math.max(0, switchTimer - 1);
        if (drawnTicks > 0) {
            drawnTicks--;
        }
        if (slideTicks > 0) {
            slideTicks--;
        }
    }

    private void forgetOld() {
        ownSpots.tick();
        removed.tick();
        attempts.keySet().removeIf(id -> mc.level.getEntity(id) == null);
    }

    // The silent switch goes back at the end of the tick. Normal stays on the crystals
    // unless AutoCity needs its tool in hand.
    private void restoreSlot() {
        if (!autoSwitch.is(Switch.NORMAL) || targets.isEmpty() || Modules.cityKeepsTool()) {
            slots.restore();
        }
    }

    private String pauseReason() {
        if (onlyOnUse.isOn() && !InputUtil.physicallyHeld(mc.options.keyUse)) {
            return "(use key up)";
        }
        if (pauseOnLag.isOn() && TickRate.INSTANCE.lagging(lagThreshold.getInt())) {
            return "(lag)";
        }
        if (pauseHealth.getValue() > 0 && EntityUtil.totalHealth(mc.player) <= pauseHealth.getFloat()) {
            return "(low health)";
        }
        if (Modules.pistonFiring()) {
            return "(PistonAura)";
        }
        for (String name : pauseModules.getValue()) {
            Module module = OfflineClient.INSTANCE.getModuleManager().get(name);
            if (module != null && module.isEnabled()) {
                return "(" + module.getName() + ")";
            }
        }
        if (paused(Pause.BOTH)) {
            return "(paused)";
        }
        return null;
    }

    private static EnumSetting<Pause> pauseRow(String name, String description, Pause defaultValue) {
        return new EnumSetting<>(name, description, defaultValue)
            .describe(Pause.BOTH, "Neither places nor hits.")
            .describe(Pause.PLACE, "Stops placing but still hits.")
            .describe(Pause.BREAK, "Stops hitting but still places.")
            .describe(Pause.NONE, "Carries on regardless.");
    }

    private boolean paused(Pause half) {
        return halts(pauseOnUse.getValue(), half, mc.player.isUsingItem())
            || halts(pauseOnMine.getValue(), half, mc.gameMode.isDestroying());
    }

    private static boolean halts(Pause chosen, Pause half, boolean busy) {
        if (!busy || chosen == Pause.NONE) {
            return false;
        }
        return chosen == Pause.BOTH ? half == Pause.BOTH || half != Pause.NONE
            : chosen == half;
    }

    @Override
    public boolean wouldFight(Entity entity) {
        if (!(entity instanceof LivingEntity living) || entity == mc.player || !living.isAlive()) {
            return false;
        }
        return entity instanceof Player player
            ? players.isOn() && EntityUtil.isEnemy(player) && !(ignoreNakeds.isOn() && naked(player))
            : mobs.contains(entity.getType());
    }

    private void collectTargets() {
        double reach = targetRange.getValue();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living && wouldFight(living)
                && mc.player.distanceTo(entity) <= reach) {
                targets.add(living);
            }
        }
        targets.sort((a, b) -> Double.compare(mc.player.distanceToSqr(a), mc.player.distanceToSqr(b)));
    }

    private static boolean naked(Player player) {
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) {
            return false;
        }
        for (EquipmentSlot slot : ItemUtil.ARMOR_SLOTS) {
            if (!player.getItemBySlot(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void collectEntities() {
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.isRemoved() || entity.isSpectator()) {
                continue;
            }
            if (entity instanceof EndCrystal && removed.contains(entity.getId())) {
                continue;
            }
            entities.add(entity);
        }
    }

    private boolean worthHitting() {
        return doBreak.isOn() && bestCrystal() != null;
    }

    private boolean breakBest() {
        if (!attackAllowed()) {
            return false;
        }
        EndCrystal best = bestCrystal();
        if (best == null) {
            return false;
        }
        return hit(best);
    }

    private boolean attackAllowed() {
        if (switchTimer > 0) {
            return false;
        }
        long gap = 1000 / attackFrequency.getInt();
        return System.currentTimeMillis() - lastHitAt >= gap;
    }

    private EndCrystal bestCrystal() {
        EndCrystal best = null;
        float bestDamage = 0;
        for (Entity entity : entities) {
            if (!(entity instanceof EndCrystal crystal) || !hittable(crystal)) {
                continue;
            }
            float damage = scoreOf(crystal.position());
            if (damage > bestDamage) {
                bestDamage = damage;
                best = crystal;
            }
        }
        return best;
    }

    private boolean hittable(EndCrystal crystal) {
        if (crystal.tickCount < ticksExisted.getInt()) {
            return false;
        }
        if (attempts.getOrDefault(crystal.getId(), 0) >= breakAttempts.getInt()) {
            return false;
        }
        if (onlyOwn.isOn() && !ownSpots.contains(crystal.blockPosition())) {
            return false;
        }
        if (Modules.cityHolds(crystal.blockPosition())) {
            return false;
        }
        Vec3 centre = crystal.getBoundingBox().getCenter();
        double reach = BlockUtil.reachFor(centre, breakRange.getValue(), breakWallsRange.getValue());
        if (EntityUtil.reachDistance(mc.player, crystal) > reach) {
            return false;
        }
        if (smartDelay.isOn() && immune()) {
            return false;
        }
        return blastAllowed(crystal.position());
    }

    // Damage during immunity drops to the difference and the crystal is wasted.
    private boolean immune() {
        for (LivingEntity target : targets) {
            if (target.hurtTime == 0) {
                return false;
            }
        }
        return true;
    }

    private boolean hit(EndCrystal crystal) {
        if (!face(crystal.getBoundingBox().getCenter(), yawSteps.getFloat())) {
            status = "(turning)";
            return false;
        }
        if (antiWeakness.isOn() && !armWeakHand()) {
            status = "(weakness)";
            return false;
        }
        AttackTimer.packetHit(crystal);
        swing.getValue().hit();
        lastHitAt = System.currentTimeMillis();
        breakTimer = breakDelay.getInt();
        busyTimer = BUSY_TICKS;
        removed.put(crystal.getId(), REMOVED_MEMORY);
        attempts.merge(crystal.getId(), 1, Integer::sum);
        if (renderBreak.isOn()) {
            show(crystal.blockPosition().below(), scoreOf(crystal.position()), breakTime.getInt());
        }
        return true;
    }

    @Subscribe
    private void onEntityAdded(EntityAddedEvent event) {
        if (!fastBreak.isOn() || !doBreak.isOn() || breakTimer > 0 || !inGame()
            || !(event.getEntity() instanceof EndCrystal crystal) || targets.isEmpty()) {
            return;
        }
        if (paused(Pause.BREAK) || pauseReason() != null || !attackAllowed()) {
            return;
        }
        if (ticksExisted.getInt() > 0 || !hittable(crystal) || scoreOf(crystal.position()) <= 0) {
            return;
        }
        hit(crystal);
        restoreSlot();
    }

    private void placeBest() {
        if (supportTimer > 0) {
            return;
        }
        BlockPos base = bestBase();
        if (base == null) {
            if (support.is(Support.OFF)) {
                status = "(no safe spot)";
            } else {
                placeSupport();
            }
            return;
        }
        InteractionHand hand = crystalHand();
        if (hand == null) {
            return;
        }
        BlockHitResult click = Crystals.crystalClick(base);
        if (!face(click.getLocation(), placeStep())) {
            status = "(turning)";
            return;
        }
        if (mc.gameMode.useItemOn(mc.player, hand, click).consumesAction()) {
            swing.getValue().use(hand);
            ownSpots.put(base.above().immutable(), OWN_MEMORY);
            placeTimer = placeDelay.getInt();
            busyTimer = BUSY_TICKS;
            if (renderPlace.isOn()) {
                show(base, scoreOf(Vec3.atBottomCenterOf(base.above())), placeTime.getInt());
            }
        }
    }

    private BlockPos bestBase() {
        BlockPos best = null;
        float bestDamage = 0;
        Vec3 eye = mc.player.getEyePosition();
        double furthest = Math.max(placeRange.getValue(), placeWallsRange.getValue());
        int r = (int) Math.ceil(furthest);
        BlockPos middle = mc.player.blockPosition();
        for (BlockPos base : BlockPos.betweenClosed(middle.offset(-r, -r, -r), middle.offset(r, r, r))) {
            BlockPos above = base.above();
            Vec3 crystalPos = Vec3.atBottomCenterOf(above);
            if (eye.distanceTo(crystalPos) > furthest || !BlockUtil.state(above).isAir()) {
                continue;
            }
            if (!Crystals.crystalBase(BlockUtil.state(base))) {
                continue;
            }
            if (oldPlacement.isOn() && !BlockUtil.state(above.above()).isAir()) {
                continue;
            }
            double reach = BlockUtil.reachFor(crystalPos, placeRange.getValue(), placeWallsRange.getValue());
            if (eye.distanceTo(crystalPos) > reach) {
                continue;
            }
            // The crystal model needs its whole space free of entities.
            if (entityBlocks(Crystals.crystalSpace(above)) || !blastAllowed(crystalPos)) {
                continue;
            }
            float damage = scoreOf(crystalPos);
            if (damage > bestDamage) {
                bestDamage = damage;
                best = base.immutable();
            }
        }
        if (best != null && renderMode.getValue() != RenderMode.NONE) {
            preview(best, bestDamage);
        }
        return best;
    }

    // Zero when no target on its own clears the minimum.
    private float scoreOf(Vec3 crystalPos) {
        return scoreOf(crystalPos, targets);
    }

    private float scoreOf(Vec3 crystalPos, List<LivingEntity> against) {
        float total = 0;
        float hardest = 0;
        boolean enough = false;
        for (LivingEntity target : against) {
            float damage = ExplosionUtil.crystalDamage(target, crystalPos, lead(target));
            if (damage <= 0) {
                continue;
            }
            enough |= finishing || damage >= minDamage.getFloat();
            total += damage;
            hardest = Math.max(hardest, damage);
        }
        return enough && worthTheTrade(crystalPos, hardest) ? total : 0;
    }

    private boolean worthTheTrade(Vec3 crystalPos, float dealt) {
        float ratio = minRatio.getFloat();
        return ratio == 0 || dealt >= ratio * ExplosionUtil.crystalDamage(mc.player, crystalPos);
    }

    private Vec3 lead(LivingEntity target) {
        return predict.isOn() ? EntityUtil.velocityOf(target).scale(LEAD_TICKS) : Vec3.ZERO;
    }

    private boolean finishing(LivingEntity target) {
        if (!finisher.isOn()) {
            return false;
        }
        if (EntityUtil.totalHealth(target) <= finisherHealth.getFloat()) {
            return true;
        }
        if (!(target instanceof Player)) {
            return false;
        }
        for (EquipmentSlot slot : ItemUtil.ARMOR_SLOTS) {
            ItemStack piece = target.getItemBySlot(slot);
            if (piece.isEmpty()) {
                if (finisherMissing.isOn()) {
                    return true;
                }
                continue;
            }
            // Multiplying before dividing keeps a piece right at a whole percent counted.
            if (piece.isDamageableItem()
                && ItemUtil.usesLeft(piece) <= piece.getMaxDamage() * finisherArmour.getFloat() / 100f) {
                return true;
            }
        }
        return false;
    }

    // The server refuses an attack that weakness takes to nought and the crystal survives.
    private boolean armWeakHand() {
        if (DamageUtil.hitDamage(mc.player, mc.player.getMainHandItem()) > 0) {
            return true;
        }
        int slot = InventoryUtil.hotbarSlot(stack -> DamageUtil.hitDamage(mc.player, stack) > 0);
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        return true;
    }

    private boolean blastAllowed(Vec3 source) {
        return limits.allows(source, ExplosionUtil.CRYSTAL_POWER);
    }

    private float placeStep() {
        return yawStepsMode.is(YawSteps.ALL) ? yawSteps.getFloat() : RotationManager.NO_STEP;
    }

    // Yaw steps only slow the turn the server sees. The other ways of facing turn at once.
    private boolean face(Vec3 point, float step) {
        FaceMode mode = faceTarget.getValue();
        return mode == FaceMode.SERVER
            ? RotationManager.look(point, RotationPriority.AURA, RotationManager.BLOCK_TOLERANCE, step)
            : mode.face(point, RotationPriority.AURA);
    }

    private boolean entityBlocks(AABB space) {
        for (Entity entity : entities) {
            if (entity.getBoundingBox().intersects(space)) {
                return true;
            }
        }
        return false;
    }

    private InteractionHand crystalHand() {
        if (mc.player.getOffhandItem().is(Items.END_CRYSTAL)) {
            return InteractionHand.OFF_HAND;
        }
        if (mc.player.getMainHandItem().is(Items.END_CRYSTAL)) {
            return InteractionHand.MAIN_HAND;
        }
        if (autoSwitch.is(Switch.NONE)) {
            status = "(hold crystals)";
            return null;
        }
        if (autoSwitch.is(Switch.NORMAL) && holdingSomethingPrecious()) {
            status = "(keeping hand)";
            return null;
        }
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.END_CRYSTAL));
        if (slot != -1) {
            slots.select(slot);
            return InteractionHand.MAIN_HAND;
        }
        refillCrystals();
        status = "(no crystals)";
        return null;
    }

    private boolean holdingSomethingPrecious() {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = mc.player.getItemInHand(hand);
            if (noGapSwitch.isOn() && ItemUtil.isGoldenApple(stack)) {
                return true;
            }
            if (noBowSwitch.isOn() && stack.getItem() instanceof BowItem) {
                return true;
            }
        }
        return false;
    }

    private void refillCrystals() {
        if (refillTimer > 0 || Modules.packing() || mc.player.containerMenu != mc.player.inventoryMenu) {
            return;
        }
        int free = InventoryUtil.freeHotbarSlot();
        if (free == -1) {
            return;
        }
        for (int i = InventoryUtil.HOTBAR_SIZE; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (!mc.player.getInventory().getItem(i).is(Items.END_CRYSTAL)) {
                continue;
            }
            refillTimer = REFILL_DELAY;
            InventoryUtil.swap(InventoryUtil.networkSlot(i), InventoryUtil.networkSlot(free));
            return;
        }
    }

    private void placeSupport() {
        // ShulkerPacker keeps count of the backpack and nothing is borrowed from it meanwhile.
        int slot = InventoryUtil.findSlot(Items.OBSIDIAN,
            Modules.packing() ? InventoryUtil.HOTBAR_SIZE : InventoryUtil.WHOLE_INVENTORY);
        if (slot == -1) {
            status = "(no obsidian)";
            return;
        }
        List<LivingEntity> against = support.is(Support.FAST) ? targets.subList(0, 1) : targets;
        BlockPos best = null;
        float bestDamage = 0;
        Vec3 eye = mc.player.getEyePosition();
        for (LivingEntity target : against) {
            BlockPos feet = target.blockPosition();
            BlockPos low = feet.offset(-SUPPORT_REACH, -SUPPORT_RISE, -SUPPORT_REACH);
            BlockPos high = feet.offset(SUPPORT_REACH, SUPPORT_RISE, SUPPORT_REACH);
            for (BlockPos pos : BlockPos.betweenClosed(low, high)) {
                if (!BlockUtil.isReplaceable(pos) || !BlockUtil.state(pos.above()).isAir()) {
                    continue;
                }
                if (BlockUtil.intersectsPlayer(pos) || entityBlocks(new AABB(pos))) {
                    continue;
                }
                Vec3 crystalPos = Vec3.atBottomCenterOf(pos.above());
                if (eye.distanceTo(crystalPos) > placeRange.getValue() || !blastAllowed(crystalPos)) {
                    continue;
                }
                float damage = scoreOf(crystalPos, against);
                if (damage > bestDamage) {
                    bestDamage = damage;
                    best = pos.immutable();
                }
            }
        }
        if (best == null) {
            status = "(no safe spot)";
            return;
        }
        if (!face(Vec3.atCenterOf(best), placeStep())) {
            status = "(turning)";
            return;
        }
        if (slot < InventoryUtil.HOTBAR_SIZE) {
            slots.select(slot);
        } else if (!loan.select(slot)) {
            status = "(no room for obsidian)";
            return;
        }
        boolean placed = BlockUtil.placeAny(best, false, false);
        if (placed) {
            swing.getValue().use();
        }
        // The block goes down before the borrowed stack heads home.
        loan.giveBack();
        if (placed) {
            status = "(placing support)";
            supportTimer = supportDelay.getInt();
            busyTimer = BUSY_TICKS;
            if (supportTimer == 0) {
                placeTimer = 0;
                placeBest();
            }
        }
    }

    private void preview(BlockPos base, float damage) {
        if (drawnTicks == 0 || !base.equals(drawn)) {
            show(base, damage, 1);
        }
    }

    private void show(BlockPos base, float damage, int ticks) {
        if (renderMode.is(RenderMode.SMOOTH) && drawn != null && !base.equals(drawn)) {
            slideFrom = slideTo == null ? Vec3.atLowerCornerOf(drawn) : currentSlide();
            slideTo = Vec3.atLowerCornerOf(base);
            slideTicks = smoothness.getInt();
        }
        drawn = base;
        drawnDamage = damage;
        drawnTicks = Math.max(ticks, 1);
    }

    private Vec3 currentSlide() {
        if (slideFrom == null || slideTo == null || slideTicks == 0) {
            return slideTo == null ? Vec3.atLowerCornerOf(drawn) : slideTo;
        }
        double t = 1 - (double) slideTicks / smoothness.getInt();
        return slideFrom.lerp(slideTo, t);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (renderMode.is(RenderMode.NONE) || drawn == null || drawnTicks == 0) {
            return;
        }
        DrawBatch batch = event.getBatch();
        AABB box = DrawBatch.blockBox(drawn);
        float alpha = 1;
        switch (renderMode.getValue()) {
            case SMOOTH -> {
                Vec3 corner = currentSlide();
                box = box.move(corner.subtract(Vec3.atLowerCornerOf(drawn)));
            }
            case FADING -> alpha = Math.min(1f, (float) drawnTicks / fadeTime.getInt());
            case GRADIENT -> {
                AABB tall = new AABB(box.minX, box.minY, box.minZ, box.maxX,
                    box.minY + gradientHeight.getValue(), box.maxZ);
                if (boxStyle.drawsSides()) {
                    int fill = boxStyle.fadedFill();
                    batch.gradientBox(tall, fill, ColorUtil.fade(fill, 0f), false);
                }
                if (boxStyle.drawsLines()) {
                    batch.flatRect(box.minX, box.minZ, box.maxX, box.maxZ, box.minY,
                        boxStyle.lineColor(), false);
                }
                drawDamage(box);
                return;
            }
            default -> {
            }
        }
        boxStyle.drawFading(batch, box, alpha, false);
        drawDamage(box);
    }

    private void drawDamage(AABB box) {
        damageAt = showDamage.isOn() && drawnDamage > 0
            ? new Vec3((box.minX + box.maxX) / 2, box.maxY + 0.5, (box.minZ + box.maxZ) / 2) : null;
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (damageAt == null || drawnTicks == 0 || !WorldToScreen.update()) {
            return;
        }
        Vec3 screen = WorldToScreen.project(damageAt);
        if (screen == null) {
            return;
        }
        String text = String.format(Locale.ROOT, "%.1f", drawnDamage);
        RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, damageScale.getFloat(),
            List.of(text), List.of(damageColor.getColor()));
    }

    // Sent hotbar swaps restart the switch delay. The hit waits for the server.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (event.getPacket() instanceof ServerboundSetCarriedItemPacket) {
            switchTimer = switchDelay.getInt();
        }
    }
}
