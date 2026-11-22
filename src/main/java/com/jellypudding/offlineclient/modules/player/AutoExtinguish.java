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
import com.jellypudding.offlineclient.util.EntityUtil;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

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

    private static final int POUR_GRACE = 10;

    private static final int COLLECT_GIVE_UP = 60;

    private static final int WALK_RETRY = 40;

    private static final int SETTLE_TICKS = 10;

    private final RankSetting<Remedy> remedies = new RankSetting<>("Remedies",
        "What puts you out. The first one on the list that can work where you stand is used.",
        Remedy.class, Remedy::icon);
    private final NumberSetting waterReach = new NumberSetting("Water reach",
        "How far away water may be for Nearby water to walk you into it.", 4, 1, 8, 1, " blocks")
        .under(remedies, () -> remedies.ranked().contains(Remedy.NEARBY_WATER));
    private final BoolSetting wallOff = new BoolSetting("Wall off fire",
        "Fire and lava stop you like solid blocks. Fire resistance lets you through.", true);

    private final Feeding feeding = new Feeding(this);
    private final InventoryUtil.HotbarLoan bucketLoan = new InventoryUtil.HotbarLoan();
    private final Trip trip = new Trip();

    private BlockPos poured;
    private int pouredTick;
    // Ticks ShulkerPacker has packed since the pour. They do not count towards giving the water up.
    private int packedTicks;
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

    @Override
    protected void onDisable() {
        stopAll();
    }

    private void stopAll() {
        feeding.stop();
        trip.stop();
        letGoOfPour();
    }

    // Read by BlockCollisionsMixin.
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
        if (mc.player.isSpectator() || mc.player.getAbilities().invulnerable) {
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
        if (!EntityUtil.burnHurts(mc.player) || mc.gui.screen() != null || pouring()) {
            return;
        }
        for (Remedy remedy : remedies.ranked()) {
            if (apply(remedy)) {
                return;
            }
        }
    }

    private boolean apply(Remedy remedy) {
        return switch (remedy) {
            case BREAK_FIRE -> breakFire();
            case WATER_BUCKET -> pour();
            case NEARBY_WATER -> walkToWater();
            case FIRE_RESISTANCE -> takeFireResistance();
        };
    }

    // You still burn for a while but the fire cannot light you again.
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

    private boolean pour() {
        LocalPlayer player = mc.player;
        if (!player.onGround() || player.isInLava() || !InventoryUtil.inventoryFree() || Modules.packing()) {
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
        packedTicks = 0;
        return true;
    }

    // The bucket casts this same ray.
    private BlockPos pourSpot() {
        BlockHitResult hit = Buckets.ray(mc.player.getYRot(), RotationManager.STRAIGHT_DOWN, ClipContext.Fluid.NONE);
        return hit == null ? null : Buckets.pourSpot(hit.getBlockPos(), mc.player.isShiftKeyDown());
    }

    private void collect() {
        if (poured == null) {
            return;
        }
        if (Modules.packing()) {
            packedTicks++;
            return;
        }
        int waited = mc.player.tickCount - pouredTick - packedTicks;
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
        if (!EntityUtil.burnHurts(mc.player)) {
            trip.stop();
            return;
        }
        // A walk that ends short of the water has done all it can.
        Trip.State state = trip.tick();
        if (state == Trip.State.FAILED || state == Trip.State.ARRIVED) {
            walkRest = WALK_RETRY;
        }
    }

    // A splash bottle thrown at your feet works at once and goes before a drink. A bottle already
    // thrown may still bring fire resistance and is waited for.
    private boolean takeFireResistance() {
        if (!InventoryUtil.inventoryFree() || Feeding.busy(this) || Feeding.bottleLanding()
            || Modules.packing()) {
            return false;
        }
        int splash = fireResistanceSlot(Items.SPLASH_POTION);
        if (splash != -1) {
            return feeding.throwDown(splash, SETTLE_TICKS);
        }
        int slot = fireResistanceSlot(Items.POTION);
        if (slot == -1) {
            return false;
        }
        feeding.begin(slot);
        return feeding.isActive();
    }

    private void continueDrinking() {
        // A screen swallows the use key.
        if (!EntityUtil.burnHurts(mc.player) || mc.gui.screen() != null
            || !resists(mc.player.getInventory().getSelectedItem(), Items.POTION)) {
            feeding.stop();
            return;
        }
        if (!feeding.tick()) {
            feeding.finish(SETTLE_TICKS, false);
        }
    }

    private static int fireResistanceSlot(Item bottle) {
        return InventoryUtil.findSlot(stack -> resists(stack, bottle), InventoryUtil.WHOLE_INVENTORY);
    }

    private static boolean resists(ItemStack stack, Item bottle) {
        return stack.is(bottle) && ItemUtil.carriesEffect(stack, MobEffects.FIRE_RESISTANCE.value());
    }
}
