package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.player.ShulkerPacker;
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
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Leave;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.PlaceWatch;
import com.jellypudding.offlineclient.util.PlacedBox;
import com.jellypudding.offlineclient.util.Recipes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Mines for as long as the pickaxe lasts. When it wears down the bot swaps to
// ore that drops experience and lets Mending heal it before going back. A full bag is made
// lighter first by throwing away and crafting and packing. Only when none of that makes
// room does the bot do what When done says.
public final class InfinityMiner extends Module {

    public enum WhenDone { STOP, WALK_HOME, LOG_OUT, WALK_HOME_AND_LOG_OUT }

    public enum PackInto { NOTHING, SHULKER_BOXES, ENDER_CHEST }

    // The ways a full bag is made lighter in the order they are tried.
    private enum Relief { THROW, CRAFT, PACK }

    private static final int SCAN_TICKS = 40;

    // A storage block such as a block of diamond takes nine of its item.
    private static final int BLOCK_OF = 9;

    private static final int STACK = 64;

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
        "Pickaxe durability that starts the repair.", 20, 1, 99, 1, "%").max(99);
    private final NumberSetting mineAt = new NumberSetting("Mine at",
        "Pickaxe durability that ends the repair.", 70, 1, 99, 1, "%").max(99);
    private final NumberSetting scanRange = new NumberSetting("Scan range",
        "How far around you ore is looked for.", 16, 4, 16, 1, " blocks").min(2).max(BlockUtil.MAX_SCAN_RANGE);
    private final BoolSetting heightBand = new BoolSetting("Height band",
        "Only mines ore between the two heights below.", false);
    private final NumberSetting lowest = new NumberSetting("Lowest height",
        "Ore below this height is left alone.", -64, -64, 320, 1)
        .min(DimensionType.MIN_Y).max(DimensionType.MAX_Y)
        .under(heightBand);
    private final NumberSetting highest = new NumberSetting("Highest height",
        "Ore above this height is left alone.", 16, -64, 320, 1)
        .min(DimensionType.MIN_Y).max(DimensionType.MAX_Y)
        .under(heightBand);
    private final NumberSetting stayWithin = new NumberSetting("Stay within",
        "Ore further than this from where you switched the bot on is left alone. Nought sets no limit.",
        0, 0, 512, 8, " blocks");
    private final EnumSetting<MiningTrip.Movement> movement = MiningTrip.movementSetting();
    private final BoolSetting collectDrops = new BoolSetting("Collect drops",
        "Walks over what each ore dropped before it moves on.", true)
        .under(movement, MiningTrip.Movement.WALK);
    private final RegistryListSetting<Item> throwAway = new RegistryListSetting<>("Throw away",
        "Items thrown on the ground once the bag is full. Click to pick them.", BuiltInRegistries.ITEM,
        List.of());
    private final BoolSetting craftBlocks = new BoolSetting("Craft into blocks",
        "Once the bag is full each target item that fills three slots or more is crafted into its block"
            + " at a crafting table in reach or one you carry. A table it puts down is mined back.", false);
    private final EnumSetting<PackInto> packInto = new EnumSetting<>("Pack into",
        "Where what was mined goes once the bag is full.", PackInto.NOTHING)
        .describe(PackInto.NOTHING, "It all stays in your inventory.")
        .describe(PackInto.SHULKER_BOXES, "The target items and their blocks go into the shulker boxes you"
            + " carry the way ShulkerPacker packs.")
        .describe(PackInto.ENDER_CHEST, "An ender chest from your hotbar is set down and takes the target"
            + " items and their blocks. It only comes back with a Silk Touch pickaxe in your hotbar.");
    private final NumberSetting stopAfter = new NumberSetting("Stop after",
        "Stacks of target items to mine before the bot does what When done says. Nought never stops it.",
        0, 0, 64, 1, " stacks");
    private final EnumSetting<WhenDone> whenDone = new EnumSetting<>("When done",
        "What happens once the bag is full with nowhere left to put it or the amount is mined.", WhenDone.STOP)
        .describe(WhenDone.STOP, "Switches off where you stand.")
        .describe(WhenDone.WALK_HOME, "Walks back to where you switched it on.")
        .describe(WhenDone.LOG_OUT, "Leaves the server where you stand.")
        .describe(WhenDone.WALK_HOME_AND_LOG_OUT, "Walks back to the start and leaves the server there.");
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns towards each block on the server side.", true);
    private final BoolSetting render = new BoolSetting("Show target",
        "Outlines the block being walked to.", true);
    private final BoxStyle targetBox = new BoxStyle(BoxStyle.Shape.BOTH, 180).under(render);

    private final MiningTrip miner = new MiningTrip().ownTool(true);
    private final Trip homeTrip = new Trip();
    private final SlotSwap slots = new SlotSwap();
    private final PlacedBox chest = new PlacedBox(slots);
    private final MenuClicks.Clicked clicked = new MenuClicks.Clicked();
    private final PlaceWatch place = new PlaceWatch();

    private BlockPos home;
    private boolean repairing;
    private boolean headingHome;
    private int scanTimer;
    // What has been tried on the bag since it last had room. Each way gets one go.
    private final Set<Relief> tried = EnumSet.noneOf(Relief.class);
    // The way under way. Null whilst the bot mines.
    private Relief easing;
    // What nine of a target item craft into as the recipe book had it when the bag last filled.
    private Set<Item> blocks = Set.of();
    // The target items carried a tick ago and how many have come in since the bot was switched on.
    private int carried;
    private int mined;
    // Why the bot is done. Said again at the end of the walk home.
    private String doneWhy = "";

    public InfinityMiner() {
        super("InfinityMiner", "Mines ore for ever and lets Mending heal the pickaxe on the way.",
            Category.WORLD);
        addSettings(targetBlocks, targetItems, repairBlocks, repairAt, mineAt, scanRange, heightBand, lowest,
            highest, stayWithin, movement, collectDrops, throwAway, craftBlocks, packInto, stopAfter, whenDone,
            rotate, render);
        addSettings(targetBox.settings());
        searchTags("infinity miner", "auto mine", "mending", "ore bot", "pack ore", "ore blocks");
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
        if (easing != null) {
            return "making room";
        }
        return repairing ? "repairing" : null;
    }

    @Override
    protected void onEnable() {
        repairing = false;
        headingHome = false;
        scanTimer = 0;
        easing = null;
        tried.clear();
        mined = 0;
        carried = inGame() ? targetCount() : 0;
        miner.reset();
        place.clear();
        home = inGame() ? mc.player.blockPosition() : null;
    }

    @Override
    protected void onDisable() {
        miner.stop();
        homeTrip.stop();
        chest.end();
        slots.restoreIfMine();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.player.isSpectator()) {
            return;
        }
        // A dead player still ticks and the server ignores every click it makes.
        if (mc.player.isDeadOrDying()) {
            disable("InfinityMiner stopped because you died.");
            return;
        }
        if (place.movedAway()) {
            disable("InfinityMiner stopped because you changed world.");
            return;
        }
        // The packing and crafting open screens of their own.
        if (easing != null) {
            ease();
            return;
        }
        if (mc.gui.screen() != null) {
            return;
        }
        // Switched on from the menu before a world was open.
        if (home == null) {
            home = mc.player.blockPosition();
            carried = targetCount();
        }
        if (headingHome) {
            walkHome();
            return;
        }
        // Mining and making room wait whilst something else has the hands. The walk home needs none.
        if (Hands.taken()) {
            miner.stop();
            slots.standAside();
            return;
        }
        countMined();
        if (stopAfter.getInt() > 0 && mined >= stopAfter.getInt() * STACK) {
            done("InfinityMiner has mined the amount you asked for.");
            return;
        }
        if (isFull()) {
            makeRoom();
            return;
        }
        tried.clear();
        if (!holdPickaxe()) {
            disable("InfinityMiner needs a Mending pickaxe without Silk Touch in the hotbar.");
            return;
        }
        if (repairAt.getValue() >= mineAt.getValue()) {
            disable("Repair at has to be lower than Mine at.");
            return;
        }
        if (heightBand.isOn() && lowest.getValue() > highest.getValue()) {
            disable("Lowest height cannot be above Highest height.");
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

    // Ore of the kind wanted just now inside the heights and the distance from the start.
    private boolean wanted(BlockPos pos) {
        Block block = BlockUtil.state(pos).getBlock();
        return (repairing ? repairBlocks : targetBlocks).contains(block) && inBand(pos.getY()) && nearHome(pos);
    }

    private boolean inBand(int height) {
        return !heightBand.isOn() || height >= lowest.getValue() && height <= highest.getValue();
    }

    private boolean nearHome(BlockPos pos) {
        double limit = stayWithin.getValue();
        return limit <= 0 || home == null || home.distSqr(pos) <= limit * limit;
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

    private int targetCount() {
        return InventoryUtil.count(stack -> targetItems.contains(stack.getItem()), InventoryUtil.WHOLE_INVENTORY);
    }

    // Only what comes in counts. Packing and crafting take target items away again.
    private void countMined() {
        int now = targetCount();
        mined += Math.max(0, now - carried);
        carried = now;
    }

    // Starts the next way to make room that has not had its go. With every way spent the bot is done.
    private void makeRoom() {
        miner.stop();
        slots.restoreIfMine();
        if (tried.add(Relief.THROW) && throwOne()) {
            easing = Relief.THROW;
        } else if (tried.add(Relief.CRAFT) && craftBlocks.isOn() && startCrafting()) {
            easing = Relief.CRAFT;
        } else if (tried.add(Relief.PACK) && startPacking()) {
            easing = Relief.PACK;
        } else {
            done("The bag is full.");
        }
    }

    // One tick of the way under way. The bot goes back to its ore once it is through.
    private void ease() {
        boolean busy = switch (easing) {
            case THROW -> throwOne();
            case CRAFT -> Modules.enabled(AutoCraft.class);
            case PACK -> chest.active() ? chest.tick(this::putAway, rotate.isOn(), rotate.isOn()) : Modules.packing();
        };
        if (!busy) {
            chest.end();
            slots.restoreIfMine();
            easing = null;
        }
    }

    // Throws one listed stack a tick. False once none is left.
    private boolean throwOne() {
        int slot = InventoryUtil.findSlot(stack -> !stack.isEmpty() && throwAway.contains(stack.getItem())
            && !Modules.keepsFromThrow(stack), InventoryUtil.WHOLE_INVENTORY);
        return slot != -1 && InventoryUtil.throwFrom(slot);
    }

    private boolean startCrafting() {
        AutoCraft crafter = Modules.get(AutoCraft.class);
        return crafter != null && crafter.craftOnly(this::makesRoom);
    }

    // The target item a recipe takes nine of and nothing else. Null for any other recipe.
    private Item nineOf(Recipes.Craft craft) {
        if (craft.ingredients().size() != BLOCK_OF || targetItems.contains(craft.result().getItem())) {
            return null;
        }
        for (Item item : targetItems.resolved()) {
            Holder<Item> target = BuiltInRegistries.ITEM.wrapAsHolder(item);
            if (craft.ingredients().stream().allMatch(ingredient -> ingredient.acceptsItem(target))) {
                return item;
            }
        }
        return null;
    }

    private boolean makesBlock(Recipes.Craft craft) {
        return nineOf(craft) != null;
    }

    // A block recipe whose crafting frees a slot. The blocks and the few items left over take
    // a slot each. A lone stack would fill two slots in place of one and leave no room for a
    // table the bot put down to come back.
    private boolean makesRoom(Recipes.Craft craft) {
        Item item = nineOf(craft);
        if (item == null) {
            return false;
        }
        int slots = 0;
        int total = 0;
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(item)) {
                slots++;
                total += stack.getCount();
            }
        }
        int blocks = total / BLOCK_OF;
        int after = (total % BLOCK_OF > 0 ? 1 : 0) + Mth.positiveCeilDiv(blocks, craft.result().getMaxStackSize());
        return blocks > 0 && after < slots;
    }

    private boolean startPacking() {
        if (packInto.is(PackInto.NOTHING)) {
            return false;
        }
        blocks = new HashSet<>();
        Recipes.known(item -> true).forEach((result, crafts) -> {
            if (crafts.stream().anyMatch(this::makesBlock)) {
                blocks.add(result);
            }
        });
        if (packInto.is(PackInto.SHULKER_BOXES)) {
            ShulkerPacker packer = Modules.get(ShulkerPacker.class);
            return packer != null && packer.packOnly(this::isLoot, "mined item");
        }
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.ENDER_CHEST));
        BlockPos spot = PlacedBox.spot();
        if (slot == -1 || spot == null) {
            ChatUtil.error(slot == -1 ? "InfinityMiner has no ender chest in the hotbar to pack into."
                : "There is no room beside you to set an ender chest down.");
            return false;
        }
        clicked.clear();
        return chest.setDown(slot, spot, rotate.isOn());
    }

    private boolean isLoot(ItemStack stack) {
        return targetItems.contains(stack.getItem()) || blocks.contains(stack.getItem());
    }

    // Moves one stack of what was mined into the open ender chest a tick. False once no more
    // goes in.
    private boolean putAway(AbstractContainerMenu menu) {
        List<Integer> theirs = MenuClicks.containerSlots(menu);
        for (int slot : MenuClicks.inventorySlots(menu)) {
            ItemStack stack = menu.slots.get(slot).getItem();
            if (isLoot(stack) && !clicked.thrownBack(slot, stack) && MenuClicks.room(menu, theirs, stack) > 0) {
                clicked.note(slot, stack);
                MenuClicks.quickMove(menu, slot);
                return true;
            }
        }
        return false;
    }

    private void done(String why) {
        miner.stop();
        doneWhy = why;
        switch (whenDone.getValue()) {
            case STOP -> {
                ChatUtil.message(why);
                setEnabled(false);
            }
            case LOG_OUT -> logOut();
            case WALK_HOME, WALK_HOME_AND_LOG_OUT -> {
                headingHome = true;
                homeTrip.start(home, 0);
                ChatUtil.message(why + " Walking home.");
            }
        }
    }

    private void walkHome() {
        switch (homeTrip.tick()) {
            case ARRIVED -> {
                if (whenDone.is(WhenDone.WALK_HOME_AND_LOG_OUT)) {
                    logOut();
                } else {
                    ChatUtil.message("Home again. " + doneWhy);
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
        Leave.QUIT.go("InfinityMiner stopped. " + doneWhy);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        BlockPos target = miner.target();
        if (render.isOn() && target != null) {
            targetBox.draw(event.getBatch(), target, true);
        }
    }
}
