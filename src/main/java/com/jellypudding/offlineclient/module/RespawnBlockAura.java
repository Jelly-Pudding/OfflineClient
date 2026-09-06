package com.jellypudding.offlineclient.module;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.modules.combat.CrystalAura;
import com.jellypudding.offlineclient.modules.movement.Sneak;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

// The shape shared by the auras that place a respawn block beside an enemy and set it off.
// A subclass supplies the block and how it is placed and fired.
public abstract class RespawnBlockAura extends Module {

    // Ticks between attempts to pull ammunition up from the backpack.
    private static final int MOVE_DELAY = 4;

    // Where a module is allowed to take its blocks from.
    public enum TakeFrom { HOTBAR, INVENTORY }

    protected final NumberSetting targetRange;
    protected final EnumSetting<TargetPriority> priority;
    protected final EntityFilter filter;
    protected final NumberSetting placeRange;
    protected final NumberSetting placeWallsRange;
    protected final NumberSetting breakRange;
    protected final NumberSetting breakWallsRange;
    protected final BoolSetting lineOfSight;
    protected final BoolSetting doPlace;
    protected final NumberSetting placeDelay;
    protected final BoolSetting airPlace;
    protected final BoolSetting doBreak;
    protected final NumberSetting breakDelay;
    protected final NumberSetting minDamage;
    protected final NumberSetting maxSelfDamage;
    protected final BoolSetting antiSuicide;
    protected final BoolSetting autoSwitch;
    protected final BoolSetting swapBack;
    protected final EnumSetting<TakeFrom> takeFrom;
    protected final NumberSetting moveSlot;
    protected final BoolSetting pauseOnEat;
    protected final BoolSetting pauseOnDrink;
    protected final BoolSetting pauseOnMine;
    protected final BoolSetting pauseOnSneak;
    protected final BoolSetting pauseOnCrystals;
    protected final EnumSetting<FaceMode> faceTarget;
    protected final EnumSetting<SwingMode> swing;
    protected final BoolSetting render;
    protected final BoxStyle placementStyle;
    protected final ColorSetting armedColor;

    private final SlotSwap slots = new SlotSwap();
    private final List<BlockPos> planned = new ArrayList<>();
    private BlockPos armed;
    private int placeTimer;
    private int breakTimer;
    private int moveTimer;
    private String targetName;
    protected String status;

    // addSettings is final and files the settings away without handing out the module.
    @SuppressWarnings("this-escape")
    protected RespawnBlockAura(String name, String description, String noun, float hue) {
        super(name, description, Category.COMBAT);
        targetRange = new NumberSetting("Target range",
            "How far away enemies are considered.", 10, 2, 16, 0.5, " blocks");
        priority = TargetPriority.setting("Targets", TargetPriority.LOW_HEALTH);
        filter = EntityFilter.living("Attack", "attacked", true, EntityFilter.Pick.NONE,
            List.of());
        placeRange = new NumberSetting("Place range",
            "Reach for placing " + noun + ".", 4.5, 1, 6, 0.1, " blocks");
        placeWallsRange = new NumberSetting("Place walls range",
            "Reach for placing on a spot hidden behind blocks.", 4, 0, 6, 0.1, " blocks");
        breakRange = new NumberSetting("Break range",
            "Reach for setting " + noun + " off.", 4.5, 1, 6, 0.1, " blocks");
        breakWallsRange = new NumberSetting("Break walls range",
            "Reach for " + noun + " hidden behind blocks.", 4, 0, 6, 0.1, " blocks");
        lineOfSight = new BoolSetting("Line of sight",
            "Never act on a spot your eyes cannot see.", false);
        doPlace = new BoolSetting("Place", "Place " + noun + " near the target.", true);
        placeDelay = new NumberSetting("Place delay",
            "Ticks to wait between placements.", 3, 0, 20, 1, " ticks").under(doPlace);
        airPlace = new BoolSetting("Air place",
            "Also place on spots with no block beside them to lean on.", true).under(doPlace);
        doBreak = new BoolSetting("Break", "Set off " + noun + " that are already down.", true);
        breakDelay = new NumberSetting("Break delay",
            "Ticks to wait between detonations.", 2, 0, 20, 1, " ticks").under(doBreak);
        minDamage = new NumberSetting("Min damage",
            "Only act when the enemy would take at least this much.", 6, 0, 20, 0.5);
        maxSelfDamage = new NumberSetting("Max self damage",
            "Never take more than this from your own blast.", 8, 0, 20, 0.5);
        antiSuicide = new BoolSetting("Anti suicide", "Never set off a blast that could kill you.", true);
        autoSwitch = new BoolSetting("Auto switch",
            "Swaps to the " + noun + " itself. Off only works whilst you already hold them.", true);
        swapBack = new BoolSetting("Swap back",
            "Returns to the slot you had once the tick is over.", true).under(autoSwitch);
        takeFrom = new EnumSetting<>("Take from",
            "Where " + noun + " and anything else needed come from.", TakeFrom.INVENTORY)
            .describe(TakeFrom.HOTBAR, "Only what is already in your hotbar.")
            .describe(TakeFrom.INVENTORY, "Pulls them up from your backpack as well.");
        moveSlot = new NumberSetting("Move slot",
            "The hotbar slot they are moved into.", 9, 1, 9, 1)
            .under(takeFrom, TakeFrom.INVENTORY);
        pauseOnEat = new BoolSetting("Pause on eat", "Holds off whilst you eat.", true);
        pauseOnDrink = new BoolSetting("Pause on drink", "Holds off whilst you drink.", true);
        pauseOnMine = new BoolSetting("Pause on mine", "Holds off whilst you mine a block.", true);
        pauseOnSneak = new BoolSetting("Pause on sneak",
            "Holds off whilst you sneak so you can walk past your own blocks.", true);
        pauseOnCrystals = new BoolSetting("Pause on crystals",
            "Stands aside whilst CrystalAura places or breaks.", true);
        faceTarget = FaceMode.setting(FaceMode.SERVER);
        swing = SwingMode.setting(SwingMode.BOTH);
        render = new BoolSetting("Render", "Draws the spot the next block goes in and the one being set off.", true);
        placementStyle = new BoxStyle(BoxStyle.Shape.BOTH, hue).under(render);
        armedColor = new ColorSetting("Armed colour", "Colour of the block about to go off.", 0, false)
            .under(render);
        addSettings(targetRange, priority);
        addSettings(filter.settings());
        addSettings(placeRange, placeWallsRange, breakRange, breakWallsRange, lineOfSight,
            doPlace, placeDelay, airPlace, doBreak, breakDelay, minDamage, maxSelfDamage,
            antiSuicide, autoSwitch, swapBack, takeFrom, moveSlot, pauseOnEat, pauseOnDrink,
            pauseOnMine, pauseOnSneak, pauseOnCrystals, faceTarget, swing, render);
        addSettings(placementStyle.settings());
        addSettings(armedColor);
    }

    // False where the world turns the blast into an ordinary use.
    protected abstract boolean explodesHere();

    protected abstract String safeHereStatus();

    protected abstract boolean isAmmo(ItemStack stack);

    // Sets off the best block already down. True whenever one worth using is there.
    protected abstract boolean detonateBest(LivingEntity target);

    protected abstract void placeBest(LivingEntity target);

    @Override
    public String getSuffix() {
        return suffix(targetName, status);
    }

    @Override
    protected void onEnable() {
        placeTimer = 0;
        breakTimer = 0;
        moveTimer = 0;
        slots.forget();
        clear();
    }

    @Override
    protected void onDisable() {
        slots.restore();
        clear();
    }

    private void clear() {
        planned.clear();
        armed = null;
        targetName = null;
        status = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        planned.clear();
        armed = null;
        status = null;
        if (!inGame() || mc.player.isSpectator()) {
            targetName = null;
            return;
        }
        placeTimer = Math.max(0, placeTimer - 1);
        breakTimer = Math.max(0, breakTimer - 1);
        moveTimer = Math.max(0, moveTimer - 1);

        if (!explodesHere()) {
            targetName = null;
            status = safeHereStatus();
            return;
        }
        LivingEntity target = bestTarget();
        targetName = nameOf(target);
        if (target == null) {
            restoreSlot();
            return;
        }
        String pause = pauseReason();
        if (pause != null) {
            restoreSlot();
            status = pause;
            return;
        }
        boolean acted = doBreak.isOn() && detonateBest(target);
        if (!acted && doPlace.isOn() && placeTimer == 0) {
            placeBest(target);
        }
        restoreSlot();
    }

    // Any living thing the filter allows that is not a friend.
    private LivingEntity bestTarget() {
        Entity best = EntityUtil.best(targetRange.getValue(), priority.getValue(),
            entity -> entity instanceof LivingEntity && !EntityUtil.isFriend(entity)
                && filter.matches(entity));
        return best instanceof LivingEntity living ? living : null;
    }

    private static String nameOf(LivingEntity target) {
        if (target instanceof Player player) {
            return EntityUtil.nameOf(player);
        }
        return target == null ? null : target.getName().getString();
    }

    private String pauseReason() {
        if (mc.player.isUsingItem()) {
            ItemUseAnimation use = mc.player.getUseItem().getUseAnimation();
            if (pauseOnEat.isOn() && use == ItemUseAnimation.EAT) {
                return "(eating)";
            }
            if (pauseOnDrink.isOn() && use == ItemUseAnimation.DRINK) {
                return "(drinking)";
            }
        }
        if (pauseOnMine.isOn() && mc.gameMode.isDestroying()) {
            return "(mining)";
        }
        if (pauseOnSneak.isOn()
            && (mc.player.isShiftKeyDown() || Modules.enabled(Sneak.class))) {
            return "(sneaking)";
        }
        if (pauseOnCrystals.isOn()) {
            CrystalAura crystals = Modules.active(CrystalAura.class);
            if (crystals != null && crystals.isActing()) {
                return "(crystals)";
            }
        }
        return null;
    }

    private void restoreSlot() {
        if (swapBack.isOn()) {
            slots.restore();
        } else {
            slots.forget();
        }
    }

    // The reach allowed for a spot depending on whether it can be seen.
    protected double reach(Vec3 point, boolean placing) {
        boolean seen = BlockUtil.canSee(point);
        if (placing) {
            return seen ? placeRange.getValue() : placeWallsRange.getValue();
        }
        return seen ? breakRange.getValue() : breakWallsRange.getValue();
    }

    protected boolean inReach(BlockPos pos, boolean placing) {
        Vec3 centre = Vec3.atCenterOf(pos);
        if (lineOfSight.isOn() && !BlockUtil.canSee(centre)) {
            return false;
        }
        return BlockUtil.distanceTo(pos) <= reach(centre, placing);
    }

    protected boolean worthIt(float damage) {
        return damage >= minDamage.getFloat();
    }

    protected boolean selfSafe(Vec3 source, BlockPos... ignored) {
        return ExplosionUtil.selfSafe(source, ExplosionUtil.RESPAWN_BLOCK_POWER,
            maxSelfDamage.getFloat(), antiSuicide.isOn(), ignored);
    }

    protected Iterable<BlockPos> nearby(LivingEntity target) {
        double furthest = Math.max(Math.max(placeRange.getValue(), placeWallsRange.getValue()),
            Math.max(breakRange.getValue(), breakWallsRange.getValue()));
        return BlockUtil.positionsAround(target.blockPosition(), (int) Math.ceil(furthest));
    }

    // True when the spot may be placed on given the air place setting.
    protected boolean placeable(BlockPos pos) {
        return BlockUtil.isReplaceable(pos) && (airPlace.isOn() || BlockUtil.findPlaceSupport(pos) != null);
    }

    // Turns towards the point when asked to. False whilst the turn is still on its way.
    protected boolean look(Vec3 point) {
        if (faceTarget.getValue().face(point, RotationPriority.AURA)) {
            return true;
        }
        status = "(turning)";
        return false;
    }

    // Puts ammunition in hand. Minus one with a status set when it cannot.
    protected int armAmmo(String none) {
        return armItem(this::isAmmo, none);
    }

    // Puts a matching hotbar item in hand and pulls one up when that is allowed.
    protected int armItem(Predicate<ItemStack> test, String none) {
        int slot = InventoryUtil.hotbarSlot(test);
        if (slot == -1 && takeFrom.is(TakeFrom.INVENTORY)) {
            moveUp(test);
            slot = InventoryUtil.hotbarSlot(test);
        }
        if (slot == -1) {
            status = none;
            return -1;
        }
        return armSlot(slot);
    }

    // Puts the hotbar slot in hand when the switch setting allows. Minus one when it does not.
    protected int armSlot(int slot) {
        if (slot != InventoryUtil.selectedSlot() && !autoSwitch.isOn()) {
            status = "(hold them)";
            return -1;
        }
        slots.select(slot);
        return slot;
    }

    private void moveUp(Predicate<ItemStack> test) {
        if (moveTimer > 0 || !InventoryUtil.canClick()) {
            return;
        }
        int from = InventoryUtil.findSlot(test, InventoryUtil.WHOLE_INVENTORY);
        if (from < InventoryUtil.HOTBAR_SIZE) {
            return;
        }
        moveTimer = MOVE_DELAY;
        InventoryUtil.swap(InventoryUtil.networkSlot(from),
            InventoryUtil.networkSlot(moveSlot.getInt() - 1));
    }

    // Any hotbar slot that is not ammunition. Minus one when there is none.
    protected int armOther(String none) {
        int slot = InventoryUtil.hotbarSlot(stack -> !isAmmo(stack));
        if (slot == -1) {
            status = none;
            return -1;
        }
        return armSlot(slot);
    }

    protected void placed() {
        placeTimer = placeDelay.getInt();
    }

    protected void fired() {
        breakTimer = breakDelay.getInt();
    }

    protected boolean breakReady() {
        return breakTimer == 0;
    }

    protected void plan(BlockPos... spots) {
        planned.clear();
        for (BlockPos spot : spots) {
            if (spot != null) {
                planned.add(spot);
            }
        }
    }

    protected void arm(BlockPos pos) {
        armed = pos;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (BlockPos spot : planned) {
            placementStyle.draw(batch, spot, false);
        }
        if (armed != null) {
            placementStyle.draw(batch, DrawBatch.blockBox(armed), armedColor.getColor(), false);
        }
    }
}
