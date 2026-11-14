package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.path.PathFinder;
import com.jellypudding.offlineclient.path.PathProbe;
import com.jellypudding.offlineclient.path.PathWalker;
import com.jellypudding.offlineclient.path.Travel;
import com.jellypudding.offlineclient.path.Trip;
import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigFailure;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.core.World;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

// Walks the goto command over courses built for it and over the game's own land with the movement
// modules a player keeps on. Every walk must arrive without looking for its way again and again
// and no module may make it slower or send it further than the plain walk from the same spot.
public final class GotoSuite implements Suite {

    private static final String NAME = "Goto";
    private static final WorldCreationUiState.SelectedGameMode CREATIVE =
        WorldCreationUiState.SelectedGameMode.CREATIVE;

    // The courses stand east of the stage a blanket run plays on.
    private static final int ARENA_EAST = 24;
    private static final int ARENA_NORTH = 12;
    private static final int ARENA = 24;
    private static final int WALL_HEIGHT = 4;
    private static final int HEADROOM = 9;
    private static final String WALL = "stone";

    // The longest a walk may take against the plain walk over the same course and the least
    // any walk is given.
    private static final int PATIENCE = 4;
    private static final int LEAST_LIMIT = 600;
    // A walk that ends on the block beside its goal is there. The far corner of that block lies this far off.
    private static final double THERE = 2.2;
    // A column the player comes back to after this many ticks away is the track crossing itself.
    private static final int AWAY_TICKS = 40;
    // A walk plans once at its start and once more for every stretch of water too wide to see
    // across. More than this over a short course is a walk that is lost.
    private static final int MOST_SEARCHES = 3;
    // A module may cost a walk this share of the plain walk's time and no more.
    private static final double SLOWEST = 1.05;

    private record Course(String name, BiConsumer<World, BlockPos> build, BlockPos start, BlockPos goal) {
    }

    // The modules a walk is made with and the rows set before it.
    private record Kit(String name, List<String> modules, List<String[]> rows) {
    }

    private record Walk(boolean there, int ticks, int searches, int crossings, double travelled, String said,
                        String stood) {
    }

    private static final List<Course> COURSES = List.of(
        new Course("the serpentine", GotoSuite::serpentine, new BlockPos(2, 0, 2), new BlockPos(21, 0, 2)),
        new Course("the terraces", GotoSuite::terraces, new BlockPos(1, 0, 3), new BlockPos(21, 6, 3)),
        new Course("the open diagonal", GotoSuite::pillars, new BlockPos(1, 0, 1), new BlockPos(22, 0, 22)));

    private static final String[] BHOP = {"Speed", "Mode", "bhop"};
    private static final List<Kit> KITS = List.of(
        new Kit("Speed", List.of("Speed"), List.of()),
        new Kit("Speed BHop", List.of("Speed"), List.<String[]>of(BHOP)),
        new Kit("Step", List.of("Step"), List.of()),
        new Kit("Speed and Step", List.of("Speed", "Step"), List.of()),
        new Kit("Speed BHop and Step", List.of("Speed", "Step"), List.<String[]>of(BHOP)));

    // Seeds of the game's own land and how far each walk across it goes. A walk heads for a
    // place on the map and finds the height itself.
    private static final List<String> SEEDS = List.of("offline one", "offline two");
    // Places on the map of each world far enough apart to lie in different kinds of land.
    private static final int[][] PLACES = {{-20, 91}, {-800, 2200}, {3000, -1200}, {700, 900}, {-1700, 400},
        {2200, 2600}};
    private static final int LAND_WALK = 96;
    private static final int[][] HEADINGS = {{1, 0}, {0, 1}};
    private static final String[] HEADING_NAMES = {"east", "south"};
    private static final int LAND_LIMIT = 1600;
    // A walk that covers less than a block in this many ticks stands still and is ended.
    private static final int STILL_TICKS = 160;
    private static final int NEVER_SET_OFF = 5;
    // A start with no way off it is moved this far east up to this many times.
    private static final int NUDGE = 7;
    private static final int NUDGES = 3;
    private static final Kit OWNER_KIT = new Kit("Speed and Step", List.of("Speed", "Step"), List.of());

    // The furthest a walk over land may go for each block of the straight line and the furthest a
    // walk with modules may go against the plain walk from the same place.
    private static final double ROUNDABOUT = 1.6;
    private static final double WANDERING = 1.2;
    private static final int LAND_SLACK = 5;

    private final Map<String, Walk> plain = new HashMap<>();
    private final Map<String, Walk> landPlain = new HashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        List<Case> cases = new ArrayList<>();
        for (Course course : COURSES) {
            cases.add(Case.of("a plain walk over " + course.name(), each -> plainWalk(each, course)));
            for (Kit kit : KITS) {
                cases.add(Case.of(kit.name() + " over " + course.name(), each -> kitWalk(each, course, kit)));
            }
        }
        List<Case> wanted = rig.wanted(NAME, cases);
        if (!wanted.isEmpty()) {
            rig.openWorld(CREATIVE);
            Stage stage = new Stage(rig);
            for (Case each : wanted) {
                stage.reset(false);
                rig.run(NAME, each);
                rig.client().command("goto stop");
                if (!stage.worldStands()) {
                    stage.reopen(CREATIVE);
                }
            }
            rig.closeWorld();
        }
        for (String seed : SEEDS) {
            land(rig, seed);
        }
    }

    // One world for each seed. The plain walk and the walk with the modules start from the same spot.
    private void land(Rig rig, String seed) {
        Map<Case, int[]> places = new LinkedHashMap<>();
        for (int[] place : PLACES) {
            for (int i = 0; i < HEADINGS.length; i++) {
                int[] heading = HEADINGS[i];
                String way = " " + HEADING_NAMES[i] + " from " + place[0] + " " + place[1] + " over the land of seed "
                    + seed;
                places.put(Case.of("a plain walk" + way, each -> landWalk(each, heading, null)), place);
                places.put(Case.of(OWNER_KIT.name() + way, each -> landWalk(each, heading, OWNER_KIT)), place);
            }
        }
        List<Case> wanted = rig.wanted(NAME, List.copyOf(places.keySet()));
        if (wanted.isEmpty()) {
            return;
        }
        rig.openLand(CREATIVE, seed);
        try {
            int[] at = null;
            Vec3 start = null;
            for (Case each : wanted) {
                rig.client().restoreDefaults();
                rig.world().gameMode("creative");
                int[] place = places.get(each);
                if (place != at) {
                    at = place;
                    start = wayOff(rig, place);
                } else if (start != null) {
                    rig.world().teleport(start.x, start.y, start.z, 0, 0);
                }
                if (start == null) {
                    rig.skip(NAME, each.name(), "The place lies under water");
                    continue;
                }
                rig.world().gameMode("survival");
                rig.run(NAME, each);
                rig.client().command("goto stop");
            }
        } finally {
            rig.closeWorld();
        }
    }

    // The ground at the place or a few blocks from it where a walk has somewhere to go. The top
    // of a spike of earth is ground too and nobody walks off it unhurt. Null when all of it lies
    // under water.
    private static Vec3 wayOff(Rig rig, int[] place) {
        Vec3 found = null;
        for (int nudge = 0; nudge <= NUDGES; nudge++) {
            found = rig.world().ground(place[0] + nudge * NUDGE, place[1]);
            if (found != null && rig.ask(mc -> PathProbe.stepCount()) > 0) {
                return found;
            }
        }
        return found;
    }

    private void landWalk(Rig rig, int[] heading, Kit kit) {
        if (kit != null) {
            for (String module : kit.modules()) {
                rig.client().enable(module);
            }
        }
        BlockPos start = rig.ask(mc -> mc.player.blockPosition());
        int x = start.getX() + heading[0] * LAND_WALK;
        int z = start.getZ() + heading[1] * LAND_WALK;
        String biome = rig.ask(mc -> mc.level.getBiome(start).unwrapKey().map(key -> key.identifier().getPath())
            .orElse("land with no name"));
        Walk walk = follow(rig, "goto " + x + " " + z, new Vec3(x + 0.5, 0, z + 0.5), LAND_LIMIT, true);
        rig.note("A straight line there is " + LAND_WALK + " blocks and the walk began in " + biome);
        judge(rig, walk);
        String place = start.getX() + " " + start.getZ() + " " + heading[0] + " " + heading[1];
        if (kit == null) {
            landPlain.put(place, walk);
            rig.expect(walk.travelled() <= LAND_WALK * ROUNDABOUT, "The walk covered " + (int) walk.travelled()
                + " blocks for " + LAND_WALK + " in a straight line");
            return;
        }
        Walk base = landPlain.get(place);
        if (base != null && base.there()) {
            rig.note("The plain walk took " + base.ticks() + " ticks over " + (int) base.travelled() + " blocks");
            rig.expect(walk.ticks() <= base.ticks() * SLOWEST, "The walk took " + walk.ticks()
                + " ticks and the plain walk " + base.ticks());
            rig.expect(walk.travelled() <= base.travelled() * WANDERING + LAND_SLACK, "The walk covered "
                + (int) walk.travelled() + " blocks and the plain walk " + (int) base.travelled());
        }
    }

    private void plainWalk(Rig rig, Course course) {
        Walk walk = plain(rig, course);
        judge(rig, walk);
    }

    private void kitWalk(Rig rig, Course course, Kit kit) {
        Walk base = plain(rig, course);
        for (String[] row : kit.rows()) {
            rig.client().set(row[0], row[1], row[2]);
        }
        for (String module : kit.modules()) {
            rig.client().enable(module);
        }
        Walk walk = walk(rig, course, Math.max(LEAST_LIMIT, base.ticks() * PATIENCE));
        rig.note("The plain walk took " + base.ticks() + " ticks");
        judge(rig, walk);
        rig.expect(walk.ticks() <= base.ticks() * SLOWEST, "The walk took " + walk.ticks()
            + " ticks and the plain walk " + base.ticks());
    }

    // The plain walk over a course is made once and every other walk over it is held against it.
    private Walk plain(Rig rig, Course course) {
        Walk known = plain.get(course.name());
        if (known == null) {
            known = walk(rig, course, LEAST_LIMIT * PATIENCE);
            plain.put(course.name(), known);
            rig.client().restoreDefaults();
        }
        return known;
    }

    private static void judge(Rig rig, Walk walk) {
        rig.note(String.format(Locale.ROOT, "%d ticks and %d searches and %d crossings of its own track over %.1f"
            + " blocks and the client said %s", walk.ticks(), walk.searches(), walk.crossings(), walk.travelled(),
            walk.said()));
        Stage.expectAlive(rig);
        rig.expect(walk.stood().isEmpty(), "The walk stood still " + walk.stood());
        rig.expect(walk.there(), "The walk did not arrive");
        rig.expect(walk.searches() <= MOST_SEARCHES, "The walk looked for its way " + walk.searches() + " times");
    }

    private static Walk walk(Rig rig, Course course, int limit) {
        World world = rig.world();
        BlockPos origin = origin(rig);
        build(world, origin);
        course.build().accept(world, origin);
        world.gameMode("survival");
        BlockPos start = origin.offset(course.start());
        BlockPos goal = origin.offset(course.goal());
        world.teleport(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
        return follow(rig, "goto " + goal.getX() + " " + goal.getY() + " " + goal.getZ(),
            Vec3.atBottomCenterOf(goal), limit, false);
    }

    // Types the command and watches the walk it starts until it ends or the limit is up. A goal
    // on the map counts as reached at any height.
    private static Walk follow(Rig rig, String command, Vec3 goal, int limit, boolean anyHeight) {
        int mark = rig.client().chatMark();
        rig.client().command(command);
        Map<Long, Integer> seen = new HashMap<>();
        Vec3 last = rig.ask(mc -> mc.player.position());
        double travelled = 0;
        int crossings = 0;
        int ticks = 0;
        Vec3 mark0 = last;
        int markTick = 0;
        String stood = "";
        StringBuilder trace = new StringBuilder("tick x y z ground bumped node nodes stray push searches busy\n");
        while (ticks < limit && rig.ask(mc -> Travel.walking())) {
            rig.ticks(1);
            ticks++;
            Vec3 now = rig.ask(mc -> mc.player.position());
            trace.append(ticks).append(' ').append(traced(rig)).append('\n');
            travelled += now.subtract(last).horizontalDistance();
            last = now;
            if (now.distanceTo(mark0) > 1) {
                mark0 = now;
                markTick = ticks;
            } else if (ticks - markTick > STILL_TICKS) {
                stood = standing(rig);
                rig.frames().shot("stood still " + ticks);
                break;
            }
            long column = BlockPos.asLong((int) Math.floor(now.x), 0, (int) Math.floor(now.z));
            Integer before = seen.put(column, ticks);
            if (before != null && ticks - before > AWAY_TICKS) {
                crossings++;
            }
        }
        if (ticks < NEVER_SET_OFF) {
            stood = "It never set off. " + where(rig) + " " + rig.ask(mc -> PathProbe.stepsFromPlayer());
            rig.frames().shot("never set off");
        }
        Vec3 end = anyHeight ? new Vec3(last.x, goal.y, last.z) : last;
        boolean there = !rig.ask(mc -> Travel.walking()) && end.distanceTo(goal) <= THERE;
        keep(rig, trace);
        List<String> said = rig.client().saidSince(mark);
        return new Walk(there, ticks, searches(rig), crossings, travelled, said.isEmpty() ? "nothing" : said.getLast(),
            stood);
    }

    // One line of a walk's trace. The walker keeps its state to itself.
    private static String traced(Rig rig) {
        return rig.ask(mc -> {
            try {
                Trip trip = trip();
                PathWalker walker = trip.walker();
                List<?> nodes = (List<?>) walkerField(walker, "path");
                int index = walkerInt(walker, "index");
                BlockPos node = index < nodes.size() ? (BlockPos) nodes.get(index) : null;
                String ahead = node == null ? "none" : node.toShortString().replace(" ", "") + " "
                    + mc.level.getBlockState(node).getBlock() + " on "
                    + mc.level.getBlockState(node.below()).getBlock();
                return String.format(Locale.ROOT, "%.2f %.2f %.2f %s %s %d %d %d %d %d %s under %s node %s",
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.onGround() ? "g" : "a",
                    mc.player.horizontalCollision ? "b" : "f", index, nodes.size(), walkerInt(walker, "strayTicks"),
                    walkerInt(walker, "pushTicks"), tripSearches(trip), trip.finder().busy() ? "s" : "w",
                    mc.level.getBlockState(BlockPos.containing(mc.player.getX(), mc.player.getY() - 0.2,
                        mc.player.getZ())).getBlock(), ahead);
            } catch (ReflectiveOperationException e) {
                throw new RigFailure("The walk no longer keeps its state where the rig looks", e);
            }
        });
    }

    private static Trip trip() throws ReflectiveOperationException {
        Field instance = Travel.class.getDeclaredField("INSTANCE");
        Field trip = Travel.class.getDeclaredField("trip");
        instance.setAccessible(true);
        trip.setAccessible(true);
        return (Trip) trip.get(instance.get(null));
    }

    private static Object walkerField(PathWalker walker, String name) throws ReflectiveOperationException {
        Field field = PathWalker.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(walker);
    }

    private static int walkerInt(PathWalker walker, String name) throws ReflectiveOperationException {
        return (Integer) walkerField(walker, name);
    }

    private static int tripSearches(Trip trip) throws ReflectiveOperationException {
        Field searches = Trip.class.getDeclaredField("searches");
        searches.setAccessible(true);
        return searches.getInt(trip);
    }

    // The trace of the walk that just ended goes beside the results under the name of its case.
    private static void keep(Rig rig, StringBuilder trace) {
        Path file = rig.results().out().resolve("traces").resolve(rig.caseId().replaceAll("[^A-Za-z0-9]+", "_")
            + ".txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, trace, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RigFailure("The trace of a walk could not be written to " + file, e);
        }
    }

    // The blocks round the feet of a player whose walk ended before it began.
    private static String where(Rig rig) {
        return rig.ask(mc -> {
            BlockPos feet = mc.player.blockPosition();
            return "The player is at " + mc.player.position() + " on ground " + mc.player.onGround() + " with "
                + mc.level.getBlockState(feet).getBlock() + " at the feet and " + mc.level.getBlockState(feet.below())
                .getBlock() + " below and " + mc.level.getBlockState(feet.above()).getBlock() + " at the head";
        });
    }

    // Where a walk that no longer moves stands and what it holds down.
    private static String standing(Rig rig) {
        return rig.ask(mc -> {
            try {
                Field instance = Travel.class.getDeclaredField("INSTANCE");
                Field trip = Travel.class.getDeclaredField("trip");
                instance.setAccessible(true);
                trip.setAccessible(true);
                PathWalker walker = ((Trip) trip.get(instance.get(null))).walker();
                Field path = PathWalker.class.getDeclaredField("path");
                Field index = PathWalker.class.getDeclaredField("index");
                path.setAccessible(true);
                index.setAccessible(true);
                List<?> nodes = (List<?>) path.get(walker);
                int next = index.getInt(walker);
                BlockPos feet = mc.player.blockPosition();
                return "at " + mc.player.position() + " standing at " + PathFinder.standingAt(mc.player)
                    + " node " + next + " of " + nodes.size()
                    + " before " + (next > 0 && next <= nodes.size() ? nodes.get(next - 1) : "none")
                    + " next " + (next < nodes.size() ? nodes.get(next) : "none")
                    + " ground " + mc.player.onGround() + " water " + mc.player.isInWater()
                    + " bumped " + mc.player.horizontalCollision
                    + " feet " + mc.level.getBlockState(feet).getBlock()
                    + " below " + mc.level.getBlockState(feet.below()).getBlock()
                    + " head " + mc.level.getBlockState(feet.above()).getBlock()
                    + " keys up " + mc.options.keyUp.isDown() + " jump " + mc.options.keyJump.isDown()
                    + " sneak " + mc.options.keyShift.isDown() + " busy " + ((Trip) trip.get(instance.get(null)))
                        .finder().busy();
            } catch (ReflectiveOperationException e) {
                throw new RigFailure("The walk no longer keeps its path where the rig looks", e);
            }
        });
    }

    // How often the walk that just ended looked for a way. The client keeps the count to itself.
    private static int searches(Rig rig) {
        return rig.ask(mc -> {
            try {
                return tripSearches(trip());
            } catch (ReflectiveOperationException e) {
                throw new RigFailure("The walk no longer counts its searches where the rig looks", e);
            }
        });
    }

    private static BlockPos origin(Rig rig) {
        BlockPos home = rig.ask(mc -> mc.player.blockPosition());
        return home.offset(ARENA_EAST, 0, -ARENA_NORTH);
    }

    // A flat yard with a wall round it and nothing in it.
    private static void build(World world, BlockPos origin) {
        BlockPos low = origin.offset(-1, 0, -1);
        BlockPos high = origin.offset(ARENA, 0, ARENA);
        world.refill(low.below(), high.below(), "grass_block");
        world.refill(low, high.above(HEADROOM), "air");
        world.refill(low, new BlockPos(high.getX(), low.getY() + WALL_HEIGHT, low.getZ()), WALL);
        world.refill(new BlockPos(low.getX(), low.getY(), high.getZ()), high.above(WALL_HEIGHT), WALL);
        world.refill(low, new BlockPos(low.getX(), low.getY() + WALL_HEIGHT, high.getZ()), WALL);
        world.refill(new BlockPos(high.getX(), low.getY(), low.getZ()), high.above(WALL_HEIGHT), WALL);
    }

    // Three walls with a gap at alternate ends. The way winds round each of them.
    private static void serpentine(World world, BlockPos origin) {
        wall(world, origin, 6, 0, 6, 17);
        wall(world, origin, 12, 6, 12, 23);
        wall(world, origin, 18, 0, 18, 17);
    }

    // Six terraces each one block higher than the one before it and the full width of the yard.
    private static void terraces(World world, BlockPos origin) {
        for (int level = 1; level <= 6; level++) {
            world.fill(origin.offset(3 * level, 0, 0), origin.offset(ARENA - 1, level - 1, 6), WALL);
        }
        wall(world, origin, 0, 7, ARENA - 1, 7);
    }

    // Open ground with pillars strewn over it. The goal lies on the far corner.
    private static void pillars(World world, BlockPos origin) {
        for (int x = 4; x < ARENA - 2; x += 5) {
            for (int z = 3; z < ARENA - 2; z += 4) {
                wall(world, origin, x + z % 3, z, x + z % 3, z);
            }
        }
    }

    private static void wall(World world, BlockPos origin, int fromX, int fromZ, int toX, int toZ) {
        world.fill(origin.offset(fromX, 0, fromZ), origin.offset(toX, WALL_HEIGHT - 1, toZ), WALL);
    }
}
