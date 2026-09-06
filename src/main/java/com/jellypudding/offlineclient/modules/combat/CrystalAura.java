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
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TickRate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Every candidate spot is scored with the game's own explosion maths against every target.
public final class CrystalAura extends Module {

    public enum Switch { NORMAL, SILENT, NONE }

    public enum Support { OFF, ACCURATE, FAST }

    public enum Pause { BOTH, PLACE, BREAK, NONE }

    public enum YawSteps { BREAK, ALL }

    public enum RenderMode { NORMAL, SMOOTH, FADING, GRADIENT, NONE }

    // How far ahead of a moving target the damage is scored.
    private static final double LEAD_TICKS = 1;

    // Ticks a placed spot stays on the own crystal list.
    private static final int OWN_MEMORY = 100;

    // Ticks between attempts to pull crystals up from the backpack.
    private static final int REFILL_DELAY = 4;

    // Ticks a hit crystal is treated as already gone before a retry.
    private static final int REMOVED_MEMORY = 3;

    // Ticks that other combat modules stand aside for after a real action.
    private static final int BUSY_TICKS = 8;

    // How far around a target a support block may be laid.
    private static final int SUPPORT_REACH = 3;
    private static final int SUPPORT_RISE = 2;

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away targets are considered.", 10, 2, 16, 0.5, " blocks");
    private final BoolSetting players = new BoolSetting("Players",
        "Attack other players.", true);
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
        "Place crystals near targets.", true);
    private final NumberSetting placeDelay = new NumberSetting("Place delay",
        "Ticks to wait between placements.", 2, 0, 10, 1, " ticks")
        .under(doPlace);
    private final BoolSetting multiPlace = new BoolSetting("Multi place",
        "Place another crystal whilst one worth hitting is still up.", false)
        .under(doPlace);
    private final BoolSetting oldPlacement = new BoolSetting("Old placement",
        "Require two air blocks above the base like older servers.", false)
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
        "Hit crystals to set them off.", true);
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
        "Skip a hit whilst the target is still in damage immunity.", true)
        .under(doBreak);
    private final BoolSetting antiWeakness = new BoolSetting("Anti weakness",
        "Swap to a tool that still breaks crystals whilst you have weakness.", true)
        .under(doBreak);
    private final BoolSetting onlyOwn = new BoolSetting("Only own",
        "Only hit crystals you placed yourself.", false)
        .under(doBreak);

    private final NumberSetting minDamage = new NumberSetting("Min damage",
        "Only act when a target would take at least this much.", 6, 0, 20, 0.5);
    private final NumberSetting maxSelfDamage = new NumberSetting("Max self damage",
        "Never take more than this from your own crystal.", 8, 0, 20, 0.5);
    private final BoolSetting antiSuicide = new BoolSetting("Anti suicide",
        "Never touch a crystal that could kill you.", true);
    private final BoolSetting predict = new BoolSetting("Predict",
        "Score the damage where a moving target is heading.", true);

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

    private final EnumSetting<Pause> pauseOnUse = new EnumSetting<>("Pause on use",
        "What to hold off whilst eating or drawing a bow.", Pause.BOTH)
        .describe(Pause.BOTH, "Neither places nor hits.")
        .describe(Pause.PLACE, "Stops placing but still hits.")
        .describe(Pause.BREAK, "Stops hitting but still places.")
        .describe(Pause.NONE, "Carries on regardless.");
    private final EnumSetting<Pause> pauseOnMine = new EnumSetting<>("Pause on mine",
        "What to hold off whilst you mine a block.", Pause.NONE)
        .describe(Pause.BOTH, "Neither places nor hits.")
        .describe(Pause.PLACE, "Stops placing but still hits.")
        .describe(Pause.BREAK, "Stops hitting but still places.")
        .describe(Pause.NONE, "Carries on regardless.");
    private final BoolSetting pauseOnLag = new BoolSetting("Pause on lag",
        "Holds off whilst the server has stopped ticking.", true);
    private final NumberSetting lagLimit = new NumberSetting("Lag limit",
        "How long the server may go quiet before that counts as lag.", 1000, 250, 5000, 250, " ms")
        .under(pauseOnLag);
    private final ChoiceListSetting pauseModules = new ChoiceListSetting("Pause modules",
        "Holds off whilst any of these modules is on.", CrystalAura::moduleNames);
    private final NumberSetting pauseHealth = new NumberSetting("Pause health",
        "Holds off whilst your health plus absorption is at or below this. Zero never pauses.", 5, 0, 36, 0.5);

    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards the crystal spot.", true);
    private final NumberSetting yawSteps = new NumberSetting("Yaw steps",
        "Most degrees the server rotation turns in one tick.", 180, 1, 180, 1, " degrees")
        .under(rotate);
    private final EnumSetting<YawSteps> yawStepsMode = new EnumSetting<>("Yaw steps mode",
        "Which turns the yaw steps limit applies to.", YawSteps.BREAK)
        .describe(YawSteps.BREAK, "Only the turn to hit a crystal.")
        .describe(YawSteps.ALL, "Placing turns too.")
        .under(rotate);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);

    private final EnumSetting<RenderMode> renderMode = new EnumSetting<>("Render",
        "How the placement box is drawn.", RenderMode.NORMAL)
        .describe(RenderMode.NORMAL, "A box on the base block.")
        .describe(RenderMode.SMOOTH, "The box slides between spots.")
        .describe(RenderMode.FADING, "The box fades out after a placement.")
        .describe(RenderMode.GRADIENT, "A box that fades towards its top.")
        .describe(RenderMode.NONE, "Nothing is drawn.");
    private final BoxStyle boxStyle = new BoxStyle(BoxStyle.Shape.BOTH, 280);
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
    private String targetName;
    private String status;

    // True for the tick when some target is weak enough to ignore Min damage.
    private boolean finishing;

    // The targets and every other entity worth looking at this tick.
    private final List<LivingEntity> targets = new ArrayList<>();
    private final List<Entity> entities = new ArrayList<>();

    // Spots placed on with the ticks left before they are forgotten.
    private final Map<BlockPos, Integer> ownSpots = new HashMap<>();

    // Crystals just hit with the ticks left before they count as still there.
    private final Map<Integer, Integer> removed = new HashMap<>();

    // Hits landed on each crystal that refused to go.
    private final Map<Integer, Integer> attempts = new HashMap<>();

    // What the box is drawn on and the damage it promised.
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
            minDamage, maxSelfDamage, antiSuicide, predict,
            finisher, finisherHealth, finisherArmour, finisherMissing, finisherKey,
            autoSwitch, switchDelay, noGapSwitch, noBowSwitch,
            pauseOnUse, pauseOnMine, pauseOnLag, lagLimit, pauseModules, pauseHealth,
            rotate, yawSteps, yawStepsMode, swing,
            renderMode);
        addSettings(boxStyle.settings());
        addSettings(renderPlace, placeTime, renderBreak, breakTime, smoothness, gradientHeight,
            fadeTime, showDamage, damageColor, damageScale);
        searchTags("end crystal", "cpvp", "ca");
    }

    private static List<String> moduleNames() {
        List<String> names = new ArrayList<>();
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            if (module.isTogglable()) {
                names.add(module.getName());
            }
        }
        return names;
    }

    @Override
    public String getSuffix() {
        return targetName == null ? null : suffix(targetName, status);
    }

    public boolean hasTarget() {
        return targetName != null;
    }

    // True only for a short window after a real place or break.
    // Other combat modules stand aside during that window.
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
        targetName = null;
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
        if (!inGame() || mc.player.isSpectator()) {
            targetName = null;
            return;
        }
        tickTimers();
        forgetOld();

        collectTargets();
        targetName = targets.isEmpty() ? null : nameOf(targets.getFirst());
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
        ownSpots.entrySet().removeIf(entry -> {
            entry.setValue(entry.getValue() - 1);
            return entry.getValue() <= 0;
        });
        removed.entrySet().removeIf(entry -> {
            entry.setValue(entry.getValue() - 1);
            return entry.getValue() <= 0;
        });
        attempts.keySet().removeIf(id -> mc.level.getEntity(id) == null);
    }

    // The silent switch goes back at the end of the tick. Normal stays on the crystals.
    private void restoreSlot() {
        if (!autoSwitch.is(Switch.NORMAL) || targets.isEmpty()) {
            slots.restore();
        }
    }

    private String pauseReason() {
        if (pauseOnLag.isOn() && TickRate.INSTANCE.lagging(lagLimit.getInt())) {
            return "(lag)";
        }
        if (pauseHealth.getValue() > 0 && EntityUtil.totalHealth(mc.player) <= pauseHealth.getFloat()) {
            return "(low health)";
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

    // True when the given half is held off by a use or a mine.
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

    private void collectTargets() {
        double reach = targetRange.getValue();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || entity == mc.player
                || !living.isAlive() || mc.player.distanceTo(entity) > reach) {
                continue;
            }
            if (entity instanceof Player player) {
                if (!players.isOn() || !EntityUtil.isEnemy(player)
                    || (ignoreNakeds.isOn() && naked(player))) {
                    continue;
                }
            } else if (!mobs.contains(entity.getType())) {
                continue;
            }
            targets.add(living);
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

    private static String nameOf(LivingEntity target) {
        return target instanceof Player player ? EntityUtil.nameOf(player) : target.getName().getString();
    }

    private void collectEntities() {
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.isRemoved() || entity.isSpectator()) {
                continue;
            }
            if (entity instanceof EndCrystal && removed.containsKey(entity.getId())) {
                continue;
            }
            entities.add(entity);
        }
    }

    // True whilst a crystal that passes the damage checks is still standing.
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
        if (onlyOwn.isOn() && !ownSpots.containsKey(crystal.blockPosition())) {
            return false;
        }
        Vec3 centre = crystal.getBoundingBox().getCenter();
        double reach = BlockUtil.canSee(centre) ? breakRange.getValue() : breakWallsRange.getValue();
        if (EntityUtil.reachDistance(mc.player, crystal) > reach) {
            return false;
        }
        if (smartDelay.isOn() && immune()) {
            return false;
        }
        return selfSafe(crystal.position());
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
        if (rotate.isOn() && !RotationManager.look(crystal.getBoundingBox().getCenter(),
            RotationPriority.AURA, RotationManager.BLOCK_TOLERANCE, yawSteps.getFloat())) {
            status = "(turning)";
            return false;
        }
        if (antiWeakness.isOn() && !armWeakHand()) {
            status = "(weakness)";
            return false;
        }
        mc.player.connection.send(new ServerboundAttackPacket(crystal.getId()));
        swing.getValue().swing();
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

    // A crystal that spawns is hit before its first tick when the reach and damage allow.
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
        Vec3 hit = Vec3.atCenterOf(base).add(0, 0.5, 0);
        float step = yawStepsMode.is(YawSteps.ALL) ? yawSteps.getFloat() : RotationManager.NO_STEP;
        if (rotate.isOn() && !RotationManager.look(hit, RotationPriority.AURA,
            RotationManager.BLOCK_TOLERANCE, step)) {
            status = "(turning)";
            return;
        }
        BlockHitResult result = new BlockHitResult(hit, Direction.UP, base, false);
        if (mc.gameMode.useItemOn(mc.player, hand, result).consumesAction()) {
            swing.getValue().swing(hand);
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
            Block block = BlockUtil.state(base).getBlock();
            if (block != Blocks.OBSIDIAN && block != Blocks.BEDROCK) {
                continue;
            }
            if (oldPlacement.isOn() && !BlockUtil.state(above.above()).isAir()) {
                continue;
            }
            double reach = BlockUtil.canSee(crystalPos) ? placeRange.getValue() : placeWallsRange.getValue();
            if (eye.distanceTo(crystalPos) > reach) {
                continue;
            }
            // The crystal model needs its whole space free of entities.
            if (entityBlocks(crystalSpace(above)) || !selfSafe(crystalPos)) {
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

    private static AABB crystalSpace(BlockPos above) {
        return new AABB(above.getX(), above.getY(), above.getZ(),
            above.getX() + 1, above.getY() + 2, above.getZ() + 1);
    }

    // The damage a crystal here deals to every target added up. Zero when no
    // target on its own clears the minimum.
    private float scoreOf(Vec3 crystalPos) {
        return scoreOf(crystalPos, targets);
    }

    private float scoreOf(Vec3 crystalPos, List<LivingEntity> against) {
        float total = 0;
        boolean enough = false;
        for (LivingEntity target : against) {
            float damage = ExplosionUtil.crystalDamage(target, crystalPos, lead(target));
            if (damage <= 0) {
                continue;
            }
            enough |= finishing || damage >= minDamage.getFloat();
            total += damage;
        }
        return enough ? total : 0;
    }

    // How far the target travels before the blast lands.
    private Vec3 lead(LivingEntity target) {
        return predict.isOn() ? EntityUtil.velocityOf(target).scale(LEAD_TICKS) : Vec3.ZERO;
    }

    // True when the target is nearly dead or their armour is falling apart.
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
            if (!piece.isDamageableItem()) {
                continue;
            }
            int left = piece.getMaxDamage() - piece.getDamageValue();
            if (left <= piece.getMaxDamage() * finisherArmour.getFloat() / 100f) {
                return true;
            }
        }
        return false;
    }

    // Puts something with attack damage in hand when weakness has zeroed it out.
    // The server refuses the whole attack at zero and the crystal survives.
    private boolean armWeakHand() {
        if (mc.player.getAttributeValue(Attributes.ATTACK_DAMAGE) > 0) {
            return true;
        }
        ItemStack held = mc.player.getInventory().getSelectedItem();
        double heldBonus = ItemUtil.attributeValue(held, Attributes.ATTACK_DAMAGE,
            EquipmentSlot.MAINHAND);
        double base = mc.player.getAttributeValue(Attributes.ATTACK_DAMAGE) - heldBonus;

        int bestSlot = -1;
        double bestBonus = 0;
        for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            double bonus = ItemUtil.attributeValue(stack, Attributes.ATTACK_DAMAGE,
                EquipmentSlot.MAINHAND);
            if (bonus > bestBonus) {
                bestBonus = bonus;
                bestSlot = i;
            }
        }
        if (bestSlot == -1 || base + bestBonus <= 0) {
            return false;
        }
        select(bestSlot);
        return true;
    }

    private boolean selfSafe(Vec3 source) {
        return ExplosionUtil.selfSafe(source, ExplosionUtil.CRYSTAL_POWER,
            maxSelfDamage.getFloat(), antiSuicide.isOn());
    }

    private boolean entityBlocks(AABB space) {
        for (Entity entity : entities) {
            if (entity.getBoundingBox().intersects(space)) {
                return true;
            }
        }
        return false;
    }

    // The hand holding a crystal. Switches the hotbar over when the mode allows.
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
            select(slot);
            return InteractionHand.MAIN_HAND;
        }
        refillCrystals();
        status = "(no crystals)";
        return null;
    }

    private boolean holdingSomethingPrecious() {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = mc.player.getItemInHand(hand);
            if (noGapSwitch.isOn() && (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE))) {
                return true;
            }
            if (noBowSwitch.isOn() && stack.getItem() instanceof BowItem) {
                return true;
            }
        }
        return false;
    }

    // A hotbar swap the server has to hear about before a hit is believed.
    private void select(int slot) {
        if (mc.player.getInventory().getSelectedSlot() != slot) {
            switchTimer = switchDelay.getInt();
        }
        slots.select(slot);
    }

    private void refillCrystals() {
        if (refillTimer > 0 || mc.player.containerMenu != mc.player.inventoryMenu) {
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

    // Places an obsidian block on the best open spot for the next crystal.
    private void placeSupport() {
        int slot = InventoryUtil.findSlot(Items.OBSIDIAN, InventoryUtil.WHOLE_INVENTORY);
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
                if (eye.distanceTo(crystalPos) > placeRange.getValue() || !selfSafe(crystalPos)) {
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
        if (rotate.isOn() && !RotationManager.look(Vec3.atCenterOf(best), RotationPriority.AURA,
            RotationManager.BLOCK_TOLERANCE)) {
            status = "(turning)";
            return;
        }
        if (slot < InventoryUtil.HOTBAR_SIZE) {
            select(slot);
        } else if (!loan.select(slot)) {
            status = "(no room for obsidian)";
            return;
        }
        boolean placed = BlockUtil.placeAny(best, false, true);
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

    // The spot the next crystal is heading for. Drawn until a placement replaces it.
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
                batch.gradientBox(tall, ColorUtil.fade(boxStyle.lineColor(), 0.4f),
                    ColorUtil.fade(boxStyle.lineColor(), 0f), false);
                batch.flatRect(box.minX, box.minZ, box.maxX, box.maxZ, box.minY,
                    boxStyle.lineColor(), false);
                drawDamage(box);
                return;
            }
            default -> {
            }
        }
        boxStyle.draw(batch, box, ColorUtil.fade(boxStyle.lineColor(), alpha), false);
        drawDamage(box);
    }

    // The number is drawn on the screen over where the box top sits in the world.
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

    // Sent hotbar swaps restart the switch delay so the hit waits for the server.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (event.getPacket() instanceof ServerboundSetCarriedItemPacket) {
            switchTimer = switchDelay.getInt();
        }
    }
}
