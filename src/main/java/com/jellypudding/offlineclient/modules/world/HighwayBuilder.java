package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.mixin.MultiPlayerGameModeAccessor;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.combat.BowAimbot;
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
import com.jellypudding.offlineclient.util.BreakTime;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.HeldKey;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.Leave;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.PacketBreaker;
import com.jellypudding.offlineclient.util.PlaceWatch;
import com.jellypudding.offlineclient.util.ProjectilePath;
import com.jellypudding.offlineclient.util.ProjectileUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SlopePlan;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TickRate;
import com.jellypudding.offlineclient.util.UseBudget;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SignBlock;
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

// The nearest job is done each tick. Liquid is plugged first and clearing goes before filling.
// Unsupported blocks go into the air.
public final class HighwayBuilder extends Module {

    private static final int LEAD = 2;

    private static final int AHEAD = 6;
    private static final int BEHIND = 2;

    private static final int IDLE_LIMIT = 200;

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

    private static final double END_SLACK = 0.3;

    // An axis or a diagonal nearer than this is the one you stand on and not the next.
    private static final double CROSSING_AHEAD = 1;

    private static final List<Block> SIGNS = BuiltInRegistries.BLOCK.stream()
        .filter(block -> block instanceof SignBlock).toList();

    public enum Movement { AUTO, MANUAL }

    public enum Rotation { NONE, MINE, PLACE, BOTH }

    public enum Sides { OPEN, RAILINGS, WALLS }

    public enum StopAt { NEVER, LENGTH, AXIS, DIAGONAL }

    public enum Slope {
        FLAT(0), UP(1), DOWN(-1);

        private final int rise;

        Slope(int rise) {
            this.rise = rise;
        }
    }

    // Why the module is not simply building or walking. The word follows the distance.
    private enum Status {
        NONE(null), LAG("lag"), HANDS_BUSY("hands busy"), FIGHTING("fighting"), CRYSTAL("crystal"),
        RESTOCKING("restocking"), GRINDING("grinding"), STEPPING("stepping"),
        OUT_OF_REACH("out of reach"), BLOCKED("blocked"), NO_BLOCKS("no blocks");

        private final String word;

        Status(String word) {
            this.word = word;
        }
    }

    private record Step(BlockPos block, double lane) {
    }

    private final NumberSetting width = new NumberSetting("Width",
        "How wide the highway is.", 4, 1, 5, 1, " blocks").max(9);
    // A taller tunnel's top row is past the server's reach from the floor.
    private final NumberSetting height = new NumberSetting("Height",
        "How tall the tunnel is.", 3, 2, 5, 1, " blocks").max(8);
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
    private final RegistryListSetting<Block> keep = new RegistryListSetting<>("Leave standing",
        "Blocks the highway never breaks. It starts as every kind of sign. Click to pick them.",
        BuiltInRegistries.BLOCK, SIGNS);
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
        0, 0, 4, 1, " blocks").max(7)
        .under(torches, () -> torches.isOn() && !sides.is(Sides.RAILINGS));
    private final EnumSetting<Rotation> rotation = new EnumSetting<>("Rotation",
        "When a look packet is sent.", Rotation.BOTH)
        .describe(Rotation.NONE, "Never. The server keeps your own view.")
        .describe(Rotation.MINE, "Only towards blocks being broken.")
        .describe(Rotation.PLACE, "Only towards blocks being placed.")
        .describe(Rotation.BOTH, "Towards everything the module touches.");
    private final NumberSetting reach = new NumberSetting("Reach",
        "How far the nearest edge of a block may be from your eyes to break or place it. The server allows one block more than your normal reach.",
        4.5, 1, 5.5, 0.1, " blocks");
    private final NumberSetting breakDelay = new NumberSetting("Break delay",
        "Ticks between one block break and the next.", 0, 0, 20, 1, " ticks");
    private final NumberSetting breaksPerTick = new NumberSetting("Breaks per tick",
        "How many one hit blocks come out in a tick whilst no mining rotation is sent. " + PacketBreaker.PAPER_LIMIT,
        1, 1, 16, 1)
        .under(rotation, Rotation.NONE, Rotation.PLACE);
    private final BoolSetting doubleMine = new BoolSetting("Double mine",
        "Breaks a second block with packets whilst the first is mined by hand.", true)
        .under(rotation, Rotation.MINE, Rotation.BOTH);
    private final NumberSetting savePickaxes = new NumberSetting("Save pickaxes",
        "Turns the module off once you are down to this many pickaxes. Zero never stops.",
        1, 0, 36, 1).max(36);
    private final NumberSetting placeDelay = new NumberSetting("Place delay",
        "Ticks between one placement and the next.", 0, 0, 20, 1, " ticks");
    private final NumberSetting placementsPerTick = new NumberSetting("Placements per tick",
        "How many blocks may go down in one tick.", 1, 1, 8, 1);
    private final BoolSetting crystalTraps = new BoolSetting("Shoot crystal traps",
        "Puts an arrow through any end crystal sitting in the way ahead.", true);
    private final EnumSetting<StopAt> stopAt = new EnumSetting<>("Stop at",
        "Where the highway ends by itself.", StopAt.NEVER)
        .describe(StopAt.NEVER, "Nowhere. It runs until you stop it or it runs out.")
        .describe(StopAt.LENGTH, "After the length you set.")
        .describe(StopAt.AXIS, "Where the line next crosses the x or the z axis of the world.")
        .describe(StopAt.DIAGONAL, "Where the line next crosses a diagonal of the world.");
    private final NumberSetting stopLength = new NumberSetting("Length",
        "Blocks from where the highway began to where it ends.", 1000, 8, 10000, 8, " blocks").min(1)
        .under(stopAt, StopAt.LENGTH);
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
    private final HeldKey forward = new HeldKey(options -> options.keyUp);
    private final PlaceWatch place = new PlaceWatch();
    private final SlotSwap slots = new SlotSwap();
    private final SlopePlan plan = new SlopePlan();
    private final HighwaySupplies supplies = new HighwaySupplies(slots, this::allowed, this::rotatesToPlace,
        this::rotatesToMine);

    private int idleTicks;
    private double bestTravelled;

    private Status status = Status.NONE;
    private BlockPos waitingOn;
    private boolean stepping;

    private BlockPos mineTarget;
    private BlockPos placeTarget;

    private int sinceBreak;
    private int sincePlace;
    private int blocksBroken;
    private int blocksPlaced;

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
    private Slope planned = Slope.FLAT;
    private int slopeFrom;
    private boolean toldDiagonal;

    private BlockPos wasMining;

    // Second blocks taken down with packets whilst the hand works the first.
    private final PacketBreaker spares = new PacketBreaker();

    private StopAt crossingFor;
    private double crossing;
    private double runEnd = Double.POSITIVE_INFINITY;
    private long startedAt;

    private final Set<Integer> ignoredCrystals = new HashSet<>();
    private Entity crystal;
    private int crystalShots;
    private final HeldKey bowDraw = new HeldKey(options -> options.keyUse);
    private boolean toldNoArrows;

    private final List<BlockPos> stretch = new ArrayList<>();

    public HighwayBuilder() {
        super("HighwayBuilder", "Digs and builds a highway along one line.", Category.WORLD);
        addSettings(width, height, slope, upKey, downKey, levelOut, levelHeight, diagonal, movement,
            freeLook, stepToReach, skipUnreachable, reLevel, pauseOnLag, blocks, keep, floor, replaceFloor,
            sides, replaceSides, clearAboveRailings, ceiling, replaceCeiling, fillLiquids, torches,
            torchSpacing, torchHeight, rotation, reach, breakDelay, breaksPerTick, doubleMine,
            savePickaxes, placeDelay, placementsPerTick);
        addSettings(supplies.settings());
        addSettings(crystalTraps, stopAt, stopLength, stopWhenEmpty, disconnectOnStop, outline, outlineColor,
            renderMine);
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
        place.clear();
        slots.forget();
        mineTarget = null;
        placeTarget = null;
        spares.reset();
        crystal = null;
        ignoredCrystals.clear();
        blocksBroken = 0;
        blocksPlaced = 0;
        sinceBreak = 0;
        sincePlace = 0;
        supplies.reset();
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
        forward.letGo();
        bowDraw.letGo();
        walker.clear();
        mineTarget = null;
        placeTarget = null;
        spares.reset();
        supplies.end();
        stretch.clear();
        passedBy.clear();
        unsteppable.clear();
        if (blocksBroken > 0 || blocksPlaced > 0 || leftUndone > 0) {
            tell(totals());
        }
    }

    private String totals() {
        String left = leftUndone > 0 ? "§7 and left §f" + leftUndone + "§7 it could not reach" : "";
        double seconds = (System.currentTimeMillis() - startedAt) / 1000.0;
        return "covered §f" + (int) bestTravelled + "§7 blocks in §f" + ChatUtil.duration(seconds)
            + "§7 and broke §f" + blocksBroken + "§7 and placed §f" + blocksPlaced + left + "§7.";
    }

    private void warnAboutModules() {
        if (diagonal.isOn() && Modules.enabled(Speed.class)) {
            ChatUtil.error("Turn Speed off on a diagonal or you will drift off the line.");
        }
        if (!Modules.enabled(NoKnockback.class)) {
            tell("works better with §fNoKnockback§7 on.");
        }
        if (Modules.enabled(FastBreak.class)) {
            tell("works better with §fFastBreak§7 off. The server puts back blocks broken too early.");
        }
    }

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
        toldNoArrows = false;
        unsteppable.clear();
        crossingFor = null;
        startedAt = System.currentTimeMillis();
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
        // A dead player still ticks and the server ignores every click it makes.
        if (mc.player.isDeadOrDying()) {
            stop("HighwayBuilder stopped because you died.");
            return;
        }
        if (place.movedAway()) {
            stop("HighwayBuilder stopped because you changed world.");
            return;
        }
        if (!walker.isLocked()) {
            lockAxis();
        }
        status = pauseReason();
        if (status != Status.NONE) {
            forward.letGo();
            bowDraw.letGo();
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
            forward.letGo();
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

        runEnd = endDepth(here);
        gatherStretch(here - BEHIND, Math.min(here + AHEAD, runEnd + AxisWalker.BESIDE));
        countPassed(here);
        if (shootCrystal()) {
            status = Status.CRYSTAL;
            idleTicks = 0;
            forward.letGo();
            return;
        }
        if (levelUp(here)) {
            forward.letGo();
            return;
        }
        if (supplies.tick()) {
            status = supplies.grinding() ? Status.GRINDING
                : supplies.restocking() ? Status.RESTOCKING : Status.NONE;
            idleTicks = 0;
            forward.letGo();
            return;
        }
        spares.tick(this::needsClearing);
        if (work()) {
            if (stepping) {
                status = Status.STEPPING;
            }
            if (auto) {
                forward.letGo();
            }
            return;
        }
        // Running out of blocks turns the module off inside a fill.
        if (!isEnabled()) {
            return;
        }
        if (here >= runEnd - END_SLACK) {
            finish();
            return;
        }
        if (auto) {
            moveOn(here);
        }
    }

    // The next axis or diagonal is looked for once when the run starts and again when Stop at
    // changes. A crossing reached must not give way to the one after it.
    private double endDepth(double here) {
        StopAt choice = stopAt.getValue();
        if (choice != crossingFor) {
            crossingFor = choice;
            crossing = choice == StopAt.AXIS || choice == StopAt.DIAGONAL
                ? crossingAhead(here, choice == StopAt.DIAGONAL) : Double.POSITIVE_INFINITY;
            if (choice != StopAt.NEVER && choice != StopAt.LENGTH && Double.isInfinite(crossing)) {
                tell("has no " + (choice == StopAt.AXIS ? "axis" : "diagonal")
                    + " ahead on this line and runs until you stop it.");
            }
        }
        return choice == StopAt.LENGTH ? stopLength.getValue() : crossing;
    }

    // The depth at which the line next comes to block nought on x or z or for a diagonal to a
    // block as far along x as along z. Infinite when it never does.
    private double crossingAhead(double here, boolean diagonal) {
        Vec3 start = walker.pointAt(0, 0, 0);
        Vec3 step = walker.pointAt(1, 0, 0).subtract(start);
        double[] depths = diagonal
            ? new double[] {(start.z - start.x) / (step.x - step.z), (1 - start.x - start.z) / (step.x + step.z)}
            : new double[] {(0.5 - start.x) / step.x, (0.5 - start.z) / step.z};
        double next = Double.POSITIVE_INFINITY;
        for (double depth : depths) {
            // A line that never meets the mark divides by nought and gives no number to compare.
            if (depth > here + CROSSING_AHEAD && depth < next) {
                next = depth;
            }
        }
        return next;
    }

    // Anything else with a claim on the hands takes the tick. The bow drawn at a crystal is the
    // module's own use and the target BowAimbot picks for that draw is no fight.
    private Status pauseReason() {
        if (pauseOnLag.isOn() && TickRate.INSTANCE.stalled()) {
            return Status.LAG;
        }
        if (Hands.slotsTaken() || mc.player.isUsingItem() && !bowDraw.isHeld()) {
            return Status.HANDS_BUSY;
        }
        boolean fighting = bowDraw.isHeld() ? Modules.fighting(BowAimbot.class) : Modules.fighting();
        return fighting ? Status.FIGHTING : Status.NONE;
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
        return InventoryUtil.count(stack -> stack.is(ItemTags.PICKAXES), InventoryUtil.WHOLE_INVENTORY);
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

    // A diagonal line stays level because its rounded depths would make jagged steps.
    private void followSlope(double here) {
        if (walker.isDiagonal()) {
            if (!slope.is(Slope.FLAT) && !toldDiagonal) {
                toldDiagonal = true;
                tell("keeps a diagonal highway level.");
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
            tell(change + " from §f" + (depth - (int) Math.round(here))
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
                tell("levels out " + end + "§7.");
                return;
            }
        }
    }

    private int nextBend(double here) {
        return Math.max(frontier, (int) Math.round(here)) + BEND_LEAD;
    }

    private void bendAt(int depth, Slope to) {
        slopeFrom = plan.floorAt(depth - 1);
        plan.bend(depth, to.rise);
        planned = to;
    }

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

    private boolean reachedLevel(int depth) {
        if (!levelOut.isOn()) {
            return false;
        }
        int goal = levelHeight.getInt();
        int last = plan.floorAt(depth - 1);
        return planned == Slope.UP ? slopeFrom <= goal && last >= goal : slopeFrom >= goal && last <= goal;
    }

    private int floorHere(double here) {
        return plan.floorAt((int) Math.round(here));
    }

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

    // True when the tick was spent.
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

    private boolean packetMine() {
        int sent = 0;
        for (BlockPos pos : stretch) {
            if (sent >= breaksPerTick.getInt()) {
                break;
            }
            if (!needsClearing(pos) || !reaches(pos) || !BreakTime.canInstantBreak(pos)) {
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
        BlockPos spare = nearest(pos -> !pos.equals(busy) && needsClearing(pos) && spares.canSend(pos));
        if (spare != null) {
            spares.send(spare);
            worked(spare);
            blocksBroken++;
        }
    }

    private BlockPos nearest(Predicate<BlockPos> wanted) {
        return BlockUtil.nearestOf(stretch, pos -> reaches(pos) && wanted.test(pos));
    }

    private enum Part { TUNNEL, FLOOR, WALL, ABOVE_WALL, CEILING, SIDE, OUTSIDE }

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

    // A block NoInteract keeps standing for the lava behind it is left like one that cannot be dug.
    private boolean needsClearing(BlockPos pos) {
        if (!BlockUtil.diggable(pos) || BlockUtil.isStandingOn(pos)
            || keep.contains(BlockUtil.state(pos).getBlock()) || BlockMiner.kept(pos)) {
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

    private boolean isLiquid(BlockPos pos) {
        if (mc.level.getFluidState(pos).isEmpty() || BlockUtil.intersectsPlayer(pos)) {
            return false;
        }
        return BlockUtil.isReplaceable(pos) && partOf(pos) != Part.OUTSIDE;
    }

    private boolean needsBlock(BlockPos pos) {
        return needsFilling(pos) || (fillLiquids.isOn() && isLiquid(pos));
    }

    // Liquid is plugged whenever it comes in reach but never holds the walk.
    private boolean pending(BlockPos pos) {
        return needsClearing(pos) || needsFilling(pos);
    }

    private boolean placeRound(BlockPos first) {
        if (sincePlace < placeDelay.getInt()) {
            sincePlace++;
            return true;
        }
        // Paper drops use packets past its limit. The rest of the round waits for a later tick.
        int round = Math.min(placementsPerTick.getInt(), UseBudget.remaining());
        if (round == 0) {
            return true;
        }
        if (!place(first)) {
            return false;
        }
        sincePlace = 0;
        int placed = 1;
        while (placed < round) {
            BlockPos next = nearest(pos -> !pos.equals(first) && needsBlock(pos));
            if (next == null || !place(next)) {
                break;
            }
            placed++;
        }
        return true;
    }

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

    // An end crystal in the way is a trap. Three arrows and then it is left alone.
    private boolean shootCrystal() {
        if (!crystalTraps.isOn()) {
            bowDraw.letGo();
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
            bowDraw.letGo();
            return false;
        }
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.BOW));
        if (slot == -1) {
            ignoredCrystals.add(crystal.getId());
            crystal = null;
            return false;
        }
        // The bow draws nothing without an arrow and arrows can run out between shots.
        if (mc.player.getProjectile(mc.player.getInventory().getItem(slot)).isEmpty()) {
            if (!toldNoArrows) {
                toldNoArrows = true;
                tell("has no arrows for the crystal ahead.");
            }
            ignoredCrystals.add(crystal.getId());
            crystal = null;
            bowDraw.letGo();
            return false;
        }
        BlockMiner.release();
        slots.select(slot);
        aimAtCrystal();
        if (mc.player.getTicksUsingItem() >= BowItem.MAX_DRAW_DURATION) {
            bowDraw.letGo();
            crystalShots++;
            if (crystalShots >= CRYSTAL_SHOTS) {
                ignoredCrystals.add(crystal.getId());
                crystal = null;
            }
            return true;
        }
        bowDraw.hold();
        return true;
    }

    private Entity findCrystal() {
        return EntityUtil.nearest(Double.MAX_VALUE, entity -> entity instanceof EndCrystal
            && !ignoredCrystals.contains(entity.getId()) && !outOfBand(entity) && mc.player.hasLineOfSight(entity));
    }

    private boolean outOfBand(Entity entity) {
        double distance = mc.player.distanceTo(entity);
        return distance < CRYSTAL_NEAR || distance > CRYSTAL_FAR;
    }

    // The arrow leaves from a little below the eyes.
    private void aimAtCrystal() {
        Vec3 at = crystal.getBoundingBox().getCenter();
        Vec3 start = mc.player.getEyePosition().subtract(0, ProjectilePath.BELOW_EYES, 0);
        ProjectilePath.Arc arc = ProjectilePath.arrowArc(start, at, ProjectileUtil.BOW_SPEED);
        float pitch = arc == null ? RotationManager.pitchTo(at) : arc.pitch();
        RotationManager.requestExact(RotationManager.yawTo(at), pitch, RotationPriority.ATTACK);
    }

    private void moveOn(double here) {
        Step step = stepToReach.isOn() ? findStep(here) : null;
        stepping = step != null;
        if (stepping) {
            walker.holdLane(step.lane());
            waitingOn = step.block();
            status = Status.STEPPING;
            forward.letGo();
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
            forward.letGo();
            if (go) {
                walker.walkAlong(WALK_SPEED);
            }
        } else if (go) {
            forward.hold();
        } else {
            forward.letGo();
            // The key goes up whoever held it. That keeps an AutoWalk switched on before HighwayBuilder
            // from carrying you past the block waited on. Both tick in the order they were switched on.
            InputUtil.release(mc.options.keyUp);
        }
        if (go) {
            jumpStep(here);
        }
    }

    // A job in reach just ahead that did not get done holds the walk. A block beside or behind you
    // that nothing reaches holds it whilst Skip unreachable is off and is passed by otherwise.
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

    private static double nextLane(double lane, double side) {
        return side > 0 ? Math.floor(lane) + 1 : Math.ceil(lane) - 1;
    }

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
                tell("left a block it cannot reach at §f"
                    + BlockUtil.text(pos) + "§7.");
            }
        }
    }

    private void jumpStep(double here) {
        int depth = (int) Math.round(here);
        if (mc.player.onGround() && mc.player.horizontalCollision
            && plan.floorAt(depth + 1) > plan.floorAt(depth)) {
            mc.player.jumpFromGround();
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

    private void stop(String reason) {
        ChatUtil.error(reason);
        turnOff(reason);
    }

    private void finish() {
        tell("reached the end you set.");
        turnOff("It reached the end you set.");
    }

    // The totals are read once turning off has counted the blocks passed by.
    private void turnOff(String reason) {
        boolean leave = disconnectOnStop.isOn() && inGame();
        setEnabled(false);
        if (leave) {
            Leave.QUIT.go("HighwayBuilder stopped.\n§7" + reason + "\n§7It " + totals());
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

    private void drawStretch(DrawBatch batch) {
        int color = ColorUtil.withAlpha(outlineColor.getColor(), 255);
        int here = (int) Math.floor(walker.travelled());
        int first = here - BEHIND;
        int last = (int) Math.min(here + AHEAD, Math.round(runEnd));
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
