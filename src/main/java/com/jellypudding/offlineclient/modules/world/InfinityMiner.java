package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.path.MiningTrip;
import com.jellypudding.offlineclient.path.PathWalker;
import com.jellypudding.offlineclient.path.Trip;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

// Mines for as long as the pickaxe lasts. When it wears down the bot swaps to
// ore that drops experience and lets Mending heal it before going back.
public final class InfinityMiner extends Module {

    public enum WhenFull { STOP, WALK_HOME, LOG_OUT, WALK_HOME_AND_LOG_OUT }

    private static final int SCAN_TICKS = 40;

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
        "How far around you ore is looked for.", 16, 4, 48, 1, " blocks").min(2);
    private final EnumSetting<MiningTrip.Movement> movement = MiningTrip.movementSetting();
    private final BoolSetting collectDrops = new BoolSetting("Collect drops",
        "Walks over what each ore dropped before it moves on.", true)
        .under(movement, MiningTrip.Movement.WALK);
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

    private final MiningTrip miner = new MiningTrip();
    private final Trip homeTrip = new Trip();
    private final SlotSwap slots = new SlotSwap();

    private BlockPos home;
    private boolean repairing;
    private boolean headingHome;
    private int scanTimer;

    public InfinityMiner() {
        super("InfinityMiner", "Mines ore for ever and lets Mending heal the pickaxe on the way.",
            Category.WORLD);
        addSettings(targetBlocks, targetItems, repairBlocks, repairAt, mineAt, scanRange, movement,
            collectDrops, whenFull, rotate, render);
        addSettings(targetBox.settings());
        searchTags("infinity miner", "auto mine", "mending", "ore bot");
        homeTrip.finder().breakBlocks(true);
        homeTrip.walker().turn(PathWalker.Turn.CLIENT);
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
        repairing = false;
        headingHome = false;
        scanTimer = 0;
        miner.reset();
        home = inGame() ? mc.player.blockPosition() : null;
    }

    @Override
    protected void onDisable() {
        miner.stop();
        homeTrip.stop();
        slots.restoreIfMine();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || mc.gui.screen() != null) {
            return;
        }
        // Switched on from the menu before a world was open.
        if (home == null) {
            home = mc.player.blockPosition();
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
            disable("InfinityMiner needs a Mending pickaxe without Silk Touch in the hotbar.");
            return;
        }
        if (repairAt.getValue() >= mineAt.getValue()) {
            disable("Repair at has to be lower than Mine at.");
            return;
        }
        updateRepairing();
        boolean walk = movement.is(MiningTrip.Movement.WALK);
        miner.collect(collectDrops.isOn())
            .tick(this::wanted, walk ? this::scan : () -> miner.nearestInReach(this::wanted), rotate.isOn(), walk);
    }

    // A worn pickaxe swaps the target to experience ore until it is healthy again. The block
    // it was on then fails the test and is let go.
    private void updateRepairing() {
        ItemStack pick = mc.player.getMainHandItem();
        double left = ItemUtil.durabilityPercent(pick);
        if (!repairing && left <= repairAt.getValue()) {
            repairing = true;
            ChatUtil.message("The pickaxe is worn. Mining for experience now.");
        } else if (repairing && left >= mineAt.getValue()) {
            repairing = false;
            ChatUtil.message("The pickaxe has healed. Back to the ore.");
        }
    }

    private boolean wanted(BlockPos pos) {
        Block block = BlockUtil.state(pos).getBlock();
        return (repairing ? repairBlocks : targetBlocks).contains(block);
    }

    // The nearest wanted ore in range. A scan is not cheap and only runs now and then.
    private BlockPos scan() {
        if (scanTimer-- > 0) {
            return null;
        }
        scanTimer = SCAN_TICKS;
        for (BlockPos pos : BlockUtil.positionsWithin(scanRange.getValue())) {
            if (wanted(pos) && !miner.shuns(pos)) {
                return pos.immutable();
            }
        }
        return null;
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
        miner.stop();
        switch (whenFull.getValue()) {
            case STOP -> {
                ChatUtil.message("The bag is full.");
                setEnabled(false);
            }
            case LOG_OUT -> logOut();
            case WALK_HOME, WALK_HOME_AND_LOG_OUT -> {
                headingHome = true;
                homeTrip.start(home, 0);
                ChatUtil.message("The bag is full. Walking home.");
            }
        }
    }

    private void walkHome() {
        switch (homeTrip.tick()) {
            case ARRIVED -> {
                if (whenFull.is(WhenFull.WALK_HOME_AND_LOG_OUT)) {
                    logOut();
                } else {
                    ChatUtil.message("Home with a full bag.");
                    setEnabled(false);
                }
            }
            case FAILED -> {
                disable("Could not find the way home.");
            }
            default -> {
            }
        }
    }

    private void logOut() {
        setEnabled(false);
        ChatUtil.leaveServer("InfinityMiner filled the bag.");
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        BlockPos target = miner.target();
        if (render.isOn() && target != null) {
            targetBox.draw(event.getBatch(), target, true);
        }
    }
}
