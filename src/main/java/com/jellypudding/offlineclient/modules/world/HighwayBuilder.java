package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.mixin.MultiPlayerGameModeAccessor;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.combat.KillAura;
import com.jellypudding.offlineclient.modules.movement.NoKnockback;
import com.jellypudding.offlineclient.modules.movement.Speed;
import com.jellypudding.offlineclient.modules.player.FastBreak;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.AxisWalker;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.PacketBreaker;
import com.jellypudding.offlineclient.util.PlacedBox;
import com.jellypudding.offlineclient.util.ProjectileUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SlopePlan;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TickRate;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

// Builds a highway along one line doing the nearest job each tick. Liquid is plugged first and
// clearing goes before filling. Unsupported blocks go into the air. The line can climb or drop
// like stairs.
public final class HighwayBuilder extends Module {

    // Finished depths that must lie ahead before the module walks on.
    private static final int LEAD = 2;

    // How far ahead of the player the work reaches and how far behind it is checked.
    private static final int AHEAD = 6;
    private static final int BEHIND = 2;

    // Ticks of no progress before the module gives up walking on its own. Ten seconds.
    private static final int IDLE_LIMIT = 200;

    // A walking pace in blocks per tick whilst the view is left free.
    private static final double WALK_SPEED = 0.13;

    // A lane is picked only when it reaches with this to spare. The lane pull stops this short
    // of the lane at most.
    private static final double STEP_MARGIN = AxisWalker.ON_LANE;

    // A bend goes this many depths past the last block broken or placed. It and the depth
    // before it are still untouched.
    private static final int BEND_LEAD = 2;

    private static final int CRYSTAL_SHOTS = 3;
    private static final double CRYSTAL_NEAR = 12;
    private static final double CRYSTAL_FAR = 24;

    private static final int MAX_DRAWN = 512;

    // A torch on a railing only stands on it.
    private static final List<Direction> STAND_ONLY = List.of(Direction.DOWN);

    // Ticks a grind may run before it is abandoned.
    private static final int GRIND_LIMIT = 2400;

    public enum Movement { AUTO, MANUAL }

    public enum Rotation { NONE, MINE, PLACE, BOTH }

    public enum Sides { OPEN, RAILINGS, WALLS }

    public enum Slope {
        FLAT(0), UP(1), DOWN(-1);

        // Blocks the floor changes by for each block forward.
        private final int rise;

        Slope(int rise) {
            this.rise = rise;
        }
    }

    private enum Stage { NONE, GRIND }

    // Why the module is not simply building or walking. The word follows the distance.
    private enum Status {
        NONE(null), LAG("lag"), EATING("eating"), FIGHTING("fighting"), CRYSTAL("crystal"),
        RESTOCKING("restocking"), GRINDING("grinding"), STEPPING("stepping"),
        OUT_OF_REACH("out of reach"), BLOCKED("blocked"), NO_BLOCKS("no blocks");

        private final String word;

        Status(String word) {
            this.word = word;
        }
    }

    // A block the line cannot reach and the lane that reaches it.
    private record Step(BlockPos block, double lane) {
    }

    private final NumberSetting width = new NumberSetting("Width",
        "How wide the highway is.", 4, 1, 5, 1, " blocks").min(1).max(9);
    // A taller tunnel's top row is past the server's reach from the floor.
    private final NumberSetting height = new NumberSetting("Height",
        "How tall the tunnel is.", 3, 2, 5, 1, " blocks").min(2).max(8);
    private final EnumSetting<Slope> slope = new EnumSetting<>("Slope",
        "Turns the highway into stairs from just past the part already dug.", Slope.FLAT)
        .describe(Slope.FLAT, "Stays level at the height it has reached.")
        .describe(Slope.UP, "Climbs a block for every block forward.")
        .describe(Slope.DOWN, "Drops a block for every block forward.");
    private final KeybindSetting upKey = new KeybindSetting("Up key",
        "Starts a climb or levels the highway out again.", KeybindSetting.UNBOUND);
    private final KeybindSetting downKey = new KeybindSetting("Down key",
        "Starts a drop or levels the highway out again.", KeybindSetting.UNBOUND);
    private final BoolSetting levelOut = new BoolSetting("Level out",
        "Levels the highway out once its floor reaches a height.", false)
        .under(slope, Slope.UP, Slope.DOWN);
    private final NumberSetting levelHeight = new NumberSetting("Level height",
        "The floor height a climb or a drop stops at.", 120, -64, 320, 1)
        .min(DimensionType.MIN_Y).max(DimensionType.MAX_Y).under(levelOut);
    private final BoolSetting diagonal = new BoolSetting("Diagonal",
        "Lets the line run at forty five degrees when you face that way.", false);
    private final EnumSetting<Movement> movement = new EnumSetting<>("Movement",
        "Who walks.", Movement.AUTO)
        .describe(Movement.AUTO, "The module walks on once the stretch ahead is finished.")
        .describe(Movement.MANUAL, "You walk. The module builds around wherever you are along the line.");
    private final BoolSetting freeLook = new BoolSetting("Free look",
        "Leaves your view alone. The walking and building follow the line on their own.", false)
        .under(movement, Movement.AUTO);
    private final BoolSetting stepToReach = new BoolSetting("Step to reach",
        "Steps across the highway floor to reach a block you cannot reach from where you walk.", true)
        .under(movement, Movement.AUTO);
    private final BoolSetting skipUnreachable = new BoolSetting("Skip unreachable",
        "Walks on past a block nothing on the highway can reach. Off waits until you place or break it yourself.",
        true)
        .under(movement, Movement.AUTO);
    private final BoolSetting reLevel = new BoolSetting("Relevel",
        "Jumps and paves under your feet when you drop below the floor.", true);
    private final BoolSetting pauseOnLag = new BoolSetting("Pause on lag",
        "Waits whilst the server has stopped ticking.", true);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks allowed in the build. The one in your hand wins and otherwise the first in the hotbar.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.OBSIDIAN, Blocks.NETHERRACK, Blocks.BLACKSTONE, Blocks.BASALT,
            Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE));
    private final BoolSetting floor = new BoolSetting("Floor",
        "Fills the floor under the highway.", true);
    private final BoolSetting replaceFloor = new BoolSetting("Replace floor",
        "Digs out floor blocks that are not on the list and paves over them.", false)
        .under(floor);
    private final EnumSetting<Sides> sides = new EnumSetting<>("Sides",
        "What runs along each side of the highway.", Sides.OPEN)
        .describe(Sides.OPEN, "Nothing. The ground beside the highway is left alone.")
        .describe(Sides.RAILINGS, "A railing one block high on each side of the floor.")
        .describe(Sides.WALLS, "A wall as tall as the tunnel on each side.");
    private final BoolSetting replaceSides = new BoolSetting("Replace sides",
        "Digs out side blocks that are not on the list and builds them again.", false)
        .under(sides, Sides.RAILINGS, Sides.WALLS);
    private final BoolSetting clearAboveRailings = new BoolSetting("Clear above railings",
        "Digs out the space above each railing up to the top of the tunnel.", true)
        .under(sides, Sides.RAILINGS);
    private final BoolSetting ceiling = new BoolSetting("Ceiling",
        "Roofs the highway over.", false);
    private final BoolSetting replaceCeiling = new BoolSetting("Replace ceiling",
        "Digs out ceiling blocks that are not on the list and roofs over them.", false)
        .under(ceiling);
    private final BoolSetting fillLiquids = new BoolSetting("Fill liquids",
        "Plugs any water or lava in the stretch before anything else.", true);
    private final BoolSetting torches = new BoolSetting("Torches",
        "Puts a torch along the left side as you go. With railings it stands on the railing. Needs torches in your hotbar.",
        false);
    private final NumberSetting torchSpacing = new NumberSetting("Torch spacing",
        "Blocks between one torch and the next.", 8, 2, 16, 1, " blocks").min(1)
        .under(torches);
    // The torch never goes above the top row of the tallest tunnel.
    private final NumberSetting torchHeight = new NumberSetting("Torch height",
        "Blocks of air under each torch. Zero stands it on the floor and more hangs it on the side.",
        0, 0, 4, 1, " blocks").min(0).max(7)
        .under(torches, () -> torches.isOn() && !sides.is(Sides.RAILINGS));
    private final EnumSetting<Rotation> rotation = new EnumSetting<>("Rotation",
        "When a look packet is sent.", Rotation.BOTH)
        .describe(Rotation.NONE, "Never. The server keeps your own view.")
        .describe(Rotation.MINE, "Only towards blocks being broken.")
        .describe(Rotation.PLACE, "Only towards blocks being placed.")
        .describe(Rotation.BOTH, "Towards everything the module touches.");
    private final NumberSetting reach = new NumberSetting("Reach",
        "How far the nearest edge of a block may be from your eyes to break or place it. The server allows one block more than your normal reach.",
        4.5, 1, 5.5, 0.1, " blocks").min(1);
    private final NumberSetting breakDelay = new NumberSetting("Break delay",
        "Ticks between one block break and the next.", 0, 0, 20, 1, " ticks").min(0);
    private final NumberSetting breaksPerTick = new NumberSetting("Breaks per tick",
        "How many one hit blocks come out in a tick whilst no mining rotation is sent.",
        1, 1, 16, 1).min(1);
    private final BoolSetting doubleMine = new BoolSetting("Double mine",
        "Breaks a second block with packets whilst the first is mined by hand.", true);
    private final NumberSetting savePickaxes = new NumberSetting("Save pickaxes",
        "Turns the module off once you are down to this many pickaxes. Zero never stops.",
        1, 0, 36, 1).min(0).max(36);
    private final NumberSetting placeDelay = new NumberSetting("Place delay",
        "Ticks between one placement and the next.", 0, 0, 20, 1, " ticks").min(0);
    private final NumberSetting placementsPerTick = new NumberSetting("Placements per tick",
        "How many blocks may go down in one tick.", 1, 1, 8, 1).min(1);
    private final BoolSetting restock = new BoolSetting("Restock hotbar",
        "Moves blocks and a pickaxe up from your inventory when the hotbar runs dry.", true);
    private final NumberSetting inventoryDelay = new NumberSetting("Inventory delay",
        "Ticks between one inventory click and the next.", 3, 0, 20, 1, " ticks").min(0);
    private final BoolSetting searchShulkers = new BoolSetting("Search shulkers",
        "Sets a shulker box down behind you and takes the build blocks out of it.", true);
    private final BoolSetting searchEnderChest = new BoolSetting("Search ender chest",
        "Sets your ender chest down behind you and takes the build blocks out once the shulkers are empty. A Silk Touch pickaxe gets the chest back.",
        false);
    private final BoolSetting mineEnderChests = new BoolSetting("Mine ender chests",
        "With obsidian on the list and no blocks left an ender chest goes down behind you and is mined for obsidian.",
        true);
    private final NumberSetting saveEnderChests = new NumberSetting("Save ender chests",
        "Ender chests never used below this count.", 2, 0, 64, 1).min(0)
        .under(mineEnderChests);
    private final NumberSetting grindAmount = new NumberSetting("Grind amount",
        "How much obsidian one grind gathers before building goes on.", 64, 8, 256, 8).min(1)
        .under(mineEnderChests);
    private final BoolSetting grindBlockade = new BoolSetting("Grind blockade",
        "Walls you in with whatever blocks you have whilst the chests are ground.", true)
        .under(mineEnderChests);
    private final BoolSetting ejectShulkers = new BoolSetting("Eject useless shulkers",
        "Drops any shulker box holding no blocks or pickaxes or food.", true);
    private final NumberSetting emptySlots = new NumberSetting("Minimum empty slots",
        "Free slots to keep clear after a restock.", 3, 0, 9, 1).min(0);
    private final BoolSetting throwTrash = new BoolSetting("Throw out trash",
        "Turns round and drops the listed items to keep room free.", false);
    private final RegistryListSetting<Item> trash = new RegistryListSetting<>("Trash",
        "The items thrown out. Click to pick them.", BuiltInRegistries.ITEM,
        List.of(Items.NETHERRACK, Items.QUARTZ, Items.GOLD_NUGGET, Items.GOLDEN_SWORD,
            Items.GLOWSTONE_DUST, Items.GLOWSTONE, Items.BLACKSTONE, Items.BASALT,
            Items.GHAST_TEAR, Items.SOUL_SAND, Items.SOUL_SOIL, Items.ROTTEN_FLESH,
            Items.MAGMA_BLOCK))
        .under(throwTrash);
    private final BoolSetting crystalTraps = new BoolSetting("Shoot crystal traps",
        "Puts an arrow through any end crystal sitting in the way ahead.", true);
    private final BoolSetting stopWhenEmpty = new BoolSetting("Stop when empty",
        "Turns the module off once you run out of blocks.", true);
    private final BoolSetting disconnectOnStop = new BoolSetting("Disconnect on stop",
        "Leaves the server with the run totals whenever the module stops itself.", false);
    private final BoolSetting outline = new BoolSetting("Outline",
        "Draws the stretch being worked on.", true);
    private final ColorSetting outlineColor = new ColorSetting("Outline colour",
        "Colour of the outline.", 200, false).under(outline);
    private final BoolSetting renderMine = new BoolSetting("Show blocks to mine",
        "Draws every block still to come out of the stretch.", true);
    private final BoxStyle mineBox = new BoxStyle("Mine", BoxStyle.Shape.BOTH, 0)
        .under(renderMine);
    private final BoolSetting renderPlace = new BoolSetting("Show blocks to place",
        "Draws every block still to go into the stretch.", true);
    private final BoxStyle placeBox = new BoxStyle("Place", BoxStyle.Shape.BOTH, 220)
        .under(renderPlace);

    private final AxisWalker walker = new AxisWalker();
    private final SlotSwap slots = new SlotSwap();
    private final SlopePlan plan = new SlopePlan();
    // The shulker box or ender chest a restock sets down and empties.
    private final PlacedBox box = new PlacedBox(slots);
    // True once a container set down for a restock would not open. None goes down again until the
    // module is switched on again.
    private boolean containersRefused;

    private int idleTicks;
    private double bestTravelled;
    private boolean walking;

    private Status status = Status.NONE;
    // The block the walk last waited on. Named when the module gives up.
    private BlockPos waitingOn;
    // True whilst the player is held on another lane to reach a block.
    private boolean stepping;

    private BlockPos mineTarget;
    private BlockPos placeTarget;

    private int sinceBreak;
    private int sincePlace;
    private int sinceInventory;
    private int blocksBroken;
    private int blocksPlaced;

    // True for a tick in which a block was wanted and none was in the hotbar.
    private boolean outOfBlocks;

    // Blocks nothing on the highway reaches that the walk went past. Each one still undone
    // when it drops out of the stretch is counted.
    private final Set<BlockPos> passedBy = new HashSet<>();
    private int leftUndone;
    private boolean toldLeft;

    // Blocks still out of reach from the lane picked for them. No lane is stepped to for them again.
    private final Set<BlockPos> unsteppable = new HashSet<>();

    // The deepest depth this run has broken or placed at. Bends only go past it.
    private int frontier;
    // The slope the plan follows and the floor its last bend set out from.
    private Slope planned = Slope.FLAT;
    private int slopeFrom;
    private boolean toldDiagonal;

    // The block the hand was on last tick. Used only to count what really broke.
    private BlockPos wasMining;

    // Second blocks taken down with packets whilst the hand works the first.
    private final PacketBreaker spares = new PacketBreaker();

    private Stage stage = Stage.NONE;
    // Where the ender chests of a grind go down.
    private BlockPos grindPos;

    // How much obsidian the running grind is heading for.
    private int grindGoal;
    private int stageTicks;

    private final Set<Integer> ignoredCrystals = new HashSet<>();
    private Entity crystal;
    private int crystalShots;
    private boolean drawing;

    // Every block of the shell around the player this tick.
    private final List<BlockPos> stretch = new ArrayList<>();

    public HighwayBuilder() {
        super("HighwayBuilder", "Digs and builds a highway along one line.", Category.WORLD);
        addSettings(width, height, slope, upKey, downKey, levelOut, levelHeight, diagonal, movement,
            freeLook, stepToReach, skipUnreachable, reLevel, pauseOnLag, blocks, floor, replaceFloor,
            sides, replaceSides, clearAboveRailings, ceiling, replaceCeiling, fillLiquids, torches,
            torchSpacing, torchHeight, rotation, reach, breakDelay, breaksPerTick, doubleMine,
            savePickaxes, placeDelay, placementsPerTick, restock, inventoryDelay,
            searchShulkers, searchEnderChest, mineEnderChests, saveEnderChests, grindAmount,
            grindBlockade, ejectShulkers, emptySlots, throwTrash, trash,
            crystalTraps, stopWhenEmpty, disconnectOnStop, outline,
            outlineColor, renderMine);
        addSettings(mineBox.settings());
        addSettings(renderPlace);
        addSettings(placeBox.settings());
        searchTags("highway", "nether", "tunnel", "road", "railings", "walls", "slope");
    }

    @Override
    public String getSuffix() {
        if (!walker.isLocked()) {
            return null;
        }
        String word = status.word;
        if (word == null && planned != Slope.FLAT) {
            word = planned == Slope.UP ? "climbing" : "dropping";
        }
        return suffix(walker.heading() + " " + (int) Math.max(0, bestTravelled), word);
    }

    // Bringing this back at launch would start digging at once.
    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        walker.clear();
        slots.forget();
        mineTarget = null;
        placeTarget = null;
        spares.reset();
        crystal = null;
        drawing = false;
        ignoredCrystals.clear();
        blocksBroken = 0;
        blocksPlaced = 0;
        sinceBreak = 0;
        sincePlace = 0;
        sinceInventory = 0;
        stage = Stage.NONE;
        grindPos = null;
        box.end();
        containersRefused = false;
        if (inGame()) {
            lockAxis();
            warnAboutModules();
        }
    }

    @Override
    protected void onDisable() {
        // Blocks passed by and still undone count as left even before they drop behind.
        if (inGame() && walker.isLocked()) {
            countPassed(Double.POSITIVE_INFINITY);
        }
        BlockMiner.release();
        slots.restoreIfMine();
        stopWalking();
        releaseBow();
        walker.clear();
        mineTarget = null;
        placeTarget = null;
        spares.reset();
        stage = Stage.NONE;
        grindPos = null;
        box.end();
        stretch.clear();
        passedBy.clear();
        unsteppable.clear();
        if (blocksBroken > 0 || blocksPlaced > 0 || leftUndone > 0) {
            ChatUtil.message("§bHighwayBuilder §7" + totals());
        }
    }

    // What the run did.
    private String totals() {
        String left = leftUndone > 0 ? "§7 and left §f" + leftUndone + "§7 it could not reach" : "";
        return "covered §f" + (int) bestTravelled + "§7 blocks and broke §f" + blocksBroken
            + "§7 and placed §f" + blocksPlaced + left + "§7.";
    }

    private void warnAboutModules() {
        if (diagonal.isOn() && Modules.enabled(Speed.class)) {
            ChatUtil.error("Turn Speed off on a diagonal or you will drift off the line.");
        }
        if (!Modules.enabled(NoKnockback.class)) {
            ChatUtil.message("§bHighwayBuilder §7works better with §fNoKnockback§7 on.");
        }
        if (Modules.enabled(FastBreak.class)) {
            ChatUtil.message("§bHighwayBuilder §7works better with §fFastBreak§7 off."
                + " The server puts back blocks broken too early.");
        }
    }

    // A run starts level at your feet.
    private void lockAxis() {
        walker.lock(diagonal.isOn());
        plan.reset(walker.floorY());
        planned = Slope.FLAT;
        frontier = Integer.MIN_VALUE;
        toldDiagonal = false;
        stepping = false;
        idleTicks = 0;
        bestTravelled = 0;
        waitingOn = null;
        passedBy.clear();
        leftUndone = 0;
        toldLeft = false;
        unsteppable.clear();
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (mc.gui.screen() != null || !inGame()) {
            return;
        }
        if (event.presses(upKey)) {
            slope.setValue(slope.is(Slope.UP) ? Slope.FLAT : Slope.UP);
        } else if (event.presses(downKey)) {
            slope.setValue(slope.is(Slope.DOWN) ? Slope.FLAT : Slope.DOWN);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        countBreak();
        mineTarget = null;
        placeTarget = null;
        outOfBlocks = false;
        status = Status.NONE;
        if (!inGame()) {
            stopWalking();
            return;
        }
        if (!walker.isLocked()) {
            lockAxis();
        }
        status = pauseReason();
        if (status != Status.NONE) {
            stopWalking();
            BlockMiner.release();
            return;
        }
        if (!safeToWork()) {
            return;
        }
        boolean auto = movement.is(Movement.AUTO);
        // Manual hands the walking back rather than leaving the key held. Standing still is
        // then no reason to give up.
        if (!auto) {
            stopWalking();
            stepping = false;
            idleTicks = 0;
        }
        double here = walker.travelled();
        followSlope(here);
        if (!stepping) {
            walker.holdLane(homeLane());
        }
        walker.holdAxis(auto && !freeLook.isOn());
        trackProgress();

        gatherStretch(here - BEHIND, here + AHEAD);
        countPassed(here);
        if (shootCrystal()) {
            status = Status.CRYSTAL;
            idleTicks = 0;
            stopWalking();
            return;
        }
        if (levelUp(here)) {
            stopWalking();
            return;
        }
        if (tidyInventory()) {
            status = switch (stage) {
                case GRIND -> Status.GRINDING;
                case NONE -> box.active() ? Status.RESTOCKING : Status.NONE;
            };
            idleTicks = 0;
            stopWalking();
            return;
        }
        spares.tick(this::needsClearing);
        if (work()) {
            if (stepping) {
                status = Status.STEPPING;
            }
            if (auto) {
                stopWalking();
            }
            return;
        }
        // Running out of blocks turns the module off inside a fill.
        if (!isEnabled()) {
            return;
        }
        if (auto) {
            moveOn(here);
        }
    }

    // Anything else with a claim on the hands takes the tick.
    private Status pauseReason() {
        if (pauseOnLag.isOn() && TickRate.INSTANCE.stalled()) {
            return Status.LAG;
        }
        if (Modules.feeding(null)) {
            return Status.EATING;
        }
        KillAura aura = Modules.active(KillAura.class);
        return aura != null && aura.getTarget() != null ? Status.FIGHTING : Status.NONE;
    }

    private void countBreak() {
        if (wasMining != null && inGame() && BlockUtil.state(wasMining).isAir()) {
            blocksBroken++;
        }
        wasMining = null;
    }

    private boolean safeToWork() {
        if (mc.player.isSpectator() || mc.player.isPassenger()) {
            stop("HighwayBuilder stopped because you cannot build from there.");
            return false;
        }
        if (!reLevel.isOn() && walker.offFloor(floorHere(walker.travelled()))) {
            stop("HighwayBuilder stopped because you left the highway floor.");
            return false;
        }
        if (savePickaxes.getInt() > 0 && pickaxeCount() <= savePickaxes.getInt()) {
            stop("HighwayBuilder stopped because you are nearly out of pickaxes.");
            return false;
        }
        if (movement.is(Movement.AUTO) && idleTicks > IDLE_LIMIT) {
            stop(waitingOn == null ? "HighwayBuilder stopped because it could not get any further."
                : "HighwayBuilder stopped because it waited ten seconds on the block at "
                    + BlockUtil.text(waitingOn) + ".");
            return false;
        }
        return true;
    }

    private int pickaxeCount() {
        int total = 0;
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (mc.player.getInventory().getItem(i).is(ItemTags.PICKAXES)) {
                total++;
            }
        }
        return total;
    }

    private void trackProgress() {
        double travelled = walker.travelled();
        if (travelled > bestTravelled + 0.05) {
            bestTravelled = travelled;
            idleTicks = 0;
            waitingOn = null;
        } else {
            idleTicks++;
        }
    }

    // Bends the plan when Slope changes. A slope levels out at the level height or where it
    // cannot go on. A diagonal line stays level because its rounded depths would make jagged steps.
    private void followSlope(double here) {
        if (walker.isDiagonal()) {
            if (!slope.is(Slope.FLAT) && !toldDiagonal) {
                toldDiagonal = true;
                ChatUtil.message("§bHighwayBuilder §7keeps a diagonal highway level.");
            }
            return;
        }
        int depth = nextBend(here);
        if (!slope.is(planned)) {
            bendAt(depth, slope.getValue());
            String change = switch (planned) {
                case UP -> "climbs";
                case DOWN -> "drops";
                case FLAT -> "levels out";
            };
            ChatUtil.message("§bHighwayBuilder §7" + change + " from §f" + (depth - (int) Math.round(here))
                + "§7 blocks ahead.");
            return;
        }
        if (planned == Slope.FLAT) {
            return;
        }
        // The worked depth can jump several at once. Every depth the next bend could land on is
        // looked at before then.
        int last = (int) Math.round(here) + AHEAD + BEND_LEAD;
        for (; depth <= last; depth++) {
            String end = slopeEnd(depth);
            if (end != null) {
                bendAt(depth, Slope.FLAT);
                slope.setValue(Slope.FLAT);
                ChatUtil.message("§bHighwayBuilder §7levels out " + end + "§7.");
                return;
            }
        }
    }

    // The first depth a bend can still change. Everything nearer has been dug or built.
    private int nextBend(double here) {
        return Math.max(frontier, (int) Math.round(here)) + BEND_LEAD;
    }

    private void bendAt(int depth, Slope to) {
        slopeFrom = plan.floorAt(depth - 1);
        plan.bend(depth, to.rise);
        planned = to;
    }

    // Why the slope ends before this depth. Null whilst it goes on.
    private String slopeEnd(int depth) {
        if (reachedLevel(depth)) {
            return "at §f" + plan.floorAt(depth - 1);
        }
        int top = plan.roofAt(depth) + height.getInt();
        for (int lane = leftLane() - 1; lane <= rightLane() + 1; lane++) {
            BlockPos column = walker.blockAt(depth, lane, 0);
            for (int y = plan.floorAt(depth) - 1; y <= top; y++) {
                BlockPos pos = column.atY(y);
                // Open ground beside the tunnel never ends a slope.
                Part part = partOf(pos);
                if (part == Part.OUTSIDE || part == Part.SIDE) {
                    continue;
                }
                if (mc.level.isOutsideBuildHeight(y)) {
                    return "because the world ends there";
                }
                if (BlockUtil.state(pos).getDestroySpeed(mc.level, pos) < 0) {
                    return "because bedrock is in the way";
                }
            }
        }
        return null;
    }

    // True once the floor before this depth has come to the level height. A slope that set out
    // from beyond it never levels there.
    private boolean reachedLevel(int depth) {
        if (!levelOut.isOn()) {
            return false;
        }
        int goal = levelHeight.getInt();
        int last = plan.floorAt(depth - 1);
        return planned == Slope.UP ? slopeFrom <= goal && last >= goal : slopeFrom >= goal && last <= goal;
    }

    // The planned floor where you stand.
    private int floorHere(double here) {
        return plan.floorAt((int) Math.round(here));
    }

    // Every block of the tunnel and its shell between two depths along the line. The box
    // around the player is sieved by distance along and across it.
    private void gatherStretch(double from, double to) {
        stretch.clear();
        int span = AHEAD + width.getInt() + 2;
        BlockPos centre = mc.player.blockPosition();
        double nearEdge = leftLane() - 1.5;
        double farEdge = rightLane() + 1.5;
        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                int x = centre.getX() + dx;
                int z = centre.getZ() + dz;
                double along = walker.alongOf(x + 0.5, z + 0.5);
                if (along < from || along > to) {
                    continue;
                }
                double across = walker.acrossOf(x + 0.5, z + 0.5);
                if (across < nearEdge || across > farEdge) {
                    continue;
                }
                int depth = walker.depthOf(x + 0.5, z + 0.5);
                int top = plan.roofAt(depth) + height.getInt();
                for (int y = plan.floorAt(depth) - 1; y <= top; y++) {
                    stretch.add(new BlockPos(x, y, z));
                }
            }
        }
    }

    // Does the nearest job in reach across the stretch. Liquid goes first then clearing wins
    // over filling. True when the tick was spent.
    private boolean work() {
        if (fillLiquids.isOn()) {
            BlockPos plug = nearest(this::isLiquid);
            if (plug != null) {
                placeTarget = plug;
                return placeRound(plug);
            }
        }
        BlockPos dig = nearest(this::needsClearing);
        if (dig != null) {
            return mine(dig);
        }
        BlockMiner.release();
        BlockPos fill = nearest(this::needsFilling);
        if (fill != null) {
            placeTarget = fill;
            return placeRound(fill);
        }
        BlockPos torch = nearest(this::needsTorch);
        if (torch != null) {
            return placeTorch(torch);
        }
        slots.restoreIfMine();
        return false;
    }

    // Measured like the server to the nearest point of the block and kept within Reach.
    private boolean reaches(BlockPos pos) {
        return BlockUtil.reaches(pos, reach.getValue());
    }

    // A block was broken or placed. The idle count starts again and bends stay past it.
    private void worked(BlockPos pos) {
        idleTicks = 0;
        frontier = Math.max(frontier, walker.depthOf(pos));
    }

    private boolean mine(BlockPos dig) {
        if (sinceBreak < breakDelay.getInt()) {
            sinceBreak++;
            BlockMiner.release();
            return true;
        }
        mineTarget = dig;
        ItemUtil.selectBestTool(BlockUtil.state(dig), slots);
        // With no mining rotation the server takes the packets on their own.
        if (!rotatesToMine()) {
            return packetMine();
        }
        if (doubleMine.isOn()) {
            takeSpare(dig);
        }
        if (!BlockMiner.mineWithHeld(dig, true)) {
            mineTarget = null;
            return false;
        }
        worked(dig);
        wasMining = dig;
        sinceBreak = 0;
        return true;
    }

    // Sends the pair for a run of one hit blocks in the same tick.
    private boolean packetMine() {
        int sent = 0;
        for (BlockPos pos : stretch) {
            if (sent >= breaksPerTick.getInt()) {
                break;
            }
            if (!needsClearing(pos) || !reaches(pos) || !BlockUtil.canInstantBreak(pos)) {
                continue;
            }
            BlockMiner.breakInstantly(pos);
            worked(pos);
            blocksBroken++;
            sent++;
        }
        if (sent == 0 && mineTarget != null) {
            BlockMiner.breakInstantly(mineTarget);
            worked(mineTarget);
            wasMining = mineTarget;
            sent = 1;
        }
        sinceBreak = 0;
        return sent > 0;
    }

    // Sends a second block its start and stop at once. The server parks a slow one and finishes
    // it on its next tick. Every start restarts the server's count and a slow one takes its
    // target. A spare only goes out whilst the hand is between blocks with its own start to come.
    private void takeSpare(BlockPos busy) {
        MultiPlayerGameMode game = mc.gameMode;
        // The hand takes up its last block again without a start of its own.
        BlockPos last = ((MultiPlayerGameModeAccessor) game).offlineclient$destroyBlockPos();
        if (game.isDestroying() || busy.equals(last)) {
            return;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : stretch) {
            if (pos.equals(busy) || !needsClearing(pos) || !reaches(pos) || !spares.canSend(pos)) {
                continue;
            }
            double distance = BlockUtil.distanceTo(pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        if (best != null) {
            spares.send(best);
            worked(best);
            blocksBroken++;
        }
    }

    // The block in reach that passes the test with the nearest middle.
    private BlockPos nearest(Predicate<BlockPos> wanted) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : stretch) {
            if (!reaches(pos) || !wanted.test(pos)) {
                continue;
            }
            double distance = BlockUtil.distanceTo(pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    private enum Part { TUNNEL, FLOOR, WALL, ABOVE_WALL, CEILING, SIDE, OUTSIDE }

    // Which part of the highway a block belongs to by where its middle falls. The floor and the
    // top follow the plan at the block's depth.
    private Part partOf(BlockPos pos) {
        int depth = walker.depthOf(pos);
        int floorY = plan.floorAt(depth);
        int topY = plan.roofAt(depth) + height.getInt();
        int y = pos.getY();
        double across = walker.acrossOf(pos);
        if (across >= leftLane() - 0.5 && across <= rightLane() + 0.5) {
            if (y >= floorY && y < topY) {
                return Part.TUNNEL;
            }
            if (y == floorY - 1) {
                return Part.FLOOR;
            }
            return y == topY ? Part.CEILING : Part.OUTSIDE;
        }
        boolean beside = across >= leftLane() - 1.5 && across <= rightLane() + 1.5;
        if (!beside) {
            return Part.OUTSIDE;
        }
        // Open sides leave the ground beside the tunnel alone. Liquid in it is still plugged.
        if (sides.is(Sides.OPEN)) {
            return y >= floorY && y < topY ? Part.SIDE : Part.OUTSIDE;
        }
        // The blocks the sides stand on are part of the floor.
        if (y == floorY - 1) {
            return Part.FLOOR;
        }
        int sideTop = sides.is(Sides.RAILINGS) ? floorY + 1 : topY;
        if (y >= floorY && y < sideTop) {
            return Part.WALL;
        }
        if (y >= sideTop && y < topY) {
            return Part.ABOVE_WALL;
        }
        return y == topY && sides.is(Sides.WALLS) ? Part.CEILING : Part.OUTSIDE;
    }

    // Anything in the tunnel or a shell block off the list when that shell is replaced.
    private boolean needsClearing(BlockPos pos) {
        if (!BlockUtil.diggable(pos) || BlockUtil.isStandingOn(pos)) {
            return false;
        }
        Part part = partOf(pos);
        if (part == Part.TUNNEL) {
            // Torches light the way and never block it.
            return !BlockUtil.isTorch(BlockUtil.state(pos).getBlock());
        }
        if (part == Part.ABOVE_WALL) {
            return clearAboveRailings.isOn() && !BlockUtil.isTorch(BlockUtil.state(pos).getBlock())
                && !BlockUtil.holdsBackLiquid(pos);
        }
        if (allowed(BlockUtil.state(pos).getBlock())) {
            return false;
        }
        return switch (part) {
            case FLOOR -> floor.isOn() && replaceFloor.isOn();
            case WALL -> replaceSides.isOn();
            case CEILING -> ceiling.isOn() && replaceCeiling.isOn();
            default -> false;
        };
    }

    private boolean needsFilling(BlockPos pos) {
        if (!BlockUtil.isReplaceable(pos) || BlockUtil.intersectsPlayer(pos)) {
            return false;
        }
        return switch (partOf(pos)) {
            case FLOOR -> floor.isOn();
            case WALL -> true;
            case CEILING -> ceiling.isOn();
            default -> false;
        };
    }

    // Liquid anywhere in the shell or beside the tunnel is plugged whatever part it sits in.
    private boolean isLiquid(BlockPos pos) {
        if (mc.level.getFluidState(pos).isEmpty() || BlockUtil.intersectsPlayer(pos)) {
            return false;
        }
        return BlockUtil.isReplaceable(pos) && partOf(pos) != Part.OUTSIDE;
    }

    // A block still has to go in here.
    private boolean needsBlock(BlockPos pos) {
        return needsFilling(pos) || (fillLiquids.isOn() && isLiquid(pos));
    }

    // A block of the build still to come out or go in. Liquid is plugged whenever it comes in
    // reach but never holds the walk.
    private boolean pending(BlockPos pos) {
        return needsClearing(pos) || needsFilling(pos);
    }

    // Places up to the round size at the target and around it.
    private boolean placeRound(BlockPos first) {
        if (sincePlace < placeDelay.getInt()) {
            sincePlace++;
            return true;
        }
        if (!place(first)) {
            return false;
        }
        sincePlace = 0;
        int placed = 1;
        while (placed < placementsPerTick.getInt()) {
            BlockPos next = nearest(pos -> !pos.equals(first) && needsBlock(pos));
            if (next == null || !place(next)) {
                break;
            }
            placed++;
        }
        return true;
    }

    // A block goes against a neighbour in reach if there is one and into the air if there is not.
    private boolean place(BlockPos target) {
        int slot = BlockUtil.findBlockSlot(this::allowed);
        if (slot == -1) {
            outOfBlocks = true;
            if (stopWhenEmpty.isOn()) {
                stop("HighwayBuilder stopped because you ran out of blocks.");
            }
            return false;
        }
        BlockUtil.Placement click = BlockUtil.reachablePlacement(target, reach.getValue(), false);
        if (click == null) {
            return false;
        }
        slots.select(slot);
        if (!click.place(rotatesToPlace() ? FaceMode.SERVER : FaceMode.OFF, SwingMode.CLIENT)) {
            return false;
        }
        worked(target);
        blocksPlaced++;
        return true;
    }

    // The torch never sits above the top of the tunnel.
    private int torchUp() {
        return Math.min(torchHeight.getInt(), height.getInt() - 1);
    }

    // The torch spots sit along the left side every few blocks. With railings a torch stands on
    // the left railing. Otherwise it sits in the left lane at the chosen height and stands on the
    // floor or hangs on the side.
    private boolean needsTorch(BlockPos pos) {
        if (!torches.isOn()) {
            return false;
        }
        int depth = walker.depthOf(pos);
        if (depth <= 0 || depth % torchSpacing.getInt() != 0) {
            return false;
        }
        boolean railings = sides.is(Sides.RAILINGS);
        int lane = railings ? leftLane() - 1 : leftLane();
        int up = railings ? 1 : torchUp();
        double across = walker.acrossOf(pos);
        if (across < lane - 0.5 || across >= lane + 0.5 || pos.getY() != plan.floorAt(depth) + up) {
            return false;
        }
        return BlockUtil.isReplaceable(pos) && !BlockUtil.intersectsPlayer(pos) && torchSupport(pos) != null;
    }

    // A solid block the build keeps. Below first and then the sides. A railing torch only stands.
    private Direction torchSupport(BlockPos pos) {
        List<Direction> tries = sides.is(Sides.RAILINGS) ? STAND_ONLY : BlockUtil.BELOW_THEN_SIDES;
        for (Direction side : tries) {
            BlockPos against = pos.relative(side);
            if (BlockUtil.isSolid(against) && partOf(against) != Part.TUNNEL && !needsClearing(against)) {
                return side;
            }
        }
        return null;
    }

    private boolean placeTorch(BlockPos pos) {
        int slot = BlockUtil.findTorchSlot();
        if (slot == -1) {
            return false;
        }
        Direction support = torchSupport(pos);
        // The server measures the click to the block the torch goes against.
        if (support == null || !BlockUtil.serverReaches(pos.relative(support))) {
            return false;
        }
        slots.select(slot);
        boolean placed = BlockUtil.place(pos, support, rotatesToPlace(), true);
        slots.restoreIfMine();
        if (placed) {
            worked(pos);
        }
        return placed;
    }

    // Jumps and paves underfoot after a fall. The line keeps its planned height.
    private boolean levelUp(double here) {
        if (!reLevel.isOn()) {
            return false;
        }
        double drop = floorHere(here) - mc.player.getY();
        if (drop < 0.5) {
            return false;
        }
        if (mc.player.onGround()) {
            mc.player.jumpFromGround();
            return true;
        }
        BlockPos under = mc.player.blockPosition().below();
        if (mc.player.getDeltaMovement().y < 0 && BlockUtil.isReplaceable(under)) {
            place(under);
        }
        return true;
    }

    // Moves what the build needs up into the hotbar and drops what it does not.
    private boolean tidyInventory() {
        if (stage == Stage.GRIND) {
            return enderChestGrind();
        }
        if (box.active()) {
            return shulkerRestock();
        }
        if (sinceInventory < inventoryDelay.getInt()) {
            sinceInventory++;
            return false;
        }
        if (restock.isOn() && restockHotbar()) {
            return true;
        }
        if (searchShulkers.isOn() && !containersRefused && shulkerRestock()) {
            return true;
        }
        if (mineEnderChests.isOn() && enderChestGrind()) {
            return true;
        }
        if (searchEnderChest.isOn() && !containersRefused && enderChestRestock()) {
            return true;
        }
        if (ejectShulkers.isOn() && dropUselessShulker()) {
            return true;
        }
        return throwTrash.isOn() && dropTrash();
    }

    // Puts a shulker down behind you and empties it then mines it back.
    private boolean shulkerRestock() {
        if (!box.active()) {
            return startRestock();
        }
        boolean going = box.tick(this::emptyShulker, rotatesToPlace(), rotatesToMine());
        if (!going && box.wouldNotOpen()) {
            containersRefused = true;
            ChatUtil.error("HighwayBuilder could not open the container it set down."
                + " Restocking from containers stops.");
        }
        return going;
    }

    private boolean startRestock() {
        return setDownContainer(this::usefulShulker);
    }

    // Sets an ender chest down and empties it the way a shulker is emptied.
    private boolean enderChestRestock() {
        return setDownContainer(stack -> stack.is(Items.ENDER_CHEST));
    }

    // Puts the first matching container down behind you ready to be emptied.
    private boolean setDownContainer(Predicate<ItemStack> which) {
        if (hasBlocksSomewhere() || mc.player.getInventory().getFreeSlot() == -1) {
            return false;
        }
        int slot = InventoryUtil.findSlot(which, InventoryUtil.WHOLE_INVENTORY);
        if (slot == -1) {
            return false;
        }
        if (slot >= InventoryUtil.HOTBAR_SIZE) {
            return moveToHotbar(slot);
        }
        BlockPos spot = PlacedBox.spot();
        return spot != null && box.setDown(slot, spot, rotatesToPlace());
    }

    // Sets ender chests down behind you and mines them back for obsidian until enough is held.
    private boolean enderChestGrind() {
        if (stage == Stage.NONE) {
            if (hasBlocksSomewhere() || !allowed(Blocks.OBSIDIAN)
                || enderChests() <= saveEnderChests.getInt()) {
                return false;
            }
            BlockPos spot = PlacedBox.spot();
            if (spot == null) {
                return false;
            }
            grindPos = spot;
            grindGoal = obsidian() + grindAmount.getInt();
            stage = Stage.GRIND;
            stageTicks = 0;
            return true;
        }
        BlockState state = BlockUtil.state(grindPos);
        boolean chestDown = state.is(Blocks.ENDER_CHEST);
        if (++stageTicks > GRIND_LIMIT || obsidian() >= grindGoal
            || (!chestDown && enderChests() <= saveEnderChests.getInt())) {
            endGrind();
            return false;
        }
        if (grindBlockade.isOn() && buildBlockade()) {
            return true;
        }
        if (chestDown) {
            if (!holdPlainPickaxe(state)) {
                ChatUtil.error("HighwayBuilder needs a pickaxe without Silk Touch to grind ender chests.");
                endGrind();
                return false;
            }
            BlockMiner.mineWithHeld(grindPos, rotatesToMine());
            return true;
        }
        BlockMiner.release();
        if (!BlockUtil.isReplaceable(grindPos)) {
            endGrind();
            return false;
        }
        int slot = InventoryUtil.findSlot(Items.ENDER_CHEST, InventoryUtil.WHOLE_INVENTORY);
        if (slot >= InventoryUtil.HOTBAR_SIZE) {
            return moveToHotbar(slot);
        }
        slots.select(slot);
        BlockUtil.placeAny(grindPos, rotatesToPlace(), true);
        return true;
    }

    private void endGrind() {
        stage = Stage.NONE;
        grindPos = null;
        BlockMiner.release();
    }

    // Fills the sides round you at foot and head height apart from the chest spot.
    // True when a block went down this tick.
    private boolean buildBlockade() {
        BlockPos feet = mc.player.blockPosition();
        for (int up = 0; up < 2; up++) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos pos = feet.above(up).relative(side);
                if (pos.equals(grindPos) || !BlockUtil.isReplaceable(pos)) {
                    continue;
                }
                int slot = BlockUtil.findBlockSlot();
                if (slot == -1) {
                    return false;
                }
                slots.select(slot);
                return BlockUtil.placeAny(pos, rotatesToPlace(), true);
            }
        }
        return false;
    }

    // True once a pickaxe without Silk Touch is in hand. Only that breaks a chest into obsidian.
    private boolean holdPlainPickaxe(BlockState state) {
        int slot = ItemUtil.bestToolSlot(state, 1,
            stack -> ItemUtil.enchantLevel(Enchantments.SILK_TOUCH, stack) == 0, InventoryUtil.HOTBAR_SIZE);
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        return true;
    }

    private int enderChests() {
        return InventoryUtil.count(Items.ENDER_CHEST, InventoryUtil.WHOLE_INVENTORY);
    }

    private int obsidian() {
        return InventoryUtil.count(Items.OBSIDIAN, InventoryUtil.WHOLE_INVENTORY);
    }

    // Quick moves what the build needs out of the open container. False once nothing is left
    // to take or the inventory has no more room to spare.
    private boolean emptyShulker(AbstractContainerMenu menu) {
        if (sinceInventory < inventoryDelay.getInt()) {
            sinceInventory++;
            return true;
        }
        int containerSlots = menu.slots.size() - InventoryUtil.WHOLE_INVENTORY;
        if (containerSlots > 0 && freeSlots() > emptySlots.getInt()) {
            for (int i = 0; i < containerSlots; i++) {
                Slot slot = menu.slots.get(i);
                if (!slot.hasItem() || !neededItem(slot.getItem())) {
                    continue;
                }
                MenuClicks.quickMove(menu, i);
                sinceInventory = 0;
                return true;
            }
        }
        return false;
    }

    private boolean hasBlocksSomewhere() {
        return InventoryUtil.findSlot(stack -> stack.getItem() instanceof BlockItem item
            && allowed(item.getBlock()), InventoryUtil.WHOLE_INVENTORY) != -1;
    }

    private int freeSlots() {
        int free = 0;
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private boolean neededItem(ItemStack stack) {
        return (stack.getItem() instanceof BlockItem item && allowed(item.getBlock()))
            || stack.is(ItemTags.PICKAXES) || stack.has(DataComponents.FOOD);
    }

    private boolean usefulShulker(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem item)
            || !(item.getBlock() instanceof ShulkerBoxBlock)) {
            return false;
        }
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return false;
        }
        return contents.nonEmptyItemCopyStream().anyMatch(this::neededItem);
    }

    // A shulker with nothing the build wants is dead weight.
    private boolean dropUselessShulker() {
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!(stack.getItem() instanceof BlockItem item)
                || !(item.getBlock() instanceof ShulkerBoxBlock) || usefulShulker(stack)) {
                continue;
            }
            InventoryUtil.throwStack(InventoryUtil.networkSlot(i));
            sinceInventory = 0;
            return true;
        }
        return false;
    }

    private boolean restockHotbar() {
        if (BlockUtil.findBlockSlot(this::allowed) == -1) {
            int from = InventoryUtil.findSlot(stack -> stack.getItem() instanceof BlockItem item
                && allowed(item.getBlock()), InventoryUtil.WHOLE_INVENTORY);
            if (from >= InventoryUtil.HOTBAR_SIZE) {
                return moveToHotbar(from);
            }
        }
        if (InventoryUtil.hotbarSlot(stack -> stack.is(ItemTags.PICKAXES)) == -1) {
            int from = InventoryUtil.findSlot(stack -> stack.is(ItemTags.PICKAXES),
                InventoryUtil.WHOLE_INVENTORY);
            if (from >= InventoryUtil.HOTBAR_SIZE) {
                return moveToHotbar(from);
            }
        }
        return false;
    }

    private boolean moveToHotbar(int from) {
        InventoryUtil.swap(InventoryUtil.networkSlot(from), InventoryUtil.networkSlot(spareHotbarSlot()));
        sinceInventory = 0;
        return true;
    }

    // An empty slot first then a tool then trash then the smallest stack of blocks.
    private int spareHotbarSlot() {
        int smallest = -1;
        int smallestCount = Integer.MAX_VALUE;
        int tool = -1;
        int rubbish = -1;
        for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                return i;
            }
            if (trash.contains(stack.getItem())) {
                rubbish = i;
            } else if (stack.isDamageableItem() && !stack.is(ItemTags.PICKAXES)) {
                tool = i;
            } else if (stack.getItem() instanceof BlockItem && stack.getCount() < smallestCount) {
                smallestCount = stack.getCount();
                smallest = i;
            }
        }
        if (tool != -1) {
            return tool;
        }
        if (rubbish != -1) {
            return rubbish;
        }
        return smallest == -1 ? InventoryUtil.HOTBAR_SIZE - 1 : smallest;
    }

    // The biggest trash block stack stays behind for plugging liquid and corners.
    private boolean dropTrash() {
        int keep = -1;
        int keepCount = 0;
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof BlockItem && trash.contains(stack.getItem())
                && stack.getCount() > keepCount) {
                keepCount = stack.getCount();
                keep = i;
            }
        }
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (i == keep || stack.isEmpty() || !trash.contains(stack.getItem())) {
                continue;
            }
            // Facing back and up keeps the pile off the line ahead. The look goes out first.
            FaceMode.SPAM.faceExact(mc.player.getYRot() + 180, -25, RotationPriority.IDLE);
            InventoryUtil.throwStack(InventoryUtil.networkSlot(i));
            sinceInventory = 0;
            return true;
        }
        return false;
    }

    // An end crystal in the way is a trap. Three arrows and then it is left alone.
    private boolean shootCrystal() {
        if (!crystalTraps.isOn()) {
            releaseBow();
            return false;
        }
        if (crystal != null && (!crystal.isAlive() || outOfBand(crystal))) {
            ignoredCrystals.add(crystal.getId());
            crystal = null;
            crystalShots = 0;
        }
        if (crystal == null) {
            crystal = findCrystal();
            crystalShots = 0;
        }
        if (crystal == null) {
            releaseBow();
            return false;
        }
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.BOW));
        if (slot == -1) {
            ignoredCrystals.add(crystal.getId());
            crystal = null;
            return false;
        }
        BlockMiner.release();
        slots.select(slot);
        aimAtCrystal();
        if (mc.player.getTicksUsingItem() >= BowItem.MAX_DRAW_DURATION) {
            releaseBow();
            crystalShots++;
            if (crystalShots >= CRYSTAL_SHOTS) {
                ignoredCrystals.add(crystal.getId());
                crystal = null;
            }
            return true;
        }
        InputUtil.hold(mc.options.keyUse);
        drawing = true;
        return true;
    }

    private void releaseBow() {
        if (drawing) {
            InputUtil.release(mc.options.keyUse);
            drawing = false;
        }
    }

    private Entity findCrystal() {
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal) || ignoredCrystals.contains(entity.getId())) {
                continue;
            }
            if (outOfBand(entity) || !mc.player.hasLineOfSight(entity)) {
                continue;
            }
            double distance = mc.player.distanceTo(entity);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    private boolean outOfBand(Entity entity) {
        double distance = mc.player.distanceTo(entity);
        return distance < CRYSTAL_NEAR || distance > CRYSTAL_FAR;
    }

    private void aimAtCrystal() {
        Vec3 at = crystal.getBoundingBox().getCenter();
        RotationManager.requestExact(RotationManager.yawTo(at), bowPitch(mc.player.getEyePosition(), at),
            RotationPriority.ATTACK);
    }

    // The lower of the two arcs that reach the point with no drag worked in.
    private static float bowPitch(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        double rise = to.y - from.y;
        double g = ProjectileUtil.ARROW_GRAVITY;
        double v2 = ProjectileUtil.BOW_SPEED * ProjectileUtil.BOW_SPEED;
        double root = v2 * v2 - g * (g * flat * flat + 2 * rise * v2);
        if (root < 0 || flat < 0.01) {
            return (float) -Math.toDegrees(Math.atan2(rise, Math.max(flat, 0.01)));
        }
        double angle = Math.atan((v2 - Math.sqrt(root)) / (g * flat));
        return (float) -Math.toDegrees(angle);
    }

    // Nothing in reach was left to do. Steps aside for a block the line cannot reach and
    // otherwise walks on once nothing just ahead holds the walk.
    private void moveOn(double here) {
        Step step = stepToReach.isOn() ? findStep(here) : null;
        stepping = step != null;
        if (stepping) {
            walker.holdLane(step.lane());
            waitingOn = step.block();
            status = Status.STEPPING;
            stopWalking();
            return;
        }
        walker.holdLane(homeLane());
        walk(here);
    }

    private void walk(double here) {
        slots.restoreIfMine();
        waitingOn = holdFor(here);
        boolean go = waitingOn == null;
        // These waits are what the player chose and never time out.
        if (status == Status.OUT_OF_REACH || status == Status.NO_BLOCKS) {
            idleTicks = 0;
        }
        if (freeLook.isOn()) {
            stopWalking();
            if (go) {
                walker.walkAlong(WALK_SPEED);
            }
        } else {
            walking = go;
            if (go) {
                mc.options.keyUp.setDown(true);
            } else {
                InputUtil.release(mc.options.keyUp);
            }
        }
        if (go) {
            jumpStep(here);
        }
    }

    // The block the walk waits for. Null once it may go on. A job in reach just ahead that did
    // not get done holds it. A block beside or behind you that nothing reaches holds it whilst
    // Skip unreachable is off and is passed by otherwise.
    private BlockPos holdFor(double here) {
        BlockPos outOfReach = null;
        for (BlockPos pos : stretch) {
            double along = walker.alongOf(pos);
            if (along < here - BEHIND || along > here + LEAD || !pending(pos)) {
                continue;
            }
            if (reaches(pos)) {
                if (along >= here + 1) {
                    status = outOfBlocks ? Status.NO_BLOCKS : Status.BLOCKED;
                    return pos;
                }
            } else if (along <= here + AxisWalker.BESIDE && !behindStart(pos)) {
                if (skipUnreachable.isOn()) {
                    passedBy.add(pos);
                } else if (outOfReach == null) {
                    outOfReach = pos;
                }
            }
        }
        if (outOfReach != null) {
            status = Status.OUT_OF_REACH;
        }
        return outOfReach;
    }

    // The nearest block the line cannot reach that a lane of the floor reaches. Null when there
    // is none. A block still out of reach once you stand on its lane is left to the walk.
    private Step findStep(double here) {
        // A fill is only worth a step with a block to put in it.
        boolean canFill = BlockUtil.findBlockSlot(this::allowed) != -1;
        List<BlockPos> stranded = new ArrayList<>();
        for (BlockPos pos : stretch) {
            if (stranded(pos, here, canFill)) {
                stranded.add(pos);
            }
        }
        stranded.sort(Comparator.comparingDouble(BlockUtil::distanceTo));
        for (BlockPos pos : stranded) {
            Step step = stepFor(pos, here);
            if (step == null) {
                continue;
            }
            if (!mc.player.onGround() || !walker.onLane(step.lane())) {
                return step;
            }
            unsteppable.add(pos);
        }
        return null;
    }

    // A job out of reach that walking along the line will not bring in reach. It lies beside or
    // behind you or the line passes too far from it.
    private boolean stranded(BlockPos pos, double here, boolean canFill) {
        double along = walker.alongOf(pos);
        if (along < here - BEHIND || along > here + LEAD || behindStart(pos) || reaches(pos)
            || unsteppable.contains(pos)) {
            return false;
        }
        if (!needsClearing(pos) && !(canFill && needsFilling(pos))) {
            return false;
        }
        Vec3 passing = walker.eyeAt(along, homeLane(), plan.floorAt(walker.depthOf(pos)));
        return along <= here + AxisWalker.BESIDE || !BlockUtil.reaches(pos, passing, reach.getValue());
    }

    // The highway of a run begins at the depth it started on. Blocks behind that are done when
    // they come in reach and are never waited or stepped for.
    private boolean behindStart(BlockPos pos) {
        return walker.depthOf(pos) < 0;
    }

    // The first lane from yours outwards on the block's side that brings it in reach. Null once a
    // lane cannot be stood on or the edge of the floor is passed.
    private Step stepFor(BlockPos pos, double here) {
        double home = homeLane();
        double side = Math.signum(walker.acrossOf(pos) - home);
        int floorY = floorHere(here);
        // The margin comes off whichever limit is nearer. Reach can be typed past the server's.
        double range = Math.min(reach.getValue(), BlockUtil.serverReachLimit()) - STEP_MARGIN;
        for (double lane = home; lane >= leftLane() && lane <= rightLane(); lane = nextLane(lane, side)) {
            if (lane != home && !walker.standable(here, lane, floorY)) {
                return null;
            }
            if (BlockUtil.reaches(pos, walker.eyeAt(here, lane, floorY), range)) {
                return new Step(pos, lane);
            }
            if (side == 0) {
                return null;
            }
        }
        return null;
    }

    // The middle of the next lane past this one on a side.
    private static double nextLane(double lane, double side) {
        return side > 0 ? Math.floor(lane) + 1 : Math.ceil(lane) - 1;
    }

    // A block the walk passed by is counted once it drops out of the stretch still undone.
    // Chat names the first of each run.
    private void countPassed(double here) {
        Iterator<BlockPos> passed = passedBy.iterator();
        while (passed.hasNext()) {
            BlockPos pos = passed.next();
            if (walker.alongOf(pos) >= here - BEHIND) {
                continue;
            }
            passed.remove();
            if (!pending(pos)) {
                continue;
            }
            leftUndone++;
            if (!toldLeft) {
                toldLeft = true;
                ChatUtil.message("§bHighwayBuilder §7left a block it cannot reach at §f"
                    + BlockUtil.text(pos) + "§7.");
            }
        }
    }

    // A step up just ahead needs a jump. Walking into it from the ground starts one.
    private void jumpStep(double here) {
        int depth = (int) Math.round(here);
        if (mc.player.onGround() && mc.player.horizontalCollision
            && plan.floorAt(depth + 1) > plan.floorAt(depth)) {
            mc.player.jumpFromGround();
        }
    }

    private void stopWalking() {
        if (walking) {
            walking = false;
            InputUtil.release(mc.options.keyUp);
        }
    }

    // An empty list means any plain building block will do.
    private boolean allowed(Block block) {
        if (blocks.size() == 0) {
            return BlockUtil.isBuildingBlock(block, BlockPos.ZERO);
        }
        return blocks.contains(block);
    }

    // Even widths stand on the seam between the two middle lanes.
    private double homeLane() {
        return (leftLane() + rightLane()) / 2.0;
    }

    private int leftLane() {
        return AxisWalker.leftLane(width.getInt());
    }

    private int rightLane() {
        return AxisWalker.rightLane(width.getInt());
    }

    // The totals are read once turning off has counted the blocks passed by.
    private void stop(String reason) {
        ChatUtil.error(reason);
        boolean leave = disconnectOnStop.isOn() && inGame();
        setEnabled(false);
        if (leave) {
            ChatUtil.leaveServer("HighwayBuilder stopped.\n§7" + reason + "\n§7It " + totals());
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!walker.isLocked() || !inGame()) {
            return;
        }
        if (outline.isOn()) {
            drawStretch(event.getBatch());
        }
        drawJobs(event);
    }

    private void drawJobs(Render3DEvent event) {
        int drawn = 0;
        for (BlockPos pos : stretch) {
            if (drawn >= MAX_DRAWN) {
                return;
            }
            if (renderMine.isOn() && needsClearing(pos)) {
                mineBox.draw(event.getBatch(), pos, false);
                drawn++;
            } else if (renderPlace.isOn() && needsBlock(pos)) {
                placeBox.draw(event.getBatch(), pos, false);
                drawn++;
            }
        }
    }

    // A frame round the tunnel from just behind the player to the end of the reach. Each depth
    // draws its own floor and top and a step closes off where either changes.
    private void drawStretch(DrawBatch batch) {
        int color = ColorUtil.withAlpha(outlineColor.getColor(), 255);
        int here = (int) Math.floor(walker.travelled());
        int first = here - BEHIND;
        int last = here + AHEAD;
        double left = leftLane() - 0.5;
        double right = rightLane() + 0.5;
        for (int depth = first; depth <= last; depth++) {
            double near = depth - 0.5;
            double bottom = plan.floorAt(depth) - walker.floorY();
            double top = plan.roofAt(depth) + height.getInt() - walker.floorY();
            for (double side : new double[] {left, right}) {
                batch.line(walker.pointAt(near, side, bottom), walker.pointAt(near + 1, side, bottom), color, true);
                batch.line(walker.pointAt(near, side, top), walker.pointAt(near + 1, side, top), color, true);
            }
            boolean changed = plan.floorAt(depth) != plan.floorAt(depth - 1)
                || plan.roofAt(depth) != plan.roofAt(depth - 1);
            if (depth == first || changed) {
                ring(batch, near, bottom, top, color);
            }
            if (depth > first && changed) {
                ring(batch, near, plan.floorAt(depth - 1) - walker.floorY(),
                    plan.roofAt(depth - 1) + height.getInt() - walker.floorY(), color);
            }
            if (depth == last) {
                ring(batch, near + 1, bottom, top, color);
            }
        }
    }

    // The outline of the tunnel across the line at a point along it.
    private void ring(DrawBatch batch, double along, double bottom, double top, int color) {
        double left = leftLane() - 0.5;
        double right = rightLane() + 0.5;
        Vec3 lowLeft = walker.pointAt(along, left, bottom);
        Vec3 lowRight = walker.pointAt(along, right, bottom);
        Vec3 highRight = walker.pointAt(along, right, top);
        Vec3 highLeft = walker.pointAt(along, left, top);
        batch.line(lowLeft, lowRight, color, true);
        batch.line(lowRight, highRight, color, true);
        batch.line(highRight, highLeft, color, true);
        batch.line(highLeft, lowLeft, color, true);
    }

    private boolean rotatesToPlace() {
        return rotation.isAny(Rotation.PLACE, Rotation.BOTH);
    }

    private boolean rotatesToMine() {
        return rotation.isAny(Rotation.MINE, Rotation.BOTH);
    }
}
