package com.jellypudding.offlineclient.module;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.BlastLimits;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.CoverMiner;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.Holes;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TargetFilter;
import com.jellypudding.offlineclient.util.TargetPriority;
import com.jellypudding.offlineclient.util.Targeting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

// The shape shared by the auras that place a respawn block beside an enemy and set it off.
// A subclass supplies the block and how it is placed and fired.
public abstract class RespawnBlockAura extends Module implements Targeting {

    // Ticks between attempts to pull ammunition up from the backpack.
    private static final int MOVE_DELAY = 4;

    // Ticks before a search for a block worth mining that found none runs again. Each search
    // is a whole spot search for every block in the way.
    private static final int SEARCH_DELAY = 10;

    // Where a module is allowed to take its blocks from.
    public enum TakeFrom { HOTBAR, INVENTORY }

    protected final NumberSetting targetRange;
    protected final EnumSetting<TargetPriority> priority;
    protected final TargetFilter targets = new TargetFilter();
    protected final EntityFilter filter;
    protected final NumberSetting placeRange;
    protected final NumberSetting placeWallsRange;
    protected final NumberSetting breakRange;
    protected final NumberSetting breakWallsRange;
    protected final BoolSetting lineOfSight;
    protected final BoolSetting doPlace;
    protected final NumberSetting placeDelay;
    protected final BoolSetting airPlace;
    protected final BoolSetting clearWay;
    protected final BoolSetting doBreak;
    protected final NumberSetting minDamage;
    protected final BlastLimits limits = new BlastLimits(8, 20);
    protected final BoolSetting autoSwitch;
    protected final BoolSetting swapBack;
    protected final EnumSetting<TakeFrom> takeFrom;
    protected final NumberSetting moveSlot;
    protected final BoolSetting onlyOnUse;
    protected final BoolSetting pauseOnEat;
    protected final BoolSetting pauseOnDrink;
    protected final BoolSetting pauseOnUse;
    protected final BoolSetting pauseOnMine;
    protected final BoolSetting pauseOnSneak;
    protected final BoolSetting pauseOnCrystals;
    protected final EnumSetting<FaceMode> faceTarget;
    protected final EnumSetting<SwingMode> swing;
    protected final BoolSetting render;
    protected final BoxStyle placementStyle;
    protected final ColorSetting armedColor;

    private final SlotSwap slots = new SlotSwap();
    private final CoverMiner miner;
    private final List<BlockPos> planned = new ArrayList<>();
    private BlockPos armed;
    private int placeTimer;
    private int breakTimer;
    private int moveTimer;
    private int searchTimer;
    private LivingEntity target;
    protected String status;

    // Each aura brings options of its own. Those for placing sit under Place and the delays
    // under Break. The escape is safe because addSettings is final and never hands the module out.
    @SuppressWarnings("this-escape")
    protected RespawnBlockAura(String name, String description, String noun, float hue,
                               List<? extends Setting<?>> placeOptions, List<NumberSetting> breakDelays) {
        super(name, description, Category.COMBAT);
        targetRange = new NumberSetting("Target range",
            "How far away enemies are considered.", 10, 2, 16, 0.5, " blocks");
        priority = TargetPriority.setting("Targets", TargetPriority.LOW_HEALTH);
        filter = EntityFilter.mortal("Attacks", "attacked", true, EntityFilter.Pick.NONE,
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
            "Never acts on a spot your eyes cannot see.", false);
        doPlace = new BoolSetting("Place", "Places " + noun + " near the target.", true);
        placeDelay = new NumberSetting("Place delay",
            "Ticks to wait between placements.", 3, 0, 20, 1, " ticks").under(doPlace);
        airPlace = new BoolSetting("Air place",
            "Also places on spots with no block beside them to lean on.", true).under(doPlace);
        for (Setting<?> option : placeOptions) {
            option.under(doPlace);
        }
        clearWay = new BoolSetting("Clear the way",
            "Mines what keeps the blast off the target whilst there is nowhere to place. That is the block"
                + " they stand in or a block beside their head or web or string in their body.", false)
            .under(doPlace);
        doBreak = new BoolSetting("Break", "Sets off " + noun + " that are already down.", true);
        for (NumberSetting delay : breakDelays) {
            delay.under(doBreak);
        }
        minDamage = new NumberSetting("Min damage",
            "Only acts when the enemy would take at least this much.", 6, 0, 20, 0.5);
        autoSwitch = new BoolSetting("Auto switch",
            "Swaps to the " + noun + " itself. Off only works whilst you already hold them.", true);
        swapBack = new BoolSetting("Swap back",
            "Returns to the slot you had once the tick is over.", true).under(autoSwitch);
        takeFrom = new EnumSetting<>("Take from",
            "Where " + noun + " and anything else needed come from.", TakeFrom.INVENTORY)
            .describe(TakeFrom.HOTBAR, "Only what is already in your hotbar.")
            .describe(TakeFrom.INVENTORY, "Pulls them up from your backpack as well.");
        moveSlot = new NumberSetting("Move slot",
            "The hotbar slot they are moved into.", 9, 1, 9, 1).max(9)
            .under(takeFrom, TakeFrom.INVENTORY);
        onlyOnUse = new BoolSetting("Only on use key",
            "Only places and sets off " + noun + " whilst you hold the use key. With food or a bow or a"
                + " shield in hand the key uses that item and the pauses below still apply.", false);
        pauseOnEat = new BoolSetting("Pause on eat", "Holds off whilst you eat.", true);
        pauseOnDrink = new BoolSetting("Pause on drink", "Holds off whilst you drink.", true);
        pauseOnUse = new BoolSetting("Pause on use",
            "Holds off whilst you use anything else such as a bow or a shield.", true);
        pauseOnMine = new BoolSetting("Pause on mine", "Holds off whilst you mine a block.", true);
        pauseOnSneak = new BoolSetting("Pause on sneak",
            "Holds off whilst you sneak.", true);
        pauseOnCrystals = new BoolSetting("Pause on crystals",
            "Stands aside whilst CrystalAura places or breaks.", true);
        faceTarget = FaceMode.setting(FaceMode.SERVER);
        swing = SwingMode.setting(SwingMode.BOTH);
        render = new BoolSetting("Render", "Draws the spot the next block goes in and the one being set off.", true);
        placementStyle = new BoxStyle(BoxStyle.Shape.BOTH, hue).under(render);
        armedColor = new ColorSetting("Armed colour", "Colour of the block about to go off.", 0, false)
            .under(render);
        miner = new CoverMiner(() -> !faceTarget.is(FaceMode.OFF), swing::getValue).under(clearWay);
        addSettings(targetRange, priority);
        addSettings(filter.settings());
        addSettings(targets.settings());
        addSettings(placeRange, placeWallsRange, breakRange, breakWallsRange, lineOfSight,
            doPlace, placeDelay, airPlace);
        addSettings(placeOptions.toArray(new Setting<?>[0]));
        addSettings(clearWay);
        addSettings(miner.settings());
        addSettings(doBreak);
        addSettings(breakDelays.toArray(new Setting<?>[0]));
        addSettings(minDamage);
        addSettings(limits.settings());
        addSettings(autoSwitch, swapBack, takeFrom, moveSlot, onlyOnUse, pauseOnEat, pauseOnDrink,
            pauseOnUse, pauseOnMine, pauseOnSneak, pauseOnCrystals, faceTarget, swing, render);
        addSettings(placementStyle.settings());
        addSettings(armedColor);
    }

    // False where the world turns the blast into an ordinary use.
    protected abstract boolean explodesHere();

    protected abstract String safeHereStatus();

    protected abstract boolean isAmmo(ItemStack stack);

    // Sets off the best block already down. True whenever one worth using is there.
    protected abstract boolean detonateBest(LivingEntity target);

    // Places on the best spot. False when no spot is worth a block.
    protected abstract boolean placeBest(LivingEntity target);

    // True when a spot worth a block would open up with this block mined out.
    protected abstract boolean spotWithout(LivingEntity target, BlockPos gone);

    @Override
    public String getSuffix() {
        return suffix(target == null ? null : EntityUtil.displayNameOf(target), status);
    }

    // The enemy picked this tick. Null whilst there is none.
    @Override
    public LivingEntity getFought() {
        return target;
    }

    @Override
    protected void onEnable() {
        placeTimer = 0;
        breakTimer = 0;
        moveTimer = 0;
        searchTimer = 0;
        slots.forget();
        clear();
    }

    @Override
    protected void onDisable() {
        miner.stop();
        slots.restore();
        clear();
    }

    private void clear() {
        planned.clear();
        armed = null;
        target = null;
        status = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        planned.clear();
        armed = null;
        status = null;
        if (mc.player.isSpectator()) {
            miner.stop();
            target = null;
            return;
        }
        placeTimer = Math.max(0, placeTimer - 1);
        breakTimer = Math.max(0, breakTimer - 1);
        moveTimer = Math.max(0, moveTimer - 1);
        searchTimer = Math.max(0, searchTimer - 1);

        if (!explodesHere()) {
            miner.stop();
            target = null;
            status = safeHereStatus();
            return;
        }
        target = bestTarget();
        if (target == null) {
            miner.stop();
            restoreSlot();
            return;
        }
        String pause = pauseReason();
        if (pause != null) {
            miner.rest();
            restoreSlot();
            status = pause;
            return;
        }
        boolean acted = doBreak.isOn() && detonateBest(target);
        boolean stuck = !acted && doPlace.isOn() && placeTimer == 0 && !placeBest(target);
        // The slot taken last goes back first. The miner took its tool before anything was placed.
        restoreSlot();
        if (stuck && clearWay.isOn() && hasAmmo()) {
            mineObstacle(target);
        } else {
            miner.stop();
        }
    }

    // Works on the block in the way. A feeder or the packer has the hand first and another
    // miner the server's break.
    private void mineObstacle(LivingEntity target) {
        if (Hands.slotsTaken()) {
            miner.rest();
            return;
        }
        if (miner.waiting()) {
            miner.rest();
            status = "(waiting to mine)";
            return;
        }
        BlockPos obstacle = obstacle(target);
        if (obstacle == null || BlockMiner.minesOther(obstacle)) {
            miner.stop();
            return;
        }
        plan(obstacle);
        status = "(clearing the way)";
        miner.drive(obstacle, true, () -> { });
    }

    // The first block in the way whose loss opens a spot. The blocks of their own body come
    // first and then the ones beside their head from the nearest on. One already being
    // mined is kept whilst it stands there.
    private BlockPos obstacle(LivingEntity target) {
        BlockPos head = Holes.headBlock(target);
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos = target.blockPosition(); pos.getY() <= head.getY(); pos = pos.above()) {
            candidates.add(pos);
        }
        List<BlockPos> sides = new ArrayList<>();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            sides.add(head.relative(side));
        }
        sides.sort(Comparator.comparingDouble(BlockUtil::distanceTo));
        candidates.addAll(sides);
        BlockPos mined = miner.target();
        if (mined != null && candidates.contains(mined) && inTheWay(mined)) {
            return mined;
        }
        if (searchTimer > 0) {
            return null;
        }
        for (BlockPos pos : candidates) {
            if (inTheWay(pos) && spotWithout(target, pos)) {
                return pos;
            }
        }
        searchTimer = SEARCH_DELAY;
        return null;
    }

    // Something that can be mined and that a placement cannot simply replace.
    private boolean inTheWay(BlockPos pos) {
        return BlockUtil.isBreakable(pos) && !BlockUtil.isReplaceable(pos) && inReach(pos, false);
    }

    private boolean hasAmmo() {
        int limit = takeFrom.is(TakeFrom.INVENTORY) ? InventoryUtil.WHOLE_INVENTORY : InventoryUtil.HOTBAR_SIZE;
        return InventoryUtil.findSlot(this::isAmmo, limit) != -1;
    }

    // Any living thing the filter allows that is not a friend.
    private LivingEntity bestTarget() {
        Entity best = EntityUtil.best(targetRange.getValue(), priority.getValue(),
            entity -> entity instanceof LivingEntity && !EntityUtil.isFriend(entity)
                && filter.matches(entity) && targets.allows(entity));
        return best instanceof LivingEntity living ? living : null;
    }

    private String pauseReason() {
        if (onlyOnUse.isOn() && !InputUtil.physicallyHeld(mc.options.keyUse)) {
            return "(use key up)";
        }
        if (mc.player.isUsingItem()) {
            ItemUseAnimation use = mc.player.getUseItem().getUseAnimation();
            if (use == ItemUseAnimation.EAT) {
                if (pauseOnEat.isOn()) {
                    return "(eating)";
                }
            } else if (use == ItemUseAnimation.DRINK) {
                if (pauseOnDrink.isOn()) {
                    return "(drinking)";
                }
            } else if (pauseOnUse.isOn()) {
                return "(using an item)";
            }
        }
        // Its own work on a block in the way is no reason to hold off.
        if (pauseOnMine.isOn() && mc.gameMode.isDestroying() && miner.target() == null) {
            return "(mining)";
        }
        if (pauseOnSneak.isOn() && Modules.sneaking()) {
            return "(sneaking)";
        }
        if (pauseOnCrystals.isOn() && Modules.crystalsActing()) {
            return "(crystals)";
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
        return placing ? BlockUtil.reachFor(point, placeRange.getValue(), placeWallsRange.getValue())
            : BlockUtil.reachFor(point, breakRange.getValue(), breakWallsRange.getValue());
    }

    protected boolean inReach(BlockPos pos, boolean placing) {
        Vec3 centre = Vec3.atCenterOf(pos);
        if (lineOfSight.isOn() && !BlockUtil.canSee(centre)) {
            return false;
        }
        return BlockUtil.distanceTo(pos) <= reach(centre, placing);
    }

    // What a blast at the block would deal the target. Nought unless it beats the best yet
    // and the minimum and spares you and your friends. The ignored blocks are gone by then.
    protected float damageBeating(float best, LivingEntity target, BlockPos blastAt, BlockPos... ignored) {
        Vec3 centre = Vec3.atCenterOf(blastAt);
        float damage = ExplosionUtil.blastDamage(target, centre, ExplosionUtil.RESPAWN_BLOCK_POWER,
            Vec3.ZERO, ignored);
        if (damage < minDamage.getFloat() || damage <= best) {
            return 0;
        }
        return limits.allows(centre, ExplosionUtil.RESPAWN_BLOCK_POWER, ignored) ? damage : 0;
    }

    protected Iterable<BlockPos> nearby(LivingEntity target) {
        double furthest = Math.max(Math.max(placeRange.getValue(), placeWallsRange.getValue()),
            Math.max(breakRange.getValue(), breakWallsRange.getValue()));
        return BlockUtil.positionsAround(target.blockPosition(), (int) Math.ceil(furthest));
    }

    // True when the spot may be placed on given the air place setting. The block named as
    // gone counts as mined out. Null names none.
    protected boolean placeable(BlockPos pos, BlockPos gone) {
        return (pos.equals(gone) || BlockUtil.isReplaceable(pos))
            && (airPlace.isOn() || BlockUtil.findPlaceSupport(pos) != null);
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
        if (moveTimer > 0 || Modules.packing() || !InventoryUtil.canClick()) {
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

    // Keeps the next charge or detonation back for this many ticks.
    protected void holdBreak(int ticks) {
        breakTimer = ticks;
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
