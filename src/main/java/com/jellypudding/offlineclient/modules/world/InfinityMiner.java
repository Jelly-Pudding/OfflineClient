package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.path.PathWalker;
import com.jellypudding.offlineclient.path.Trip;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Mines for as long as the pickaxe lasts. When it wears down the bot swaps to
// ore that drops experience and lets Mending heal it before going back.
public final class InfinityMiner extends Module {

    public enum WhenFull { STOP, WALK_HOME, LOG_OUT, WALK_HOME_AND_LOG_OUT }

    private static final int SCAN_TICKS = 40;
    // Ticks a spot the walker could not reach is left alone.
    private static final int SHUN_TICKS = 1200;
    private static final double GOAL_RADIUS = 3;
    private static final int PERCENT = 100;

    private final RegistryListSetting<Block> targetBlocks = new RegistryListSetting<>("Target blocks",
        "The ore mined whilst the pickaxe is healthy. Click to pick it.", BuiltInRegistries.BLOCK,
        List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE));
    private final RegistryListSetting<Item> targetItems = new RegistryListSetting<>("Target items",
        "What the ore drops. A full bag with none of these part stacked counts as full.",
        BuiltInRegistries.ITEM, List.of(Items.DIAMOND));
    private final RegistryListSetting<Block> repairBlocks = new RegistryListSetting<>("Repair blocks",
        "The ore mined for experience whilst the pickaxe heals. Click to pick it.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.REDSTONE_ORE,
            Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.NETHER_QUARTZ_ORE));
    private final NumberSetting repairAt = new NumberSetting("Repair at",
        "Pickaxe durability that starts the repair.", 20, 1, 99, 1, "%").min(1).max(99);
    private final NumberSetting mineAt = new NumberSetting("Mine at",
        "Pickaxe durability that ends the repair.", 70, 1, 99, 1, "%").min(1).max(99);
    private final NumberSetting scanRange = new NumberSetting("Scan range",
        "How far around you ore is looked for.", 16, 4, 48, 1, " blocks").min(2).max(64);
    private final EnumSetting<WhenFull> whenFull = new EnumSetting<>("When full",
        "What happens once the bag is full.", WhenFull.STOP)
        .describe(WhenFull.STOP, "Switches off where you stand.")
        .describe(WhenFull.WALK_HOME, "Walks back to where you switched it on.")
        .describe(WhenFull.LOG_OUT, "Leaves the server where you stand.")
        .describe(WhenFull.WALK_HOME_AND_LOG_OUT, "Walks back to the start and leaves the server there.");
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns towards each block on the server side.", true);
    private final BoolSetting render = new BoolSetting("Show target",
        "Outlines the block being walked to.", true);
    private final BoxStyle targetBox = new BoxStyle(BoxStyle.Shape.BOTH, 180).under(render);

    private final Trip trip = new Trip();
    private final SlotSwap slots = new SlotSwap();
    private final Map<BlockPos, Integer> shunned = new HashMap<>();

    private BlockPos home;
    private BlockPos target;
    private boolean repairing;
    private boolean headingHome;
    private int scanTimer;

    public InfinityMiner() {
        super("InfinityMiner", "Mines ore for ever and lets Mending heal the pickaxe on the way.",
            Category.WORLD);
        addSettings(targetBlocks, targetItems, repairBlocks, repairAt, mineAt, scanRange, whenFull,
            rotate, render);
        addSettings(targetBox.settings());
        searchTags("infinity miner", "auto mine", "mending", "ore bot");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    public String getSuffix() {
        if (headingHome) {
            return "going home";
        }
        return repairing ? "repairing" : null;
    }

    @Override
    protected void onEnable() {
        target = null;
        repairing = false;
        headingHome = false;
        scanTimer = 0;
        shunned.clear();
        home = inGame() ? mc.player.blockPosition() : null;
        trip.finder().breakBlocks(true);
        trip.walker().turn(PathWalker.Turn.CLIENT);
    }

    @Override
    protected void onDisable() {
        trip.stop();
        BlockMiner.release();
        slots.restoreIfMine();
        target = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || mc.gui.screen() != null) {
            return;
        }
        if (headingHome) {
            walkHome();
            return;
        }
        if (isFull()) {
            onFull();
            return;
        }
        if (!holdPickaxe()) {
            ChatUtil.error("InfinityMiner needs a Mending pickaxe without Silk Touch in the hotbar.");
            setEnabled(false);
            return;
        }
        if (repairAt.getValue() >= mineAt.getValue()) {
            ChatUtil.error("Repair at has to be lower than Mine at.");
            setEnabled(false);
            return;
        }
        updateRepairing();
        forgetShunned();
        if (target != null && !wanted(target)) {
            target = null;
            trip.stop();
        }
        if (target == null && !scan()) {
            return;
        }
        if (BlockUtil.distanceTo(target) <= mc.player.blockInteractionRange()) {
            trip.stop();
            BlockMiner.mine(target, rotate.isOn());
            return;
        }
        BlockMiner.release();
        if (!trip.active()) {
            trip.start(target, GOAL_RADIUS);
        }
        // A walk that ends short of reach has run into something the walker cannot pass.
        Trip.State state = trip.tick();
        if (state == Trip.State.FAILED || state == Trip.State.ARRIVED) {
            shunned.put(target, mc.player.tickCount);
            target = null;
        }
    }

    // A worn pickaxe swaps the target to experience ore until it is healthy again.
    private void updateRepairing() {
        ItemStack pick = mc.player.getMainHandItem();
        double left = (pick.getMaxDamage() - pick.getDamageValue()) * (double) PERCENT / pick.getMaxDamage();
        if (!repairing && left <= repairAt.getValue()) {
            repairing = true;
            target = null;
            ChatUtil.message("The pickaxe is worn. Mining for experience now.");
        } else if (repairing && left >= mineAt.getValue()) {
            repairing = false;
            target = null;
            ChatUtil.message("The pickaxe has healed. Back to the ore.");
        }
    }

    private boolean wanted(BlockPos pos) {
        Block block = BlockUtil.state(pos).getBlock();
        return (repairing ? repairBlocks : targetBlocks).contains(block);
    }

    // The nearest wanted ore in range. A scan is not cheap and only runs now and then.
    private boolean scan() {
        if (scanTimer-- > 0) {
            return false;
        }
        scanTimer = SCAN_TICKS;
        for (BlockPos pos : BlockUtil.positionsWithin(scanRange.getValue())) {
            if (wanted(pos) && !shunned.containsKey(pos)) {
                target = pos.immutable();
                return true;
            }
        }
        return false;
    }

    private void forgetShunned() {
        int now = mc.player.tickCount;
        shunned.values().removeIf(when -> now - when > SHUN_TICKS || now < when);
    }

    private boolean holdPickaxe() {
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(ItemTags.PICKAXES)
            && ItemUtil.enchantLevel(Enchantments.MENDING, stack) > 0
            && ItemUtil.enchantLevel(Enchantments.SILK_TOUCH, stack) == 0);
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        return true;
    }

    // Full means no empty slot and no part stack of anything the ore drops.
    private boolean isFull() {
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                return false;
            }
            if (targetItems.contains(stack.getItem()) && stack.getCount() < stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    private void onFull() {
        BlockMiner.release();
        switch (whenFull.getValue()) {
            case STOP -> {
                ChatUtil.message("The bag is full.");
                setEnabled(false);
            }
            case LOG_OUT -> logOut();
            case WALK_HOME, WALK_HOME_AND_LOG_OUT -> {
                headingHome = true;
                target = null;
                trip.start(home, 0);
                ChatUtil.message("The bag is full. Walking home.");
            }
        }
    }

    private void walkHome() {
        switch (trip.tick()) {
            case ARRIVED -> {
                if (whenFull.is(WhenFull.WALK_HOME_AND_LOG_OUT)) {
                    logOut();
                } else {
                    ChatUtil.message("Home with a full bag.");
                    setEnabled(false);
                }
            }
            case FAILED -> {
                ChatUtil.error("Could not find the way home.");
                setEnabled(false);
            }
            default -> {
            }
        }
    }

    private void logOut() {
        setEnabled(false);
        mc.player.connection.getConnection().disconnect(
            Component.literal("§b[§3Offline§b] §fInfinityMiner filled the bag."));
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (render.isOn() && target != null) {
            targetBox.draw(event.getBatch(), target, true);
        }
    }
}
