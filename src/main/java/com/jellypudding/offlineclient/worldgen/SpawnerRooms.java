package com.jellypudding.offlineclient.worldgen;

import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChunkScanner;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

// The rooms the world builds round the spawners it generates. SpawnerPlace tells which structure
// made a spawner. Dungeon rooms and spider corridors are known by their blocks whether or not
// their spawner still stands. MonsterRoomFeature and MineshaftPieces fill both with cave air and
// a block a player breaks leaves plain air. Chunks from before cave air existed hold none and
// are never judged.
public final class SpawnerRooms {

    // A room the scan recognised. The key is a dungeon's spawner spot or the floor of a
    // corridor's middle row at its lowest end. Standing means its spawner is there. Mined
    // means a player took the spawner. Disturbed means plain air where a block was broken.
    public record Room(SpawnerPlace place, BlockPos key, AABB box, boolean standing, boolean mined, boolean disturbed,
                       int chests) {
    }

    // One step of a spider corridor run and what its six cells held.
    private record Run(Direction.Axis axis, int across, int floor, int first, int last, int webs, int caveAir,
                       boolean plainAir, boolean spawner) {

        int length() {
            return last - first + 1;
        }
    }

    // A dungeon's inside reaches two or three blocks each way from its spawner and four up.
    private static final int SMALL_HALF = 2;
    private static final int LARGE_HALF = 3;
    private static final int ROOM_HEIGHT = 4;
    // Caves eat into floors and walls and players change a few blocks. A fit needs a good part of
    // each. Three floor blocks in four start mossy.
    private static final double FLOOR_SHARE = 0.8;
    private static final double SOLID_FLOOR_SHARE = 0.25;
    private static final double MOSSY_SHARE = 0.4;
    private static final double WALL_SHARE = 0.3;
    private static final double CAVE_AIR_SHARE = 0.5;
    // Pointed dripstone and fallen gravel can end up inside.
    private static final int INSIDE_SOLIDS = 2;

    // A spider corridor is three wide and two high below its supports and its cobwebs fill
    // three cells in five.
    private static final int CORRIDOR_WIDTH = 3;
    private static final int CORRIDOR_HEIGHT = 2;
    private static final int SHORTEST_RUN = 5;
    // The longest corridor is twenty blocks. A run grows no further than a few more each way
    // from where it starts.
    private static final int CORRIDOR_LENGTH = 20;
    private static final int LONGEST_RUN = 24;
    // A support whose middle holds no cobweb can stand beside a section that holds none either.
    private static final int LONGEST_GAP = 2;
    private static final double WEB_SHARE = 0.35;
    private static final List<Direction.Axis> ALONG = List.of(Direction.Axis.X, Direction.Axis.Z);
    // Slots of the tally the cross sections of a corridor add to.
    private static final int WEBS = 0;
    private static final int CAVE_AIR = 1;
    private static final int PLAIN_AIR = 2;
    private static final int SPAWNERS = 3;
    // Plain air anywhere but the middle of the floor. A broken rail leaves plain air only there.
    private static final int OFF_RAIL_AIR = 4;
    private static final int TALLY_SIZE = 5;

    // Round a stronghold portal room spawner. The room reaches further but players stand here.
    private static final int PORTAL_ROOM_REACH = 3;
    private static final int PORTAL_ROOM_DEPTH = 2;

    // How far round a room water lava and fire are looked for.
    private static final int FLOW_REACH = 4;

    private SpawnerRooms() {
    }

    // Every dungeon and spider corridor the scanned chunk owns. It waits for the chunks round it.
    public static void scan(ChunkScanner.View view, List<Room> out) {
        if (!view.complete()) {
            return;
        }
        SeenBlocks world = view::get;
        LongSet tried = new LongOpenHashSet();
        view.forEachMatching(state -> state.is(Blocks.MOSSY_COBBLESTONE), (x, y, z, state) -> {
            // The floor reaches one block past the inside on every side.
            int reach = LARGE_HALF + 1;
            for (int cx = x - reach; cx <= x + reach; cx++) {
                for (int cz = z - reach; cz <= z + reach; cz++) {
                    BlockPos centre = new BlockPos(cx, y + 1, cz);
                    if (owns(view, centre) && tried.add(centre.asLong())) {
                        Room room = dungeon(world, centre);
                        if (room != null) {
                            out.add(room);
                        }
                    }
                }
            }
        });
        LongSet walked = new LongOpenHashSet();
        view.forEachMatching(state -> state.is(Blocks.COBWEB), (x, y, z, state) -> {
            if (walked.contains(BlockPos.asLong(x, y, z))) {
                return;
            }
            Room room = corridorThrough(world, x, y, z);
            if (room == null) {
                return;
            }
            // The other cobwebs of the corridor lead to the same room.
            BlockPos.betweenClosedStream(room.box().contract(1, 1, 1)).forEach(cell -> walked.add(cell.asLong()));
            if (owns(view, room.key())) {
                out.add(room);
            }
        });
    }

    // The spider corridor a cobweb hangs in or null.
    private static Room corridorThrough(SeenBlocks world, int x, int y, int z) {
        Run run = bestRun(world, x, y, z, false);
        return run == null ? null : corridor(world, run);
    }

    // The room a structure spawner stands in or null when its place has none or the blocks
    // there do not match one.
    public static Room around(SpawnerPlace place, SeenBlocks world, BlockPos spawner) {
        return switch (place) {
            case DUNGEON -> dungeon(world, spawner);
            case MINESHAFT -> {
                Run run = bestRun(world, spawner.getX(), spawner.getY(), spawner.getZ(), true);
                yield run == null ? null : corridor(world, run);
            }
            case STRONGHOLD -> portalRoom(world, spawner);
            default -> null;
        };
    }

    private static boolean owns(ChunkScanner.View view, BlockPos pos) {
        return SectionPos.blockToSectionCoord(pos.getX()) == view.pos().x()
            && SectionPos.blockToSectionCoord(pos.getZ()) == view.pos().z();
    }

    // A dungeon is cave air over its spawner spot. Lichen dripstone or vines may fill a cell.
    private static boolean openAbove(SeenBlocks world, BlockPos centre) {
        for (int dy = 1; dy < ROOM_HEIGHT; dy++) {
            if (world.at(centre.getX(), centre.getY() + dy, centre.getZ()).is(Blocks.CAVE_AIR)) {
                return true;
            }
        }
        return false;
    }

    // The dungeon round a spawner spot or null when the blocks there are no dungeon. A size
    // too small puts the walls inside the room and one too big puts them in the floor plan.
    public static Room dungeon(SeenBlocks world, BlockPos centre) {
        if (!openAbove(world, centre)) {
            return null;
        }
        for (int halfX = SMALL_HALF; halfX <= LARGE_HALF; halfX++) {
            for (int halfZ = SMALL_HALF; halfZ <= LARGE_HALF; halfZ++) {
                if (floorFits(world, centre, halfX, halfZ) && wallsFit(world, centre, halfX, halfZ)) {
                    Room room = inside(world, centre, halfX, halfZ);
                    if (room != null) {
                        return room;
                    }
                }
            }
        }
        return null;
    }

    // The floor is cobblestone three blocks in four mossy. Over a cave generation turns it to
    // cave air instead.
    private static boolean floorFits(SeenBlocks world, BlockPos centre, int halfX, int halfZ) {
        int cells = 0;
        int cobble = 0;
        int mossy = 0;
        int open = 0;
        for (int dx = -halfX - 1; dx <= halfX + 1; dx++) {
            for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++) {
                BlockState state = world.at(centre.getX() + dx, centre.getY() - 1, centre.getZ() + dz);
                cells++;
                if (state.is(Blocks.MOSSY_COBBLESTONE)) {
                    mossy++;
                } else if (state.is(Blocks.COBBLESTONE)) {
                    cobble++;
                } else if (state.is(Blocks.CAVE_AIR)) {
                    open++;
                }
            }
        }
        int solid = mossy + cobble;
        return solid + open >= cells * FLOOR_SHARE && solid >= cells * SOLID_FLOOR_SHARE
            && mossy >= solid * MOSSY_SHARE;
    }

    // Walls are cobblestone wherever solid ground stood. A cave along a side leaves little of
    // it but a size that is off puts a whole side inside the room or out in the rock with none.
    private static boolean wallsFit(SeenBlocks world, BlockPos centre, int halfX, int halfZ) {
        int cells = 0;
        int cobble = 0;
        for (int side = -1; side <= 1; side += 2) {
            int x = centre.getX() + side * (halfX + 1);
            int z = centre.getZ() + side * (halfZ + 1);
            int[] across = sideCobble(world, centre, x, x, centre.getZ() - halfZ - 1, centre.getZ() + halfZ + 1);
            int[] along = sideCobble(world, centre, centre.getX() - halfX, centre.getX() + halfX, z, z);
            if (across[1] == 0 || along[1] == 0) {
                return false;
            }
            cells += across[0] + along[0];
            cobble += across[1] + along[1];
        }
        return cobble >= cells * WALL_SHARE;
    }

    // How many cells one side of the walls has and how many of them are cobblestone.
    private static int[] sideCobble(SeenBlocks world, BlockPos centre, int x0, int x1, int z0, int z1) {
        int cells = 0;
        int cobble = 0;
        for (int y = centre.getY(); y < centre.getY() + ROOM_HEIGHT; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    cells++;
                    cobble += cobble(world.at(x, y, z)) ? 1 : 0;
                }
            }
        }
        return new int[] {cells, cobble};
    }

    private static boolean cobble(BlockState state) {
        return state.is(Blocks.COBBLESTONE) || state.is(Blocks.MOSSY_COBBLESTONE);
    }

    // The inside of a fitted dungeon or null when it holds too much that no dungeon would.
    private static Room inside(SeenBlocks world, BlockPos centre, int halfX, int halfZ) {
        int cells = 0;
        int caveAir = 0;
        int solids = 0;
        int chests = 0;
        boolean plainAir = false;
        for (int dx = -halfX; dx <= halfX; dx++) {
            for (int dy = 0; dy < ROOM_HEIGHT; dy++) {
                for (int dz = -halfZ; dz <= halfZ; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockState state = world.at(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    cells++;
                    if (state.is(Blocks.CAVE_AIR)) {
                        caveAir++;
                    } else if (state.is(Blocks.AIR)) {
                        plainAir = true;
                    } else if (state.is(Blocks.CHEST)) {
                        chests++;
                    } else if (BlockUtil.blocksMotion(state)) {
                        solids++;
                    }
                }
            }
        }
        if (solids > INSIDE_SOLIDS || caveAir < cells * CAVE_AIR_SHARE) {
            return null;
        }
        AABB box = new AABB(centre.getX() - halfX, centre.getY(), centre.getZ() - halfZ,
            centre.getX() + halfX + 1, centre.getY() + ROOM_HEIGHT, centre.getZ() + halfZ + 1);
        BlockState middle = world.at(centre.getX(), centre.getY(), centre.getZ());
        // Generation always sets the spawner. Cave air there means the fit is off centre.
        if (middle.is(Blocks.CAVE_AIR)) {
            return null;
        }
        boolean standing = middle.is(Blocks.SPAWNER);
        // The feature could not replace a block like bedrock and left the room without a spawner.
        boolean mined = !standing && !middle.is(BlockTags.FEATURES_CANNOT_REPLACE);
        boolean disturbed = (plainAir || mined && middle.is(Blocks.AIR)) && !nearFlow(world, box);
        return new Room(SpawnerPlace.DUNGEON, centre.immutable(), box, standing, mined, disturbed, chests);
    }

    // The cobweb corridor through a spot or null. A spawner spot is known to be the middle of
    // the floor row. Any other cell may sit anywhere in the three by two cross section. The
    // true section holds the most cobwebs and a section beside it takes in cave rock or air.
    private static Run bestRun(SeenBlocks world, int x, int y, int z, boolean spawnerSpot) {
        Run best = null;
        for (Direction.Axis axis : ALONG) {
            int across = axis == Direction.Axis.X ? z : x;
            for (int offset = 0; offset < CORRIDOR_WIDTH; offset++) {
                for (int up = 0; up < CORRIDOR_HEIGHT; up++) {
                    if (spawnerSpot && (offset != 1 || up != 0)) {
                        continue;
                    }
                    Run run = run(world, axis, across - offset, y - up, axis == Direction.Axis.X ? x : z);
                    if (run != null && (best == null || run.webs() > best.webs()
                        || run.webs() == best.webs() && run.caveAir() > best.caveAir())) {
                        best = run;
                    }
                }
            }
        }
        return best;
    }

    // The run of corridor sections along an axis through one section. It grows each way over
    // sections that carry the corridor on and across a short gap between them. Every cobweb of
    // a corridor then leads to the same run. Null when it is too short or holds too few cobwebs
    // or no cave air.
    private static Run run(SeenBlocks world, Direction.Axis axis, int across, int floor, int start) {
        int[] tally = section(world, axis, across, floor, start);
        if (tally == null) {
            return null;
        }
        int first = start - grow(world, axis, across, floor, start, -1, tally);
        int last = start + grow(world, axis, across, floor, start, 1, tally);
        Run run = new Run(axis, across, floor, first, last, tally[WEBS], tally[CAVE_AIR],
            tally[PLAIN_AIR] > 0, tally[SPAWNERS] > 0);
        int cells = run.length() * CORRIDOR_WIDTH * CORRIDOR_HEIGHT;
        return run.length() >= SHORTEST_RUN && run.webs() >= cells * WEB_SHARE && run.caveAir() > 0 ? run : null;
    }

    // How many sections the run takes in one way from where it starts. What they hold goes in
    // the tally.
    private static int grow(SeenBlocks world, Direction.Axis axis, int across, int floor, int start, int step,
                            int[] tally) {
        int taken = 0;
        int[] gap = new int[TALLY_SIZE];
        for (int far = 1; far <= LONGEST_RUN; far++) {
            int[] counts = section(world, axis, across, floor, start + step * far);
            if (counts == null || !carriesOn(counts) && far - taken > LONGEST_GAP) {
                break;
            }
            add(gap, counts);
            if (carriesOn(counts)) {
                add(tally, gap);
                gap = new int[TALLY_SIZE];
                taken = far;
            }
        }
        return taken;
    }

    // What one cross section holds. Null when a cell could not belong to a spider corridor.
    // Rails lichen and water may lie in one and a support puts fences at its sides.
    private static int[] section(SeenBlocks world, Direction.Axis axis, int across, int floor, int along) {
        int[] counts = new int[TALLY_SIZE];
        for (int side = 0; side < CORRIDOR_WIDTH; side++) {
            for (int up = 0; up < CORRIDOR_HEIGHT; up++) {
                BlockState state = axis == Direction.Axis.X
                    ? world.at(along, floor + up, across + side) : world.at(across + side, floor + up, along);
                if (state.is(Blocks.COBWEB)) {
                    counts[WEBS]++;
                } else if (state.is(Blocks.CAVE_AIR)) {
                    counts[CAVE_AIR]++;
                } else if (state.is(Blocks.AIR)) {
                    counts[PLAIN_AIR]++;
                    if (side != CORRIDOR_WIDTH / 2 || up != 0) {
                        counts[OFF_RAIL_AIR]++;
                    }
                } else if (state.is(Blocks.SPAWNER)) {
                    counts[SPAWNERS]++;
                } else if (BlockUtil.blocksMotion(state) && !(state.getBlock() instanceof FenceBlock)) {
                    return null;
                }
            }
        }
        return counts;
    }

    // A section with a cobweb or the spawner or the plain air of a broken cobweb beside cave air
    // in it. A plain corridor or a crossing in line with a spider corridor holds none of them
    // and neither does a tunnel a player dug.
    private static boolean carriesOn(int[] counts) {
        return counts[WEBS] + counts[SPAWNERS] > 0 || counts[OFF_RAIL_AIR] > 0 && counts[CAVE_AIR] > 0;
    }

    private static void add(int[] tally, int[] counts) {
        for (int slot = 0; slot < TALLY_SIZE; slot++) {
            tally[slot] += counts[slot];
        }
    }

    private static Room corridor(SeenBlocks world, Run run) {
        boolean alongX = run.axis() == Direction.Axis.X;
        BlockPos key = alongX ? new BlockPos(run.first(), run.floor(), run.across() + 1)
            : new BlockPos(run.across() + 1, run.floor(), run.first());
        AABB box = alongX
            ? new AABB(run.first(), run.floor(), run.across(), run.last() + 1, run.floor() + CORRIDOR_HEIGHT,
                run.across() + CORRIDOR_WIDTH)
            : new AABB(run.across(), run.floor(), run.first(), run.across() + CORRIDOR_WIDTH,
                run.floor() + CORRIDOR_HEIGHT, run.last() + 1);
        boolean spawner = run.spawner() || spawnerInLine(world, run);
        boolean disturbed = run.plainAir() && !nearFlow(world, box);
        // Generation skips the spawner now and then. Only broken webs with it tell of a player.
        boolean mined = !spawner && disturbed;
        return new Room(SpawnerPlace.MINESHAFT, key, box, spawner, mined, disturbed, 0);
    }

    // A block the scan could not pass may cut a corridor short. Its spawner then stands in
    // the middle of the floor further along the same line within one corridor length. A line
    // that runs out of the loaded chunks may hold it as well.
    private static boolean spawnerInLine(SeenBlocks world, Run run) {
        int middle = run.across() + 1;
        for (int step = 1; step <= CORRIDOR_LENGTH; step++) {
            for (int along : new int[] {run.first() - step, run.last() + step}) {
                BlockState state = run.axis() == Direction.Axis.X
                    ? world.at(along, run.floor(), middle) : world.at(middle, run.floor(), along);
                if (state.is(Blocks.SPAWNER) || state.is(Blocks.VOID_AIR)) {
                    return true;
                }
            }
        }
        return false;
    }

    // The part of a stronghold portal room round its spawner. The room is cave air inside.
    private static Room portalRoom(SeenBlocks world, BlockPos spawner) {
        boolean caveAir = false;
        boolean plainAir = false;
        for (int dx = -PORTAL_ROOM_REACH; dx <= PORTAL_ROOM_REACH; dx++) {
            for (int dy = -PORTAL_ROOM_DEPTH; dy <= PORTAL_ROOM_DEPTH; dy++) {
                for (int dz = -PORTAL_ROOM_REACH; dz <= PORTAL_ROOM_REACH; dz++) {
                    BlockState state = world.at(spawner.getX() + dx, spawner.getY() + dy, spawner.getZ() + dz);
                    caveAir |= state.is(Blocks.CAVE_AIR);
                    plainAir |= state.is(Blocks.AIR);
                }
            }
        }
        if (!caveAir) {
            return null;
        }
        AABB box = new AABB(spawner).inflate(PORTAL_ROOM_REACH, PORTAL_ROOM_DEPTH, PORTAL_ROOM_REACH);
        return new Room(SpawnerPlace.STRONGHOLD, spawner.immutable(), box, true, false, plainAir, 0);
    }

    // True when water lava or fire lies within reach of the box. Fire burns supports away and
    // a flow cut off from its source draws back and leaves plain air behind.
    private static boolean nearFlow(SeenBlocks world, AABB box) {
        AABB reach = box.inflate(FLOW_REACH);
        for (int x = (int) Math.floor(reach.minX); x < reach.maxX; x++) {
            for (int y = (int) Math.floor(reach.minY); y < reach.maxY; y++) {
                for (int z = (int) Math.floor(reach.minZ); z < reach.maxZ; z++) {
                    BlockState state = world.at(x, y, z);
                    if (!state.getFluidState().isEmpty() || state.getBlock() instanceof BaseFireBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
