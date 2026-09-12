package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.mixin.MultiPlayerGameModeAccessor;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

// Cuts the ripe crops around you and puts the seeds back in the ground. It
// never walks anywhere and only farms what is already in reach.
public final class AutoFarm extends Module {

    // A block test that needs the state as well as the position.
    private interface PlantTest {
        boolean matches(BlockPos pos, BlockState state);
    }

    // One kind of plant. The spot test marks where a seed belongs and the mine
    // and interact tests say when the grown part is ready to take.
    private record Plant(String name, Item seed, PlantTest spot, Predicate<BlockPos> surface,
                         PlantTest mine, PlantTest interact) {
    }

    private static final PlantTest NEVER = (pos, state) -> false;

    private static final List<Plant> PLANTS = List.of(
        new Plant("Amethyst", Items.BUDDING_AMETHYST,
            (pos, state) -> state.is(Blocks.BUDDING_AMETHYST), pos -> true,
            (pos, state) -> state.is(Blocks.AMETHYST_CLUSTER), NEVER),
        new Plant("Bamboo", Items.BAMBOO,
            (pos, state) -> (state.is(Blocks.BAMBOO) || state.is(Blocks.BAMBOO_SAPLING))
                && !below(pos).is(Blocks.BAMBOO) && !below(pos).is(Blocks.BAMBOO_SAPLING)
                && below(pos).is(BlockTags.SUPPORTS_BAMBOO),
            pos -> below(pos).is(BlockTags.SUPPORTS_BAMBOO),
            (pos, state) -> state.is(Blocks.BAMBOO) && isSpot("Bamboo", pos.below()), NEVER),
        new Plant("Beetroots", Items.BEETROOT_SEEDS,
            (pos, state) -> state.is(Blocks.BEETROOTS), AutoFarm::cropSurface,
            AutoFarm::ripeCrop, NEVER),
        new Plant("Cactus", Items.CACTUS,
            (pos, state) -> state.is(Blocks.CACTUS) && !below(pos).is(Blocks.CACTUS)
                && cactusSurface(pos),
            AutoFarm::cactusSurface,
            (pos, state) -> (state.is(Blocks.CACTUS) || state.is(Blocks.CACTUS_FLOWER))
                && isSpot("Cactus", pos.below()), NEVER),
        new Plant("Carrots", Items.CARROT,
            (pos, state) -> state.is(Blocks.CARROTS), AutoFarm::cropSurface,
            AutoFarm::ripeCrop, NEVER),
        new Plant("Chorus plants", Items.CHORUS_FLOWER,
            (pos, state) -> (state.is(Blocks.CHORUS_FLOWER) || state.is(Blocks.CHORUS_PLANT))
                && below(pos).is(BlockTags.SUPPORTS_CHORUS_FLOWER),
            pos -> below(pos).is(BlockTags.SUPPORTS_CHORUS_FLOWER),
            AutoFarm::ripeChorus, NEVER),
        new Plant("Cocoa beans", Items.COCOA_BEANS,
            (pos, state) -> state.getBlock() instanceof CocoaBlock, AutoFarm::cocoaSurface,
            (pos, state) -> state.getBlock() instanceof CocoaBlock
                && state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE, NEVER),
        new Plant("Glow berries", Items.GLOW_BERRIES,
            (pos, state) -> state.getBlock() instanceof CaveVines && vineCeiling(pos),
            AutoFarm::vineCeiling,
            (pos, state) -> state.getBlock() instanceof CaveVines && !vineCeiling(pos),
            (pos, state) -> state.getBlock() instanceof CaveVines
                && CaveVines.hasGlowBerries(state) && vineCeiling(pos)),
        new Plant("Kelp", Items.KELP,
            (pos, state) -> (state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT))
                && !below(pos).is(Blocks.KELP) && !below(pos).is(Blocks.KELP_PLANT)
                && kelpSurface(pos),
            AutoFarm::kelpSurface,
            (pos, state) -> (state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT))
                && isSpot("Kelp", pos.below()), NEVER),
        new Plant("Melons", Items.MELON_SEEDS,
            (pos, state) -> state.is(Blocks.MELON_STEM) || state.is(Blocks.ATTACHED_MELON_STEM),
            pos -> below(pos).is(BlockTags.SUPPORTS_MELON_STEM),
            (pos, state) -> state.is(Blocks.MELON), NEVER),
        new Plant("Nether wart", Items.NETHER_WART,
            (pos, state) -> state.getBlock() instanceof NetherWartBlock,
            pos -> below(pos).is(BlockTags.SUPPORTS_NETHER_WART),
            (pos, state) -> state.getBlock() instanceof NetherWartBlock
                && state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE, NEVER),
        new Plant("Pitcher plants", Items.PITCHER_POD,
            (pos, state) -> state.is(Blocks.PITCHER_CROP)
                && state.getValue(PitcherCropBlock.HALF) == DoubleBlockHalf.LOWER
                && cropSurface(pos),
            AutoFarm::cropSurface,
            (pos, state) -> state.is(Blocks.PITCHER_CROP)
                && state.getValue(PitcherCropBlock.AGE) >= PitcherCropBlock.MAX_AGE, NEVER),
        new Plant("Potatoes", Items.POTATO,
            (pos, state) -> state.is(Blocks.POTATOES), AutoFarm::cropSurface,
            AutoFarm::ripeCrop, NEVER),
        new Plant("Pumpkins", Items.PUMPKIN_SEEDS,
            (pos, state) -> state.is(Blocks.PUMPKIN_STEM) || state.is(Blocks.ATTACHED_PUMPKIN_STEM),
            pos -> below(pos).is(BlockTags.SUPPORTS_PUMPKIN_STEM),
            (pos, state) -> state.is(Blocks.PUMPKIN), NEVER),
        new Plant("Sea pickles", Items.SEA_PICKLE,
            (pos, state) -> state.getBlock() instanceof SeaPickleBlock && pickleSurface(pos),
            AutoFarm::pickleSurface,
            (pos, state) -> state.getBlock() instanceof SeaPickleBlock
                && state.getValue(SeaPickleBlock.PICKLES) > 1, NEVER),
        new Plant("Sugar cane", Items.SUGAR_CANE,
            (pos, state) -> state.is(Blocks.SUGAR_CANE) && !below(pos).is(Blocks.SUGAR_CANE)
                && caneSurface(pos),
            AutoFarm::caneSurface,
            (pos, state) -> state.is(Blocks.SUGAR_CANE) && isSpot("Sugar cane", pos.below()),
            NEVER),
        new Plant("Sweet berries", Items.SWEET_BERRIES,
            (pos, state) -> state.getBlock() instanceof SweetBerryBushBlock && berrySurface(pos),
            AutoFarm::berrySurface, NEVER,
            (pos, state) -> state.getBlock() instanceof SweetBerryBushBlock
                && state.getValue(SweetBerryBushBlock.AGE) > 1),
        new Plant("Torchflowers", Items.TORCHFLOWER_SEEDS,
            (pos, state) -> (state.is(Blocks.TORCHFLOWER) || state.is(Blocks.TORCHFLOWER_CROP))
                && cropSurface(pos),
            AutoFarm::cropSurface,
            (pos, state) -> state.is(Blocks.TORCHFLOWER), NEVER),
        new Plant("Twisting vines", Items.TWISTING_VINES,
            (pos, state) -> isTwisting(state) && twistingFloor(pos), AutoFarm::twistingFloor,
            (pos, state) -> isTwisting(state) && !twistingFloor(pos), NEVER),
        new Plant("Weeping vines", Items.WEEPING_VINES,
            (pos, state) -> isWeeping(state) && weepingCeiling(pos), AutoFarm::weepingCeiling,
            (pos, state) -> isWeeping(state) && !weepingCeiling(pos), NEVER),
        new Plant("Wheat", Items.WHEAT_SEEDS,
            (pos, state) -> state.is(Blocks.WHEAT), AutoFarm::cropSurface,
            AutoFarm::ripeCrop, NEVER));

    private static final List<String> PLANT_NAMES = PLANTS.stream().map(Plant::name).toList();

    // Vines grow fast enough to be a nuisance. They start switched off.
    private static final List<String> PLANTS_ON = PLANT_NAMES.stream()
        .filter(name -> !name.endsWith("vines")).toList();

    // Ticks before the same block is sent again.
    private static final int RETRY_TICKS = 10;

    // Ticks a slower block gets on top of its break time before it is given up on.
    private static final int SLOW_GRACE_TICKS = 20;

    // Ticks a cut spot waits for its seed.
    private static final int PLANT_TICKS = 60;

    // Vanilla repeats a held right click at this rate.
    private static final int USE_INTERVAL = 4;

    private static final AABB NODE = new AABB(BlockPos.ZERO).deflate(0.3);
    private static final AABB SHELL = new AABB(BlockPos.ZERO).deflate(1 / 16.0);

    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes to farm.", 4.5, 1, 6, 0.1).max(6);
    private final BoolSetting harvest = new BoolSetting("Harvest",
        "Takes the parts of a plant that have finished growing.", true);
    private final ChoiceListSetting harvestPlants = new ChoiceListSetting("Harvest plants",
        "Which plants to take from.", () -> PLANT_NAMES, PLANTS_ON)
        .under(harvest);
    private final BoolSetting replant = new BoolSetting("Replant",
        "Puts a seed back into every spot you just cleared.", true);
    private final ChoiceListSetting replantPlants = new ChoiceListSetting("Replant plants",
        "Which plants to put back.", () -> PLANT_NAMES, PLANTS_ON)
        .under(replant);
    private final BoolSetting bonemeal = new BoolSetting("Bonemeal",
        "Feeds bone meal to the crops that are still growing.", false);
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many crops to cut each tick.", 4, 1, 16, 1);
    private final BoolSetting lineOfSight = new BoolSetting("Line of sight",
        "Skip anything your eyes cannot see.", false);
    private final EnumSetting<FaceMode> faceTarget = FaceMode.setting(FaceMode.SERVER);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final BoolSetting drawSpots = new BoolSetting("Draw spots",
        "Marks the spots being tracked for replanting.", true);
    private final ColorSetting spotColor = new ColorSetting("Spot colour",
        "Colour of the tracked spots.", 180, 0.79f, 0.88f, false)
        .under(drawSpots);
    private final BoolSetting drawHarvest = new BoolSetting("Draw harvest",
        "Marks the plants that are ready to take and how far the break has got.", true);
    private final ColorSetting harvestColor = new ColorSetting("Harvest colour",
        "Colour of the plants that are ready.", 120, 0.79f, 0.88f, false)
        .under(drawHarvest);
    private final BoolSetting drawReplant = new BoolSetting("Draw replant",
        "Marks the empty spots waiting for a seed.", true);
    private final ColorSetting replantColor = new ColorSetting("Replant colour",
        "Colour of the spots waiting for a seed.", 0, 0.79f, 0.88f, false)
        .under(drawReplant);

    private final Map<BlockPos, Integer> attempted = new HashMap<>();
    private final Map<BlockPos, Plant> spots = new HashMap<>();
    private final Map<BlockPos, Integer> spotExpiry = new HashMap<>();

    private List<BlockPos> shownHarvest = List.of();
    private List<BlockPos> shownReplant = List.of();
    private BlockPos mining;

    private BlockPos slowPending;
    private int slowDeadline;
    private int lastUse;
    private int lastTick;

    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();

    public AutoFarm() {
        super("AutoFarm", "Harvests the ripe crops in reach and replants them.", Category.WORLD);
        addSettings(range, harvest, harvestPlants, replant, replantPlants, bonemeal, perTick,
            lineOfSight, faceTarget, swing, drawSpots, spotColor, drawHarvest, harvestColor,
            drawReplant, replantColor);
        searchTags("farm", "crops", "harvest", "replant", "wheat", "berries");
    }

    @Override
    public String getSuffix() {
        return range.getValueString();
    }

    // Break packets for anything slower than one hit share the one server slot.
    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
        loan.giveBack();
    }

    private void reset() {
        attempted.clear();
        spots.clear();
        spotExpiry.clear();
        shownHarvest = List.of();
        shownReplant = List.of();
        mining = null;
        slowPending = null;
        lastUse = 0;
        lastTick = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            loan.giveBack();
            return;
        }
        // A held attack or an open container means the player is busy by hand.
        if (InputUtil.physicallyHeld(mc.options.keyAttack) || mc.player.isUsingItem()
            || mc.gui.screen() != null) {
            loan.giveBack();
            return;
        }

        int now = mc.player.tickCount;
        // The tick count restarts on a respawn.
        if (now < lastTick) {
            reset();
        }
        lastTick = now;
        attempted.values().removeIf(expiry -> expiry <= now);
        spotExpiry.entrySet().removeIf(entry -> {
            boolean gone = entry.getValue() <= now;
            if (gone) {
                spots.remove(entry.getKey());
            }
            return gone;
        });
        if (slowPending != null && (now >= slowDeadline || !readyToMine(slowPending))) {
            slowPending = null;
        }

        List<BlockPos> scan = BlockUtil.positionsWithin(range.getValue());
        for (BlockPos pos : scan) {
            Plant plant = spotPlant(pos);
            if (plant != null) {
                spots.put(pos.immutable(), plant);
                spotExpiry.put(pos.immutable(), now + PLANT_TICKS);
            }
        }

        List<BlockPos> toMine = new ArrayList<>();
        List<BlockPos> toInteract = new ArrayList<>();
        List<BlockPos> toReplant = new ArrayList<>();
        sort(scan, toMine, toInteract, toReplant);
        shownHarvest = toMine;
        shownReplant = toReplant;

        boolean acted = replant.isOn() && plantSeeds(toReplant, now);
        if (!acted && harvest.isOn()) {
            acted = pickByHand(toInteract, now);
        }
        if (!acted && harvest.isOn()) {
            mineTick(toMine, now);
        } else {
            mining = null;
        }
        if (!acted && bonemeal.isOn()) {
            feedTick(scan, now);
        }
    }

    private void sort(List<BlockPos> scan, List<BlockPos> toMine, List<BlockPos> toInteract,
                      List<BlockPos> toReplant) {
        for (BlockPos pos : scan) {
            if (lineOfSight.isOn() && !BlockUtil.canSee(pos)) {
                continue;
            }
            BlockState state = BlockUtil.state(pos);
            if (state.canBeReplaced()) {
                Plant waiting = spots.get(pos);
                if (waiting != null && replantPlants.contains(waiting.name())
                    && waiting.surface().test(pos)) {
                    toReplant.add(pos.immutable());
                }
                continue;
            }
            for (Plant plant : PLANTS) {
                if (!harvestPlants.contains(plant.name())) {
                    continue;
                }
                if (plant.interact().matches(pos, state)) {
                    toInteract.add(pos.immutable());
                    break;
                }
                if (plant.mine().matches(pos, state)) {
                    toMine.add(pos.immutable());
                    break;
                }
            }
        }
    }

    // Creative with no turning can spam every crop at once. Anything else goes
    // block by block because only one angle rides the packet each tick.
    private void mineTick(List<BlockPos> toMine, int now) {
        boolean spam = mc.player.getAbilities().instabuild && faceTarget.is(FaceMode.OFF);
        int limit = spam ? toMine.size() : perTick.getInt();
        int sent = 0;
        mining = null;
        for (BlockPos pos : toMine) {
            if (sent >= limit) {
                break;
            }
            if (attempted.containsKey(pos)) {
                continue;
            }
            boolean instant = BlockUtil.canInstantBreak(pos);
            if (!instant && slowPending != null) {
                continue;
            }
            if (sent == 0) {
                mining = pos;
                faceTarget.getValue().face(BlockUtil.hitPoint(pos, BlockUtil.facingSide(pos)),
                    RotationPriority.MINE);
            }
            BlockMiner.breakInstantly(pos);
            if (instant) {
                attempted.put(pos, now + RETRY_TICKS);
            } else {
                slowPending = pos;
                slowDeadline = now + BlockUtil.breakTicks(pos) + SLOW_GRACE_TICKS;
                attempted.put(pos, slowDeadline);
            }
            sent++;
        }
        if (sent > 0) {
            swing.getValue().swing(InteractionHand.MAIN_HAND);
        }
    }

    // Berries and glow berries come off with a right click and leave the plant standing.
    private boolean pickByHand(List<BlockPos> toInteract, int now) {
        if (toInteract.isEmpty() || now - lastUse < USE_INTERVAL) {
            return false;
        }
        // A right click with bone meal would fertilise the bush instead.
        if (mc.player.getMainHandItem().is(Items.BONE_MEAL)) {
            int other = InventoryUtil.hotbarSlot(stack -> !stack.is(Items.BONE_MEAL));
            if (other == -1) {
                return false;
            }
            loan.select(other);
            return true;
        }
        for (BlockPos pos : toInteract) {
            Direction side = BlockUtil.facingSide(pos);
            faceTarget.getValue().face(BlockUtil.hitPoint(pos, side), RotationPriority.PLACE);
            if (BlockUtil.interact(pos, side)) {
                lastUse = now;
                return true;
            }
        }
        return false;
    }

    private boolean plantSeeds(List<BlockPos> toReplant, int now) {
        if (toReplant.isEmpty() || now - lastUse < USE_INTERVAL) {
            return false;
        }
        for (BlockPos pos : toReplant) {
            Plant plant = spots.get(pos);
            if (plant == null) {
                continue;
            }
            if (!holdSeed(plant.seed())) {
                continue;
            }
            faceTarget.getValue().face(Vec3.atCenterOf(pos), RotationPriority.PLACE);
            if (BlockUtil.placeAny(pos, false, false)) {
                swing.getValue().swing(InteractionHand.MAIN_HAND);
                spots.remove(pos);
                spotExpiry.remove(pos);
                lastUse = now;
            }
            return true;
        }
        return false;
    }

    // Takes the seed from anywhere in the inventory and conjures one in creative.
    private boolean holdSeed(Item seed) {
        if (mc.player.getMainHandItem().is(seed)) {
            return true;
        }
        int slot = InventoryUtil.findSlot(seed, InventoryUtil.WHOLE_INVENTORY);
        if (slot != -1) {
            return loan.select(slot) && mc.player.getMainHandItem().is(seed);
        }
        if (!mc.player.getAbilities().instabuild) {
            return false;
        }
        int free = InventoryUtil.freeHotbarSlot(InventoryUtil.selectedSlot());
        ItemStack stack = new ItemStack(seed);
        mc.player.getInventory().setSelectedSlot(free);
        mc.player.getInventory().setItem(free, stack);
        mc.gameMode.handleCreativeModeItemAdd(stack, InventoryUtil.networkSlot(free));
        return true;
    }

    private void feedTick(List<BlockPos> scan, int now) {
        if (now - lastUse < USE_INTERVAL) {
            return;
        }
        BlockPos target = findGrowing(scan);
        if (target == null) {
            return;
        }
        int slot = InventoryUtil.findSlot(Items.BONE_MEAL, InventoryUtil.WHOLE_INVENTORY);
        if (slot == -1 || !loan.select(slot)) {
            return;
        }
        Direction side = BlockUtil.facingSide(target);
        faceTarget.getValue().face(BlockUtil.hitPoint(target, side), RotationPriority.PLACE);
        if (BlockUtil.useOn(target, false, false)) {
            swing.getValue().swing(InteractionHand.MAIN_HAND);
            lastUse = now;
        }
    }

    private BlockPos findGrowing(List<BlockPos> scan) {
        for (BlockPos pos : scan) {
            BlockState state = BlockUtil.state(pos);
            if (state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) {
                return pos;
            }
            if (state.getBlock() instanceof CocoaBlock
                && state.getValue(CocoaBlock.AGE) < CocoaBlock.MAX_AGE) {
                return pos;
            }
        }
        return null;
    }

    private boolean readyToMine(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        for (Plant plant : PLANTS) {
            if (harvestPlants.contains(plant.name()) && plant.mine().matches(pos, state)) {
                return true;
            }
        }
        return false;
    }

    private Plant spotPlant(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        for (Plant plant : PLANTS) {
            if (plant.spot().matches(pos, state)) {
                return plant;
            }
        }
        return null;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        if (drawSpots.isOn()) {
            int colour = spotColor.getColor();
            for (BlockPos pos : spots.keySet()) {
                batch.outlineBox(NODE.move(pos), colour, false);
            }
        }
        if (drawHarvest.isOn()) {
            int colour = harvestColor.getColor();
            for (BlockPos pos : shownHarvest) {
                batch.outlineBox(SHELL.move(pos), colour, false);
            }
            drawProgress(batch, colour);
        }
        if (drawReplant.isOn()) {
            int colour = replantColor.getColor();
            for (BlockPos pos : shownReplant) {
                batch.outlineBox(SHELL.move(pos), colour, false);
            }
        }
    }

    // The break shell shrinks from the whole block down to nothing as it goes.
    private void drawProgress(DrawBatch batch, int colour) {
        if (mining == null || !(mc.gameMode instanceof MultiPlayerGameModeAccessor access)) {
            return;
        }
        float progress = access.offlineclient$destroyProgress();
        if (progress <= 0 || !mining.equals(access.offlineclient$destroyBlockPos())) {
            return;
        }
        batch.solidBox(new AABB(BlockPos.ZERO).deflate(progress * 0.5).move(mining), colour, false);
    }

    private static BlockState below(BlockPos pos) {
        return BlockUtil.state(pos.below());
    }

    // Whether the plant of that name would put a seed here.
    private static boolean isSpot(String name, BlockPos pos) {
        for (Plant plant : PLANTS) {
            if (plant.name().equals(name)) {
                return plant.spot().matches(pos, BlockUtil.state(pos));
            }
        }
        return false;
    }

    private static boolean ripeCrop(BlockPos pos, BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    private static boolean cropSurface(BlockPos pos) {
        return below(pos).is(BlockTags.SUPPORTS_CROPS);
    }

    private static boolean cocoaSurface(BlockPos pos) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (BlockUtil.state(pos.relative(side)).is(BlockTags.SUPPORTS_COCOA)) {
                return true;
            }
        }
        return false;
    }

    // A cactus needs clear sides or it snaps as soon as it grows.
    private static boolean cactusSurface(BlockPos pos) {
        if (!below(pos).is(BlockTags.SUPPORTS_CACTUS)) {
            return false;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockState neighbour = BlockUtil.state(pos.relative(side));
            if (BlockUtil.blocksMotion(neighbour) || neighbour.getFluidState().is(FluidTags.LAVA)) {
                return false;
            }
        }
        return true;
    }

    private static boolean caneSurface(BlockPos pos) {
        BlockPos floor = pos.below();
        if (!BlockUtil.state(floor).is(BlockTags.SUPPORTS_SUGAR_CANE)) {
            return false;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockState neighbour = BlockUtil.state(floor.relative(side));
            if (neighbour.getFluidState().is(FluidTags.SUPPORTS_SUGAR_CANE_ADJACENTLY)
                || neighbour.is(BlockTags.SUPPORTS_SUGAR_CANE_ADJACENTLY)) {
                return true;
            }
        }
        return false;
    }

    private static boolean kelpSurface(BlockPos pos) {
        FluidState fluid = BlockUtil.state(pos).getFluidState();
        if (!fluid.is(FluidTags.WATER) || fluid.getAmount() != 8) {
            return false;
        }
        BlockPos floor = pos.below();
        return !BlockUtil.state(floor).is(BlockTags.CANNOT_SUPPORT_KELP)
            && BlockUtil.state(floor).isFaceSturdy(OfflineClient.MC.level, floor, Direction.UP);
    }

    private static boolean pickleSurface(BlockPos pos) {
        if (!below(pos).is(BlockTags.CORAL_BLOCKS)) {
            return false;
        }
        FluidState fluid = BlockUtil.state(pos).getFluidState();
        return fluid.is(FluidTags.WATER) && fluid.getAmount() == 8;
    }

    private static boolean berrySurface(BlockPos pos) {
        return below(pos).is(BlockTags.SUPPORTS_VEGETATION);
    }

    // The topmost vine hangs from something solid rather than from more vine.
    private static boolean vineCeiling(BlockPos pos) {
        BlockPos ceiling = pos.above();
        BlockState state = BlockUtil.state(ceiling);
        return !(state.getBlock() instanceof CaveVines)
            && state.isFaceSturdy(OfflineClient.MC.level, ceiling, Direction.DOWN);
    }

    private static boolean isTwisting(BlockState state) {
        return state.is(Blocks.TWISTING_VINES) || state.is(Blocks.TWISTING_VINES_PLANT);
    }

    private static boolean twistingFloor(BlockPos pos) {
        BlockPos floor = pos.below();
        BlockState state = BlockUtil.state(floor);
        return !isTwisting(state)
            && state.isFaceSturdy(OfflineClient.MC.level, floor, Direction.UP);
    }

    private static boolean isWeeping(BlockState state) {
        return state.is(Blocks.WEEPING_VINES) || state.is(Blocks.WEEPING_VINES_PLANT);
    }

    private static boolean weepingCeiling(BlockPos pos) {
        BlockPos ceiling = pos.above();
        BlockState state = BlockUtil.state(ceiling);
        return !isWeeping(state)
            && state.isFaceSturdy(OfflineClient.MC.level, ceiling, Direction.DOWN);
    }

    // A flower is done when it stops growing. A stem is only cut once every
    // flower it holds up has been taken.
    private static boolean ripeChorus(BlockPos pos, BlockState state) {
        if (state.is(Blocks.CHORUS_FLOWER)) {
            return state.getValueOrElse(ChorusFlowerBlock.AGE, 0) == ChorusFlowerBlock.DEAD_AGE
                || !BlockUtil.state(pos.above()).isAir();
        }
        return state.is(Blocks.CHORUS_PLANT) && !holdsFlower(pos, state, new HashSet<>());
    }

    private static boolean holdsFlower(BlockPos pos, BlockState state, Set<BlockPos> seen) {
        // A plant this large is left alone rather than walked in full.
        if (seen.size() > 1000) {
            return true;
        }
        if (!seen.add(pos.immutable())) {
            return false;
        }
        for (Direction side : Direction.values()) {
            if (side == Direction.DOWN
                || !state.getValueOrElse(PipeBlock.PROPERTY_BY_DIRECTION.get(side), false)) {
                continue;
            }
            BlockPos next = pos.relative(side);
            BlockState nextState = BlockUtil.state(next);
            if (nextState.is(Blocks.CHORUS_FLOWER)) {
                return true;
            }
            if (!nextState.is(Blocks.CHORUS_PLANT)) {
                continue;
            }
            // A sideways branch that also reaches down hangs off the main stem.
            if (side.getAxis().isHorizontal()
                && nextState.getValueOrElse(PipeBlock.DOWN, false)) {
                continue;
            }
            if (holdsFlower(next, nextState, seen)) {
                return true;
            }
        }
        return false;
    }
}
