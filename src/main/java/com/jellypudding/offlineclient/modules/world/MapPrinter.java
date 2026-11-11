package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.condition.Condition;
import com.jellypudding.offlineclient.condition.Conditions;
import com.jellypudding.offlineclient.config.Blueprint;
import com.jellypudding.offlineclient.config.BuildFiles;
import com.jellypudding.offlineclient.config.BuildFrame;
import com.jellypudding.offlineclient.config.MapFiles;
import com.jellypudding.offlineclient.config.MapStations;
import com.jellypudding.offlineclient.config.MapStations.Role;
import com.jellypudding.offlineclient.config.MapStations.Stage;
import com.jellypudding.offlineclient.config.MapStations.Station;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.event.events.TotemPopEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.world.mapart.MapMaking;
import com.jellypudding.offlineclient.modules.world.mapart.Printer;
import com.jellypudding.offlineclient.modules.world.mapart.SquareClearing;
import com.jellypudding.offlineclient.modules.world.mapart.StationMarking;
import com.jellypudding.offlineclient.modules.world.mapart.StationStock;
import com.jellypudding.offlineclient.path.MiningTrip;
import com.jellypudding.offlineclient.path.PathGoal;
import com.jellypudding.offlineclient.path.Trip;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.BuildGhost;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BuildSite;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.CornerPicker;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.MapGrid;
import com.jellypudding.offlineclient.util.MapPicture;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.PlacedBox;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.SiteWorker;
import com.jellypudding.offlineclient.util.StateMatch;
import com.jellypudding.offlineclient.util.StatePlacer;
import com.jellypudding.offlineclient.util.StationVisit;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TakeFrom;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.UseBudget;
import com.jellypudding.offlineclient.util.WorldWatch;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterable;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

// Prints map art from a folder of files with nobody at the keyboard. Each file is laid on the map
// square the station was marked in and printed strip by strip by the printer's own worker with
// materials from the marked chests. The finished picture is checked against its file. A map of
// it is made and locked and stored and the square is cleared for the next file. How far it has
// got outlasts a disconnect and a restart and it carries on only in the world of its station.
public final class MapPrinter extends Module implements Printer {

    private static final int SIDE = MapPicture.SIDE;

    // Clicks on a block the server turns down before it waits a minute.
    private static final int ATTEMPTS = 3;
    // Ticks the walk waits for one row before the blocks it is stuck on are left for later.
    private static final int ROW_STALL_TICKS = 200;
    // Ticks between looks over the whole picture whilst nothing can be done but wait.
    private static final int PLAN_TICKS = 20;
    // Rounds over the blocks left behind before the printer gives up on them.
    private static final int REPAIR_ROUNDS = 3;
    // A walk counts as there this close to the middle of the spot.
    private static final double AT_SPOT = 1;
    // A player stands in a cell this far either side of its middle. The walk along a strip counts
    // as there up to this far past the middle of its stand cell. That is the far edge of the row
    // it waits for.
    private static final double IN_CELL = 0.5;
    private static final double SLIDE = 1.5;
    // Clicks one visit may make. A double chest has 54 slots.
    private static final int MOST_CLICKS = 64;

    // Every block of the picture counts and one turned the wrong way is wrong.
    private static final BuildSite.Rules SITE_RULES = new BuildSite.Rules(block -> true, false, true, ATTEMPTS);

    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes a block may go. A longer reach walks wider strips.", 4.5, 3, 6, 0.1, " blocks");
    private final NumberSetting blocksPerTick = UseBudget.blocksPerTick();
    private final EnumSetting<FaceMode> faceTarget = FaceMode.setting(FaceMode.SERVER);
    private final Conditions stopOn = Conditions.forEscape("Stop on",
        "What makes it stop and say why. Click to pick the reasons. Each one you pick lists its own rows below.",
        "Damage taken");
    private final ActionSetting mark = new ActionSetting("Mark station",
        "Takes the map square you stand in and then asks for the blocks of the station one right click at a time."
            + " The station there was and how far it got are forgotten.", this::markStation).confirm();
    private final BoolSetting show = new BoolSetting("Show",
        "Outlines where the picture goes and the station and the blocks still to come near you.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.LINES, 280).under(show);

    // The first row of a strip that has a block to come and an item that row needs and none is
    // carried of. Null when every one of them is carried.
    private record Frontier(int row, Item lacking) {
    }

    // Anywhere near a block but never in its column. A block cannot go down where the player stands.
    private record Beside(BlockPos target) implements PathGoal {

        private static final double NEAR = 2;

        @Override
        public boolean reached(BlockPos pos) {
            return PathGoal.straightLine(pos, target) <= NEAR
                && (pos.getX() != target.getX() || pos.getZ() != target.getZ());
        }

        @Override
        public double heuristic(BlockPos pos, int stepUp) {
            return Math.max(0, PathGoal.estimate(pos, target, stepUp) - NEAR);
        }

        @Override
        public double distance(Vec3 from) {
            return from.distanceTo(Vec3.atBottomCenterOf(target));
        }
    }

    private final WorldWatch world = new WorldWatch();
    private final Trip trip = new Trip();
    private final StationVisit visit = new StationVisit(trip, NAME);
    private final SiteWorker worker = new SiteWorker();
    // Its walks go round whatever stands in the way. Nothing of the station is dug through.
    private final MiningTrip miner = new MiningTrip().collect(true).ownTool(true).digsThrough(false);
    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private final InventoryUtil.SlotSwap tools = new InventoryUtil.SlotSwap();
    private final BuildGhost ghost = new BuildGhost();
    private final StationStock stock = new StationStock(this, trip);
    private final MapMaking mapMaking = new MapMaking(this, stock, trip, loan);
    private final SquareClearing clearing = new SquareClearing(this, stock, visit, miner, tools);

    // The station of the world you are in. Null until one is found or marked and whilst the
    // printer waits for the world of its station.
    private Station station;
    // Set once you were told the printer waits for the world of its station.
    private boolean away;
    // The marking under way. Null whilst none runs.
    private StationMarking marking;
    private boolean useHeld;
    // Set whilst the Mark station row switches the module on. The marking starts afresh then.
    private boolean markOnEnable;

    // The file under way. Null whilst it is being read.
    private String reading;
    private MapPicture picture;
    private BuildSite site;
    private int[] walk;
    // The cells of the map square the picture covers in walk order. The map draws these.
    private int[] covered;
    private int width;
    private double standHeight;
    private final Set<Item> pictureItems = new HashSet<>();
    // Files that could not be printed. They are passed over until the module is switched on again.
    private final Set<String> skipped = new HashSet<>();

    private int ticks;

    // The trip under way. Null until it is planned.
    private Map<Item, Integer> needs;
    private int strip;
    // The row of the strip in walk order the look for the next row to fill starts from.
    private int frontierFrom;
    private boolean repairing;
    // The blocks still to come as the round over them began and the one being walked to.
    private final IntArrayList repairCells = new IntArrayList();
    private int repairCell = -1;
    private int repairRounds;
    private final IntOpenHashSet passed = new IntOpenHashSet();
    private BlockPos walkGoal;
    private int stallRow = Integer.MIN_VALUE;
    private int stallTicks;
    private int landedAtTrip;
    private int emptyTrips;
    private boolean waitingForgive;
    private int forgiveWaits;
    private int failures;
    private int visitClicks;
    // Runs once the visit under way is done. A visit that failed or was let go never runs it.
    private Runnable afterVisit;

    // Runs on after the printer is switched off whilst a chest its last click opens may still come
    // in. The chest is closed as it comes.
    private final Object closer = new Object() {
        @Subscribe
        private void onTick(TickEvent event) {
            if (isEnabled() || !visit.waitsLate()) {
                unwatch(this);
                return;
            }
            visit.closeLate();
        }
    };

    public MapPrinter() {
        super("MapPrinter", "Prints map art from the files in offlineclient/mapart and makes and stores a map of each one.",
            Category.WORLD);
        addSettings(range, blocksPerTick, faceTarget);
        addSettings(stopOn.settings());
        addSettings(mark, show);
        addSettings(style.settings());
        searchTags("map art", "mapart", "carpet", "printer", "schematic", "nbt", "cartography");
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    public String getSuffix() {
        if (station == null) {
            return away ? "waiting" : null;
        }
        if (marking != null) {
            return "marking";
        }
        if (station.file() == null) {
            return null;
        }
        String file = BuildFiles.stem(station.file());
        return switch (station.stage()) {
            case BUILD -> suffix(file, picture == null ? null : station.right().cardinality() * 100
                / picture.blueprint().blocks() + "%");
            case MAP -> suffix(file, "map");
            case LOCK -> suffix(file, "locking");
            case STORE -> suffix(file, "storing");
            case CLEAR -> suffix(file, "clearing");
        };
    }

    @Override
    protected void onEnable() {
        skipped.clear();
        failures = 0;
        stock.switchedOn();
        if (inGame()) {
            world.accept();
            stopWork();
            attach(true);
        } else {
            world.forget();
        }
    }

    // The parts stop the printer with a reason too.
    @Override
    public void disable(String reason) {
        super.disable(reason);
    }

    @Override
    protected void onDisable() {
        stopWork();
        if (visit.waitsLate()) {
            watch(closer);
        }
        worker.forget();
        marking = null;
        forgetFile();
        if (station != null) {
            // The cells seen right outlast a restart.
            MapStations.save();
        }
        station = null;
        away = false;
    }

    // The row works with the module on or off. Pressed whilst it is off it switches it on.
    private String markStation() {
        if (!inGame()) {
            return "Join a world first";
        }
        if (!isEnabled()) {
            markOnEnable = true;
            setEnabled(true);
            markOnEnable = false;
            return isEnabled() ? "Right click the cartography table" : null;
        }
        beginMarking();
        return "Right click the cartography table";
    }

    // Takes up the station of the world the player is in. Switched on by hand the printer works
    // for that station and marks one where there is none. After a change of world it carries on
    // only in the world of its station and waits quietly anywhere else.
    private void attach(boolean switchedOn) {
        worker.forget();
        forgetFile();
        marking = null;
        stopOn.start();
        MapFiles.makeFolder();
        station = MapStations.here();
        if (switchedOn) {
            stopOn.tellProblems();
            if (station != null) {
                MapStations.workHere();
            }
        }
        if (markOnEnable || station == null && switchedOn) {
            beginMarking();
            return;
        }
        if (station == null || !station.working()) {
            station = null;
            if (!away) {
                away = true;
                ChatUtil.message(TAG + "waits until you are back in the world of its station. Switch it off and on"
                    + " to print here instead.");
            }
            return;
        }
        away = false;
        if (!station.marked()) {
            marking = new StationMarking(this, StationMarking.nextRole(station));
            marking.prompt();
            return;
        }
        if (station.file() != null) {
            ChatUtil.message(TAG + "carries on with §f" + station.file() + "§7.");
        }
    }

    private void forgetFile() {
        reading = null;
        picture = null;
        site = null;
        walk = null;
        covered = null;
        pictureItems.clear();
        resetTrip();
        repairRounds = 0;
        forgiveWaits = 0;
        waitingForgive = false;
        landedAtTrip = 0;
        emptyTrips = 0;
        mapMaking.reset();
        clearing.reset();
    }

    private void resetTrip() {
        needs = null;
        stock.reset();
        repairing = false;
        frontierFrom = 0;
        repairCells.clear();
        repairCell = -1;
        passed.clear();
        walkGoal = null;
        stallRow = Integer.MIN_VALUE;
        stallTicks = 0;
    }

    // A new world or dimension starts the work afresh without swapping a borrowed stack back since a
    // rejoin hands out another inventory. A give back that waits on another module's stack still goes
    // home with that stack.
    private void forgetWork() {
        visit.stop();
        trip.stop();
        miner.stop();
        worker.forget();
        loan.forget();
        tools.forget();
        walkGoal = null;
        afterVisit = null;
    }

    // Lets go of every walk and click and the slots borrowed for them.
    private void stopWork() {
        visit.stop();
        trip.stop();
        miner.stop();
        worker.stop();
        loan.giveBack();
        tools.restoreIfMine();
        walkGoal = null;
        afterVisit = null;
    }

    // Marking

    private void beginMarking() {
        stopWork();
        forgetFile();
        BlockPos feet = mc.player.blockPosition();
        BlockPos corner = new BlockPos(MapGrid.edge(feet.getX(), 0), feet.getY(), MapGrid.edge(feet.getZ(), 0));
        station = MapStations.fresh(corner);
        away = false;
        marking = new StationMarking(this, Role.TABLE);
        ChatUtil.message(TAG + "prints on the map square from §f" + corner.getX() + " " + corner.getZ() + "§7 to §f"
            + (corner.getX() + SIDE - 1) + " " + (corner.getZ() + SIDE - 1) + "§7 with the picture at height §f"
            + corner.getY() + "§7.");
        marking.prompt();
    }

    // Runs after Freecam and anything else that holds clicks back. Only a fresh press marks.
    @Subscribe(priority = -5)
    private void onRightClick(RightClickEvent event) {
        boolean fresh = !useHeld;
        useHeld = true;
        if (marking == null || !inGame()) {
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos pos = hit.getBlockPos().immutable();
        BlockState state = BlockUtil.state(pos);
        if (marking.opensChest(pos, state)) {
            // The chest opens as usual and is read once its screen is up.
            if (fresh) {
                marking.awaitChest(pos);
            }
            return;
        }
        event.cancel();
        mc.rightClickDelay = InputUtil.USE_DELAY;
        if (fresh && marking.mark(pos, state)) {
            marking = null;
        }
    }

    @Override
    public BoundingBox footprint() {
        BlockPos corner = station.corner();
        return new BoundingBox(corner.getX(), corner.getY(), corner.getZ() - 1, corner.getX() + SIDE - 1,
            corner.getY(), corner.getZ() + SIDE - 1);
    }

    // The tick

    @Subscribe
    private void onTick(TickEvent event) {
        if (Modules.dormant()) {
            return;
        }
        ticks++;
        useHeld = mc.options.keyUse.isDown();
        if (world.changed()) {
            forgetWork();
            attach(false);
        }
        if (station == null) {
            return;
        }
        visit.closeLate();
        if (marking != null) {
            marking.readOpenedChest();
            return;
        }
        if (mc.player.isDeadOrDying()) {
            disable(NAME + " stopped because you died.");
            return;
        }
        stopOn.tick();
        Condition held = stopOn.firstHolding();
        if (held != null) {
            disable(NAME + " stopped. " + held.reason());
            return;
        }
        if (tookOver()) {
            disable(NAME + " stopped because you took over.");
            return;
        }
        if (handTaken()) {
            standAside();
            return;
        }
        work();
    }

    @Override
    public int ticks() {
        return ticks;
    }

    // A pop is acted on as it is heard of.
    @Subscribe
    private void onTotemPop(TotemPopEvent event) {
        Condition pop = stopOn.popped(event);
        if (pop != null && station != null && marking == null) {
            disable(NAME + " stopped. " + pop.reason());
        }
    }

    // The player walking or jumping or hitting with no screen up takes over from the printer.
    private boolean tookOver() {
        if (mc.gui.screen() != null) {
            return false;
        }
        return InputUtil.steering() || InputUtil.physicallyHeld(mc.options.keyJump)
            || InputUtil.physicallyHeld(mc.options.keyAttack);
    }

    // A screen up that the printer did not open is the player's and the printer waits. It waits as
    // well whilst another module works a container it set down. That screen and the one a visit
    // clicks open could be taken for each other.
    private boolean handTaken() {
        return Hands.taken() || PlacedBox.anyAtWork() || mc.gui.screen() != null && !visit.ownsScreen();
    }

    // Makes room for whatever has the hand. Every errand starts afresh from what the world
    // and the inventory hold once the hand is free again.
    private void standAside() {
        visit.stop();
        trip.stop();
        miner.stop();
        worker.standAside();
        loan.release(false);
        tools.forget();
        walkGoal = null;
        afterVisit = null;
    }

    private void work() {
        if (station.file() == null) {
            nextFile();
            return;
        }
        if (picture == null) {
            readFile();
            return;
        }
        int wide = stripWidth();
        if (wide != width) {
            cutStrips(wide);
            resetTrip();
        }
        site.tick(SITE_RULES, ServerInfo.answerTicks(), false);
        stock.tick();
        if (station.stage() == Stage.BUILD) {
            site.count(SITE_RULES);
            ghost.tick(site, SITE_RULES, false);
        }
        switch (station.stage()) {
            case BUILD -> build();
            case MAP -> mapMaking.makeMap();
            case LOCK -> mapMaking.lock();
            case STORE -> mapMaking.store();
            case CLEAR -> clearing.clear();
        }
    }

    // Files

    private void nextFile() {
        List<String> waiting = new ArrayList<>(MapFiles.waiting());
        waiting.removeAll(skipped);
        if (waiting.isEmpty()) {
            disable(skipped.isEmpty() ? "There is no map file left in " + MapFiles.shown() + " for " + NAME + "."
                : NAME + " has no file left in " + MapFiles.shown() + " that it can print.");
            return;
        }
        station.begin(waiting.getFirst());
        MapStations.save();
    }

    private void readFile() {
        if (reading != null) {
            return;
        }
        String name = station.file();
        reading = name;
        MapFiles.read(name, result -> fileRead(name, result));
    }

    // Comes on the game thread. A module switched off or another file meanwhile drops it. A file
    // laid on the square before is never passed over for the next one because part of its picture
    // may stand there. One that cannot be read then stops the printer.
    private void fileRead(String name, BuildFiles.Result result) {
        if (!isEnabled() || station == null || !name.equals(reading) || !name.equals(station.file())
            || !inGame()) {
            return;
        }
        reading = null;
        boolean fresh = station.stage() == Stage.BUILD && !station.laid();
        String trouble = result.problem();
        if (trouble == null && !result.loaded().leftOut().isEmpty()) {
            trouble = name + " names blocks this game does not have.";
        }
        MapPicture.Read read = trouble == null ? MapPicture.of(result.loaded().blueprint()) : null;
        if (read != null && read.trouble() != null) {
            trouble = name + " " + read.trouble() + ".";
        }
        if (trouble != null) {
            if (fresh) {
                skip(trouble);
            } else {
                disable(trouble + " " + NAME + " stopped because the map square holds part of its picture.");
            }
            return;
        }
        picture = read.picture();
        standHeight = picture.standHeight();
        site = new BuildSite(mc.level, picture.blueprint(), BuildFrame.at(station.corner()), station.right());
        cutStrips(stripWidth());
        for (BlockState kind : picture.blueprint().palette()) {
            pictureItems.add(StateMatch.itemFor(kind));
        }
        Set<Item> unstocked = new LinkedHashSet<>();
        for (Item item : pictureItems) {
            if (!station.anyChestHolds(item)) {
                unstocked.add(item);
            }
        }
        if (fresh && !unstocked.isEmpty()) {
            skip(name + " needs " + Printer.named(unstocked) + " and no marked chest was seen holding any.");
            return;
        }
        // Cells a picture changed by hand has no block in are no part of how far it got.
        for (int cell = 0; cell < MapPicture.MOST_CELLS; cell++) {
            if (cell >= walk.length || kindOf(cell) == Blueprint.NO_SAY) {
                station.right().clear(cell);
            }
        }
        if (fresh) {
            station.setLaid();
            MapStations.save();
            ChatUtil.message(TAG + "prints §f" + name + "§7. It takes §f"
                + Tally.counted(picture.blueprint().blocks(), "block") + "§7 of §f"
                + Tally.counted(pictureItems.size(), "kind") + "§7.");
        }
    }

    private void skip(String reason) {
        ChatUtil.error(reason + " " + NAME + " goes on with the next file.");
        skipped.add(station.file());
        station.end();
        MapStations.save();
        forgetFile();
    }

    @Override
    public void cleared() {
        stopWork();
        String file = station.file();
        if (!MapFiles.finish(file)) {
            disable(NAME + " stopped because " + file + " could not be moved into the finished folder and would be"
                + " printed again. The log says why.");
            return;
        }
        ChatUtil.message(TAG + "cleared the map square and moved §f" + file + "§7 into the finished folder.");
        station.end();
        MapStations.save();
        forgetFile();
    }

    // The picture

    private SiteWorker.Rules workRules() {
        StatePlacer.Rules clicks = new StatePlacer.Rules(range.getValue(), false, false, faceTarget.getValue(),
            SwingMode.PACKET);
        return new SiteWorker.Rules(SITE_RULES, clicks, TakeFrom.INVENTORY, block -> false, SiteWorker.Order.NEAREST,
            false, true, false, false, blocksPerTick.getInt(), 0);
    }

    @Override
    public FaceMode faceTarget() {
        return faceTarget.getValue();
    }

    @Override
    public Station station() {
        return station;
    }

    @Override
    public MapPicture picture() {
        return picture;
    }

    @Override
    public int[] covered() {
        return covered;
    }

    @Override
    public Set<Item> pictureItems() {
        return pictureItems;
    }

    // Cuts the picture into strips of that width and lists the cells of the square it covers.
    private void cutStrips(int wide) {
        width = wide;
        walk = picture.walk(width);
        IntArrayList inside = new IntArrayList();
        for (int cell : walk) {
            if (inSquare(cell) && kindOf(cell) != Blueprint.NO_SAY) {
                inside.add(cell);
            }
        }
        covered = inside.toIntArray();
    }

    // Columns a strip spans from the range and the server's limit and how high the picture lets
    // the feet stand.
    private int stripWidth() {
        return MapPicture.stripWidth(range.getValue(), BlockUtil.serverReachLimit(), standHeight);
    }

    @Override
    public BlockPos cellPos(int cell) {
        BlockPos corner = station.corner();
        return new BlockPos(corner.getX() + cell % SIDE, corner.getY(),
            corner.getZ() + cell / SIDE + picture.firstRow());
    }

    private int kindOf(int cell) {
        return picture.blueprint().indexAt(cell % SIDE, 0, cell / SIDE + picture.firstRow());
    }

    private Item itemOf(int cell) {
        return StateMatch.itemFor(picture.blueprint().palette().get(kindOf(cell)));
    }

    // True for a cell of the map square itself. The north line is not one.
    private boolean inSquare(int cell) {
        return MapPicture.inSquare(cell % SIDE, cell / SIDE + picture.firstRow());
    }

    // A cell whose block is still to come or wrong. One out of view that was not last seen right
    // counts too and the walk goes to it. The position is where the cell lies.
    private boolean pending(int cell, BlockPos pos) {
        if (kindOf(cell) == Blueprint.NO_SAY) {
            return false;
        }
        return switch (site.stateAt(pos, SITE_RULES)) {
            case OUTSIDE -> !site.remembered(pos);
            case MISSING, WRONG -> !site.exhausted(pos, SITE_RULES);
            case DONE, SPARE -> false;
        };
    }

    @Override
    public int nearestCell(IntIterable cells, CellTest test) {
        Vec3 at = mc.player.position();
        int best = -1;
        double nearest = Double.MAX_VALUE;
        IntIterator each = cells.iterator();
        while (each.hasNext()) {
            int cell = each.nextInt();
            BlockPos pos = cellPos(cell);
            if (!test.test(cell, pos)) {
                continue;
            }
            double distance = pos.distToCenterSqr(at);
            if (distance < nearest) {
                nearest = distance;
                best = cell;
            }
        }
        return best;
    }

    @Override
    public int firstCell(IntIterable cells, CellTest test) {
        IntIterator each = cells.iterator();
        while (each.hasNext()) {
            int cell = each.nextInt();
            if (test.test(cell, cellPos(cell))) {
                return cell;
            }
        }
        return -1;
    }

    // Building

    private void build() {
        if (visiting()) {
            return;
        }
        // The last whole look found every block right and none went wrong since.
        if (site.stands(true, false) && site.census().skipped() == 0
            && station.right().cardinality() == picture.blueprint().blocks()) {
            built();
            return;
        }
        if (needs == null) {
            planTrip();
            return;
        }
        if (stock.dumpLeftovers(needs) || stock.restock(needs)) {
            return;
        }
        if (repairing) {
            repair();
        } else {
            walkStrips();
        }
    }

    // All that is left are blocks the server kept turning down. They get fresh attempts a minute
    // after their last click and turned down round after round they stop the printer.
    private void waitForRefused() {
        trip.stop();
        worker.standAside();
        int refused = picture.blueprint().blocks() - station.right().cardinality();
        if (waitingForgive || refused == 0 || site.anySettling()) {
            return;
        }
        waitingForgive = true;
        if (++forgiveWaits > REPAIR_ROUNDS) {
            disable(NAME + " could not finish " + Tally.counted(refused, "block") + " of " + station.file()
                + ". The server kept turning them down." + firstLeft());
        }
    }

    // Where the first block not known to be right lies as the end of a sentence. Empty when none is.
    private String firstLeft() {
        int first = firstCell(IntArrayList.wrap(walk),
            (cell, pos) -> kindOf(cell) != Blueprint.NO_SAY && !station.right().get(cell));
        return first == -1 ? "" : " The first is at " + BlockUtil.text(cellPos(first)) + ".";
    }

    private void built() {
        stopWork();
        resetTrip();
        ChatUtil.message(TAG + "printed §f" + station.file() + "§7 and makes its map.");
        station.reach(Stage.MAP);
        MapStations.save();
    }

    // Goes along the walk as far as the slots carry the blocks for. A trip that placed nothing
    // time after time means the inventory cannot take what the next block needs.
    private void planTrip() {
        if (waitingForgive && ticks % PLAN_TICKS != 0) {
            return;
        }
        stopWork();
        List<Item> items = new ArrayList<>();
        int first = -1;
        for (int cell : walk) {
            if (pending(cell, cellPos(cell))) {
                items.add(itemOf(cell));
                if (first == -1) {
                    first = cell;
                }
            }
        }
        if (items.isEmpty()) {
            waitForRefused();
            return;
        }
        waitingForgive = false;
        if (site.landed() == landedAtTrip && ++emptyTrips > TRIES) {
            disable(NAME + " could not get on with " + station.file() + ". It has no room for "
                + Printer.itemName(items.getFirst()) + " or found none.");
            return;
        }
        if (site.landed() != landedAtTrip) {
            emptyTrips = 0;
        }
        landedAtTrip = site.landed();
        MapPicture.Trip<Item> planned = stock.plan(items);
        if (planned.cells() == 0) {
            disable(NAME + " needs a free slot in your inventory.");
            return;
        }
        boolean wasRepairing = repairing;
        resetTrip();
        needs = planned.needs();
        strip = first % SIDE / width;
        repairing = wasRepairing;
        // The cells seen right outlast a restart.
        MapStations.save();
    }

    // Walks the strips in turn and stands one row behind the first row with a block to come.
    // The worker places whatever comes in reach. The trip ends at a block the inventory has
    // none of.
    private void walkStrips() {
        Frontier front = frontier(strip);
        while (front == null) {
            frontierFrom = 0;
            if (++strip >= MapPicture.strips(width)) {
                repairing = true;
                passed.clear();
                return;
            }
            front = frontier(strip);
        }
        if (front.lacking() != null) {
            endTrip();
            return;
        }
        int dir = MapPicture.southward(strip) ? 1 : -1;
        int stand = Math.clamp(front.row() - dir, 0, picture.rows() - 1);
        int middle = (MapPicture.stripWest(strip, width) + MapPicture.stripEast(strip, width) - 1) / 2;
        Walk walked = standAt(stand, middle, dir, stand == front.row());
        if (walked == Walk.STOPPED) {
            return;
        }
        worker.work(List.of(site), workRules());
        if (front.row() != stallRow || walked != Walk.THERE) {
            stallRow = front.row();
            stallTicks = 0;
        } else if (++stallTicks > ROW_STALL_TICKS) {
            passRow(strip, front.row());
            stallTicks = 0;
        }
    }

    // The first row of the strip in walk order with a block to come that is neither under the
    // player nor passed over. Null once there is none. Rows the walk has left behind are not
    // looked at again. The worker fills what it passes and the round at the end the rest.
    private Frontier frontier(int strip) {
        boolean south = MapPicture.southward(strip);
        int rows = picture.rows();
        for (int i = frontierFrom; i < rows; i++) {
            int row = south ? i : rows - 1 - i;
            boolean any = false;
            Item lacking = null;
            for (int x = MapPicture.stripWest(strip, width); x < MapPicture.stripEast(strip, width); x++) {
                int cell = row * SIDE + x;
                BlockPos pos = cellPos(cell);
                if (passed.contains(cell) || !pending(cell, pos) || BlockUtil.intersectsPlayer(pos)) {
                    continue;
                }
                any = true;
                Item item = itemOf(cell);
                if (stock.carrying(item) == 0) {
                    lacking = item;
                }
            }
            if (any) {
                frontierFrom = i;
                return new Frontier(row, lacking);
            }
        }
        return null;
    }

    // The blocks of a row the walk is stuck on wait for the round over what is left.
    private void passRow(int strip, int row) {
        for (int x = MapPicture.stripWest(strip, width); x < MapPicture.stripEast(strip, width); x++) {
            int cell = row * SIDE + x;
            if (pending(cell, cellPos(cell))) {
                passed.add(cell);
            }
        }
    }

    // Goes to each block still to come in turn and stands beside it. A round in which every one
    // was passed over starts another and a few such rounds stop the printer.
    private void repair() {
        if (repairCell == -1 || !pending(repairCell, cellPos(repairCell)) || passed.contains(repairCell)) {
            repairCell = nearestPending();
            stallTicks = 0;
            walkGoal = null;
        }
        if (repairCell == -1) {
            if (passed.isEmpty()) {
                // Nothing here to walk to. Planning the next trip finds out what is left to wait for.
                endTrip();
                return;
            }
            trip.stop();
            if (++repairRounds >= REPAIR_ROUNDS) {
                disable(NAME + " could not place " + Tally.counted(passed.size(), "block") + " of " + station.file()
                    + "." + firstLeft());
                return;
            }
            passed.clear();
            repairCells.clear();
            return;
        }
        if (stock.carrying(itemOf(repairCell)) == 0) {
            endTrip();
            return;
        }
        BlockPos target = cellPos(repairCell);
        Trip.State walked = pathTo(target, new Beside(target));
        worker.work(List.of(site), workRules());
        if (walked != Trip.State.WALKING && ++stallTicks > ROW_STALL_TICKS) {
            passed.add(repairCell);
        }
    }

    // The nearest block of the round still to come. A round starts with a look over the whole picture.
    private int nearestPending() {
        if (repairCells.isEmpty()) {
            for (int cell : walk) {
                if (pending(cell, cellPos(cell))) {
                    repairCells.add(cell);
                }
            }
        }
        return nearestCell(repairCells, (cell, pos) -> !passed.contains(cell) && pending(cell, pos));
    }

    private void endTrip() {
        trip.stop();
        worker.standAside();
        needs = null;
        walkGoal = null;
    }

    // Walking

    // Walks along the middle column of a strip towards the row it waits for and stops once past
    // the middle of the stand cell. The player is there from that middle up to the far edge of
    // the row waited for. At the start of a strip the walk goes into the row itself and the
    // player is there anywhere in it. That is where the width of the strip was worked out for. A
    // walk that got as near as it can counts as there as well.
    private Walk standAt(int stand, int middle, int dir, boolean start) {
        Vec3 centre = Vec3.atBottomCenterOf(cellPos(stand * SIDE + middle));
        Vec3 at = mc.player.position();
        double across = at.x - centre.x;
        double along = (at.z - centre.z) * dir;
        if (Math.abs(across) < IN_CELL && along >= (start ? -IN_CELL : 0) && along < (start ? IN_CELL : SLIDE)) {
            arrived();
            return Walk.THERE;
        }
        int row = start ? stand : stand + dir;
        return walkTo(cellPos(row * SIDE + middle));
    }

    @Override
    public Walk goTo(BlockPos spot) {
        if (new PathGoal.Column(spot.getX(), spot.getZ()).distance(mc.player.position()) <= AT_SPOT) {
            arrived();
            return Walk.THERE;
        }
        return walkTo(spot);
    }

    // Walks towards the column of the spot at whatever height the ground there lies. A walk
    // that keeps failing stops the printer.
    private Walk walkTo(BlockPos spot) {
        Trip.State state = pathTo(spot, new PathGoal.Column(spot.getX(), spot.getZ()));
        if (state == Trip.State.FAILED) {
            walkGoal = null;
            if (++failures >= TRIES) {
                halt(NAME + " could not find a way to " + spot.getX() + " " + spot.getZ() + ".");
            }
        }
        if (stopped()) {
            return Walk.STOPPED;
        }
        return state == Trip.State.ARRIVED ? Walk.THERE : Walk.ON_WAY;
    }

    // One tick of the walk to a goal. A walk to another goal or one a visit or a pause let go of
    // starts afresh.
    private Trip.State pathTo(BlockPos key, PathGoal goal) {
        Trip.State state = key.equals(walkGoal) ? trip.tick() : Trip.State.IDLE;
        if (state == Trip.State.IDLE) {
            walkGoal = key;
            trip.start(goal);
            state = trip.tick();
        }
        return state;
    }

    @Override
    public void arrived() {
        trip.stop();
        walkGoal = null;
        failures = 0;
    }

    @Override
    public void halt(String reason) {
        if (clearing.holdsWater()) {
            clearing.stopAfterRelease(reason);
            failures = 0;
            return;
        }
        disable(reason);
    }

    // Visits

    @Override
    public void openVisit(BlockPos pos, String name, Predicate<BlockState> fits, StationVisit.Work work) {
        visitClicks = 0;
        afterVisit = null;
        visit.open(pos, name, fits, work);
    }

    @Override
    public void afterVisit(Runnable then) {
        afterVisit = then;
    }

    @Override
    public boolean mayClick() {
        return ++visitClicks <= MOST_CLICKS;
    }

    @Override
    public boolean visiting() {
        if (!visit.busy()) {
            return false;
        }
        StationVisit.State state = visit.tick(faceTarget.getValue());
        if (stopped()) {
            // The work of the visit stopped the printer.
            return true;
        }
        if (state == StationVisit.State.FAILED) {
            afterVisit = null;
            if (++failures >= TRIES) {
                disable(visit.failure());
            }
        } else if (state == StationVisit.State.DONE) {
            failures = 0;
            Runnable then = afterVisit;
            afterVisit = null;
            if (then != null) {
                then.run();
            }
        }
        return visit.busy() || stopped();
    }

    // True once a stop part way through the tick has switched the printer off.
    private boolean stopped() {
        return !isEnabled();
    }

    // Drawing

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!show.isOn() || station == null) {
            return;
        }
        DrawBatch batch = event.getBatch();
        BuildGhost.drawBox(batch, style, footprint());
        for (Role role : Role.values()) {
            BlockPos pos = station.block(role);
            if (pos != null) {
                batch.outlineBlock(pos, CornerPicker.COLOR, true);
            }
        }
        for (MapStations.Chest chest : station.chests()) {
            batch.outlineBlock(chest.pos(), CornerPicker.COLOR, true);
        }
        if (site != null && station.stage() == Stage.BUILD) {
            ghost.draw(batch, style);
        }
    }
}
