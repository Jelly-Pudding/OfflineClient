package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.path.PathWalker;
import com.jellypudding.offlineclient.path.Trip;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RankSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.Buckets;
import com.jellypudding.offlineclient.util.Feeding;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RotationManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

// Puts you out when you catch fire. Each tick the first remedy on the player's list that
// can work where you stand is tried. Fire and lava can also be walled off before you reach them.
public final class AutoExtinguish extends Module {

    public enum Remedy {
        BREAK_FIRE, WATER_BUCKET, NEARBY_WATER, FIRE_RESISTANCE;

        public ItemStack icon() {
            return switch (this) {
                case BREAK_FIRE -> new ItemStack(Items.FLINT_AND_STEEL);
                case WATER_BUCKET -> new ItemStack(Items.WATER_BUCKET);
                case NEARBY_WATER -> new ItemStack(Items.LILY_PAD);
                case FIRE_RESISTANCE -> PotionContents.createItemStack(Items.POTION, Potions.FIRE_RESISTANCE);
            };
        }
    }

    // Ticks a pour gets to put the fire out before the next remedy is tried.
    private static final int POUR_GRACE = 10;

    // Ticks poured water waits to be taken back before it is left where it is.
    private static final int COLLECT_GIVE_UP = 60;

    // Ticks Nearby water rests after a walk that went nowhere.
    private static final int WALK_RETRY = 40;

    // Ticks to wait after a drink whilst the effect lands.
    private static final int SETTLE_TICKS = 10;

    private final RankSetting<Remedy> remedies = new RankSetting<>("Remedies",
        "What puts you out. The first one on the list that can work where you stand is used.",
        Remedy.class, Remedy::icon);
    private final NumberSetting waterReach = new NumberSetting("Water reach",
        "How far away water may be for Nearby water to walk you into it.", 4, 1, 8, 1, " blocks")
        .under(remedies, () -> remedies.ranked().contains(Remedy.NEARBY_WATER));
    private final BoolSetting wallOff = new BoolSetting("Wall off fire",
        "Fire and lava stop you like solid blocks. Fire resistance lets you through.", true);

    private final Feeding feeding = new Feeding();
    private final InventoryUtil.HotbarLoan bucketLoan = new InventoryUtil.HotbarLoan();
    private final Trip trip = new Trip();

    // Where the bucket poured its water. Null whilst nothing waits to be taken back.
    private BlockPos poured;
    private int pouredTick;
    private int walkRest;

    public AutoExtinguish() {
        super("AutoExtinguish", "Puts you out when you catch fire.", Category.PLAYER);
        addSettings(remedies, waterReach, wallOff);
        searchTags("fire", "burning", "water bucket", "fire resistance", "lava");
    }

    @Override
    public String getSuffix() {
        if (feeding.isActive()) {
            return "drinking";
        }
        return trip.active() ? "walking" : null;
    }

    public boolean isBusy() {
        return isEnabled() && feeding.isActive();
    }

    @Override
    protected void onDisable() {
        stopAll();
    }

    private void stopAll() {
        feeding.stop();
        trip.stop();
        letGoOfPour();
    }

    // Read by BlockCollisionsMixin. Fire and lava stand in your way as full blocks.
    public boolean isWall(BlockState state) {
        LocalPlayer player = mc.player;
        if (!wallOff.isOn() || player == null || player.getAbilities().invulnerable || player.isInLava()
            || player.hasEffect(MobEffects.FIRE_RESISTANCE)) {
            return false;
        }
        return state.getBlock() instanceof BaseFireBlock || state.getFluidState().is(FluidTags.LAVA);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || mc.player.getAbilities().invulnerable) {
            stopAll();
            return;
        }
        if (mc.player.isDeadOrDying()) {
            // Respawn rebuilds the inventory. A lent slot could hold anything by then.
            trip.stop();
            bucketLoan.forget();
            poured = null;
        }
        if (walkRest > 0) {
            walkRest--;
        }
        if (feeding.waiting()) {
            return;
        }
        collect();
        if (feeding.isActive()) {
            continueDrinking();
            return;
        }
        if (trip.active()) {
            walk();
            return;
        }
        if (!burning() || mc.gui.screen() != null || pouring()) {
            return;
        }
        for (Remedy remedy : remedies.ranked()) {
            if (apply(remedy)) {
                return;
            }
        }
    }

    // Burning in a way that hurts. Water and rain and powder snow put the fire out by
    // themselves and fire resistance takes the harm away.
    private boolean burning() {
        LocalPlayer player = mc.player;
        return player.isOnFire() && !player.hasEffect(MobEffects.FIRE_RESISTANCE)
            && !player.isInWaterOrRain() && !player.isInPowderSnow;
    }

    private boolean apply(Remedy remedy) {
        return switch (remedy) {
            case BREAK_FIRE -> breakFire();
            case WATER_BUCKET -> pour();
            case NEARBY_WATER -> walkToWater();
            case FIRE_RESISTANCE -> drink();
        };
    }

    // Punches out the fire you stand in. You still burn for a while but it cannot light you again.
    private boolean breakFire() {
        for (BlockPos pos : BlockPos.betweenClosed(mc.player.getBoundingBox())) {
            if (BlockUtil.state(pos).getBlock() instanceof BaseFireBlock) {
                mc.gameMode.startDestroyBlock(pos.immutable(), Direction.UP);
                return true;
            }
        }
        return false;
    }

    private boolean pouring() {
        return poured != null && mc.player.tickCount - pouredTick < POUR_GRACE;
    }

    // Pours a water bucket straight down. It only counts when the water lands on you.
    private boolean pour() {
        LocalPlayer player = mc.player;
        if (!player.onGround() || player.isInLava() || !InventoryUtil.inventoryFree()) {
            return false;
        }
        BlockPos feet = player.blockPosition();
        BlockPos spot = pourSpot();
        if (spot == null || !(spot.equals(feet) || spot.equals(feet.above()))
            || BlockUtil.waterEvaporates(spot)) {
            return false;
        }
        int slot = InventoryUtil.findSlot(Items.WATER_BUCKET, InventoryUtil.WHOLE_INVENTORY);
        if (slot == -1 || !bucketLoan.select(slot)) {
            return false;
        }
        if (!RotationManager.whileFacing(player.getYRot(), RotationManager.STRAIGHT_DOWN, InputUtil::useMainHand)) {
            bucketLoan.giveBack(bucketLoan.stillMine());
            return false;
        }
        poured = spot;
        pouredTick = player.tickCount;
        return true;
    }

    // Where a bucket poured straight down puts its water. The bucket casts this same ray.
    private BlockPos pourSpot() {
        BlockHitResult hit = Buckets.ray(mc.player.getYRot(), RotationManager.STRAIGHT_DOWN, ClipContext.Fluid.NONE);
        return hit == null ? null : Buckets.pourSpot(hit.getBlockPos(), mc.player.isShiftKeyDown());
    }

    // Takes the water back once the server has put the fire out. The emptied bucket is still in hand.
    private void collect() {
        if (poured == null) {
            return;
        }
        int waited = mc.player.tickCount - pouredTick;
        if (waited < 0 || waited > COLLECT_GIVE_UP) {
            letGoOfPour();
            return;
        }
        if (mc.player.isOnFire() || !BlockUtil.isWaterSource(poured) || !InventoryUtil.inventoryFree()) {
            return;
        }
        if (Buckets.scoop(poured, bucketLoan)) {
            letGoOfPour();
        }
    }

    private void letGoOfPour() {
        poured = null;
        bucketLoan.giveBack(bucketLoan.stillMine());
    }

    private boolean walkToWater() {
        if (mc.player.isInLava() || walkRest > 0) {
            return false;
        }
        BlockPos water = BlockUtil.nearestWithin(waterReach.getValue(), pos -> {
            BlockState state = BlockUtil.state(pos);
            return state.getFluidState().is(FluidTags.WATER) && !BlockUtil.blocksMotion(state);
        });
        if (water == null) {
            return false;
        }
        trip.walker().turn(PathWalker.Turn.NONE);
        return trip.start(water, 0);
    }

    private void walk() {
        if (!burning()) {
            trip.stop();
            return;
        }
        // A walk that ends short of the water has done all it can.
        Trip.State state = trip.tick();
        if (state == Trip.State.FAILED || state == Trip.State.ARRIVED) {
            walkRest = WALK_RETRY;
        }
    }

    private boolean drink() {
        if (!InventoryUtil.inventoryFree() || Modules.feeding(this)) {
            return false;
        }
        int slot = InventoryUtil.findSlot(AutoExtinguish::drinkable, InventoryUtil.WHOLE_INVENTORY);
        if (slot == -1) {
            return false;
        }
        feeding.begin(slot);
        return feeding.isActive();
    }

    private void continueDrinking() {
        // A screen swallows the use key.
        if (!burning() || mc.gui.screen() != null
            || !drinkable(mc.player.getInventory().getSelectedItem())) {
            feeding.stop();
            return;
        }
        if (!feeding.tick()) {
            feeding.finish(SETTLE_TICKS, false);
        }
    }

    private static boolean drinkable(ItemStack stack) {
        return stack.is(Items.POTION) && ItemUtil.carriesEffect(stack, MobEffects.FIRE_RESISTANCE.value());
    }
}
