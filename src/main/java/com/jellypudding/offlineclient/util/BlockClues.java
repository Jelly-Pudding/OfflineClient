package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.worldgen.BaseBlocks;
import com.jellypudding.offlineclient.worldgen.NaturalBlocks;
import com.jellypudding.offlineclient.worldgen.SeenBlocks;
import com.jellypudding.offlineclient.worldgen.StructureBeds;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SpeleothemBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

// Runs on a scanner thread with the settings as they stood. Every rule was checked against the
// structure templates and world generation of 26.3.
public final class BlockClues {

    // The words read after found such as a lit nether portal. A sign comes without words and the
    // game thread reads what it says.
    public record Hit(BaseClue clue, BlockPos pos, String what) {
    }

    // Needed holds how many of each picked block alone mark a chunk. The margins are layers left
    // out at the bottom and top. Growth is how many grown plants mark a chunk.
    public record Rules(Set<BaseClue> clues, Map<Block, Integer> needed, int skyHeight, int floorMargin,
                        int ceilingMargin, boolean paper, ResourceKey<Level> dimension, int growth) {
    }

    // The floor and the nether roof are each five layers of bedrock at most.
    private static final int BEDROCK_LAYERS = 5;
    // The nether generator stops at this height. Its roof ends there and only air lies above.
    private static final int NETHER_ROOF_TOP = 127;
    // Every structure that holds a spawner has blocks of its own this close to it.
    private static final int COMPANION_REACH = 5;
    // The igloo's snow touches its bed.
    private static final int TOUCHING = 1;
    // A stash room is a few blocks across. A larger hollow is never walled in by bedrock alone.
    private static final int MAX_ROOM = 128;
    // Thirds and sixths never add up to exactly one in floating point.
    private static final double ROUNDING = 1e-9;
    // The one deepslate the world lays on its side stands in an ancient city pillar with deepslate
    // tile stairs this close. Nothing the world does later takes those stairs away.
    private static final int PILLAR_REACH = 2;
    // Leaves hang vines four blocks free and a swamp camp hangs one five blocks free. A run this
    // long grew three times or more and that takes about an hour near a player.
    private static final int GROWN_VINE = 8;
    // The world grows kelp ten long at most and ends it two under the surface. A column this long
    // that reaches the air grew four times or more and that takes about half an hour near a player.
    private static final int GROWN_KELP = 14;
    // The world grows stalactites six long and stalagmites seven long at most.
    private static final int GROWN_STALACTITE = 7;
    private static final int GROWN_STALAGMITE = 8;

    // Rooms are told apart by their lowest corner. A room across a chunk border marks one chunk.
    private static final Comparator<BlockPos> CORNER_FIRST = Comparator.comparingInt((BlockPos pos) -> pos.getX())
        .thenComparingInt(pos -> pos.getZ()).thenComparingInt(pos -> pos.getY());

    private BlockClues() {
    }

    public static void scan(Rules rules, ChunkScanner.View view, List<Hit> out) {
        new Reading(rules, view, out).run();
    }

    private static final class Picked {
        private final Map<Block, Integer> counts = new HashMap<>();
        private final Map<Block, BlockPos> tops = new HashMap<>();
        private int total;

        private void add(Block block, int x, int y, int z) {
            total++;
            counts.merge(block, 1, Integer::sum);
            BlockPos top = tops.get(block);
            if (top == null || y > top.getY()) {
                tops.put(block, new BlockPos(x, y, z));
            }
        }

        // Each block held counts as its share of the number that alone marks a chunk. Different
        // blocks add up and a whole share marks it.
        private boolean enough(Map<Block, Integer> needed) {
            double share = 0;
            for (Map.Entry<Block, Integer> entry : counts.entrySet()) {
                share += (double) entry.getValue() / needed.get(entry.getKey());
            }
            return total > 0 && share >= 1 - ROUNDING;
        }

        private Block most() {
            return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        }

        private String words() {
            Block most = most();
            return total == 1 ? ChatUtil.withArticle(name(most))
                : total + " " + BaseClue.PLAYER_BLOCKS.noun() + " such as " + name(most);
        }
    }

    private static final class Reading {

        private final Rules rules;
        private final ChunkScanner.View view;
        private final List<Hit> out;
        private final int low;
        private final int high;
        // A missing chunk reads as air. On Paper a block only counts once its neighbours are known.
        private final boolean settled;
        private final Set<BaseClue> found = EnumSet.noneOf(BaseClue.class);
        private final Picked picked = new Picked();
        private final Growth growth = new Growth();
        private final BlockGetter getter;

        private Reading(Rules rules, ChunkScanner.View view, List<Hit> out) {
            this.rules = rules;
            this.view = view;
            this.out = out;
            low = view.minY() + rules.floorMargin();
            high = view.maxY() - rules.ceilingMargin();
            settled = !rules.paper() || view.complete();
            getter = new ViewBlocks(view);
        }

        private void run() {
            if (low > high) {
                return;
            }
            placedBlocks();
            if (settled && wants(BaseClue.BEDROCK)) {
                bedrock();
            }
            if (settled && wants(BaseClue.SKY) && in(Level.OVERWORLD)) {
                sky();
            }
            if (settled && wants(BaseClue.ROOF) && in(Level.NETHER)) {
                roof();
            }
            if (view.complete() && wants(BaseClue.ROOM)) {
                rooms();
            }
            if (picked.enough(rules.needed())) {
                out.add(new Hit(BaseClue.PLAYER_BLOCKS, picked.tops.get(picked.most()), picked.words()));
            }
            if (growth.count >= rules.growth()) {
                out.add(new Hit(BaseClue.GROWTH, growth.first, growth.words()));
            }
        }

        private void placedBlocks() {
            boolean counting = settled && !rules.needed().isEmpty();
            view.forEachMatching(low, high, state -> placedClue(state) || (counting && picked(state)),
                (x, y, z, state) -> {
                    Block block = state.getBlock();
                    if (block instanceof SignBlock && wants(BaseClue.SIGN)) {
                        out.add(new Hit(BaseClue.SIGN, new BlockPos(x, y, z), ""));
                    } else if (lit(state)) {
                        once(BaseClue.PORTAL, x, y, z, state.is(Blocks.END_PORTAL) ? "a lit end portal"
                            : "a lit nether portal");
                    } else if (rising(state)) {
                        once(BaseClue.BUBBLES, x, y, z, "a bubble column over soul sand");
                    } else if (block == Blocks.SPAWNER && wants(BaseClue.SPAWNER) && view.complete()
                        && lone(x, y, z)) {
                        once(BaseClue.SPAWNER, x, y, z, "a lone spawner");
                    } else if (bedHead(state) && view.complete() && !structureBed(x, y, z, block)) {
                        out.add(new Hit(BaseClue.BED, new BlockPos(x, y, z), "a lone " + name(block)));
                    } else if (sideways(state) && view.complete() && !inPillar(x, y, z)) {
                        once(BaseClue.SIDEWAYS, x, y, z, "deepslate laid on its side");
                    } else if (growing(state) && view.complete()) {
                        grown(x, y, z, state);
                    }
                    if (counting && picked(state) && shown(x, y, z)) {
                        picked.add(block, x, y, z);
                    }
                });
        }

        private boolean placedClue(BlockState state) {
            Block block = state.getBlock();
            return (block instanceof SignBlock && wants(BaseClue.SIGN)) || lit(state) || rising(state)
                || (block == Blocks.SPAWNER && wants(BaseClue.SPAWNER)) || bedHead(state) || sideways(state)
                || growing(state);
        }

        // Deepslate or a silverfish in it laid along x or z. Silverfish keep the way it lay.
        private boolean sideways(BlockState state) {
            return wants(BaseClue.SIDEWAYS) && (state.is(Blocks.DEEPSLATE) || state.is(Blocks.INFESTED_DEEPSLATE))
                && state.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y;
        }

        private boolean inPillar(int x, int y, int z) {
            return SeenBlocks.near(view::get, x, y, z, PILLAR_REACH,
                (bx, by, bz, state) -> state.is(Blocks.DEEPSLATE_TILE_STAIRS));
        }

        private boolean growing(BlockState state) {
            return wants(BaseClue.GROWTH) && (kelp(state) || state.is(Blocks.VINE) || state.is(Blocks.POINTED_DRIPSTONE));
        }

        // A vine run or a dripstone is read from its top or base end and a kelp column from the top
        // that reaches the air.
        private void grown(int x, int y, int z, BlockState state) {
            if (state.is(Blocks.VINE)) {
                if (!hanging(x, y + 1, z) && hanging(x, y, z)) {
                    int run = 0;
                    while (hanging(x, y - run, z)) {
                        run++;
                    }
                    if (run >= GROWN_VINE) {
                        growth.add(x, y, z, "vines hanging " + run + " blocks free");
                    }
                }
            } else if (state.is(Blocks.POINTED_DRIPSTONE)) {
                Direction tip = state.getValue(SpeleothemBlock.TIP_DIRECTION);
                if (pointing(view.get(x - tip.getStepX(), y - tip.getStepY(), z - tip.getStepZ()), tip)) {
                    return;
                }
                int length = 1;
                while (pointing(view.get(x, y + tip.getStepY() * length, z), tip)) {
                    length++;
                }
                if (length >= (tip == Direction.DOWN ? GROWN_STALACTITE : GROWN_STALAGMITE)) {
                    growth.add(x, y, z, "dripstone " + length + " blocks long");
                }
            } else if (view.get(x, y + 1, z).isAir()) {
                int length = 1;
                while (kelp(view.get(x, y - length, z))) {
                    length++;
                }
                if (length >= GROWN_KELP) {
                    growth.add(x, y, z, "kelp " + length + " blocks tall at the surface");
                }
            }
        }

        private static boolean pointing(BlockState state, Direction tip) {
            return state.is(Blocks.POINTED_DRIPSTONE) && state.getValue(SpeleothemBlock.TIP_DIRECTION) == tip;
        }

        private static boolean kelp(BlockState state) {
            return state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT);
        }

        // A vine with no face the game would let it cling to. It hangs from the vine above.
        private boolean hanging(int x, int y, int z) {
            BlockState vine = view.get(x, y, z);
            if (!vine.is(Blocks.VINE)) {
                return false;
            }
            BlockPos pos = new BlockPos(x, y, z);
            for (Direction side : Direction.values()) {
                if (side != Direction.DOWN && vine.getValue(VineBlock.getPropertyForFace(side))
                    && VineBlock.isAcceptableNeighbour(getter, pos.relative(side), side)) {
                    return false;
                }
            }
            return true;
        }

        private boolean picked(BlockState state) {
            return rules.needed().containsKey(state.getBlock());
        }

        // The world lights no nether portal. The End lights its own exit portal.
        private boolean lit(BlockState state) {
            return wants(BaseClue.PORTAL)
                && (state.is(Blocks.NETHER_PORTAL) || (state.is(Blocks.END_PORTAL) && !in(Level.END)));
        }

        // Soul sand lifts a column. The magma of ocean floors pulls one down.
        private boolean rising(BlockState state) {
            return wants(BaseClue.BUBBLES) && state.is(Blocks.BUBBLE_COLUMN)
                && !state.getValue(BubbleColumnBlock.DRAG_DOWN);
        }

        // A bed is two blocks and only its head is read.
        private boolean bedHead(BlockState state) {
            return wants(BaseClue.BED) && state.getBlock() instanceof AbstractBedBlock
                && state.getValue(AbstractBedBlock.PART) == BedPart.HEAD;
        }

        // A dungeon or mineshaft or other structure leaves its own blocks around its spawner.
        // Players clear them to build a mob farm.
        private boolean lone(int x, int y, int z) {
            Set<Block> companions = BaseBlocks.spawnerCompanions(rules.dimension());
            return !SeenBlocks.near(view::get, x, y, z, COMPANION_REACH,
                (bx, by, bz, state) -> companions.contains(state.getBlock()) && shown(bx, by, bz));
        }

        // The world's own beds keep their structure close. Trial chamber blocks stand by every
        // chamber bed. An igloo walls its red bed in snow and a camp hangs white wool stairs over
        // its straw beds. Village colours are judged on the game thread where the whole village
        // can be looked for.
        private boolean structureBed(int x, int y, int z, Block bed) {
            if (SeenBlocks.near(view::get, x, y, z, StructureBeds.CHAMBER_REACH,
                (bx, by, bz, state) -> StructureBeds.chamberBlock(state))) {
                return true;
            }
            if (bed == Blocks.STRAW_BED) {
                return SeenBlocks.near(view::get, x, y, z, StructureBeds.CAMP_REACH,
                    (bx, by, bz, state) -> StructureBeds.campRoof(state));
            }
            return bed == Blocks.BED.red() && SeenBlocks.near(view::get, x, y, z, TOUCHING,
                (bx, by, bz, state) -> StructureBeds.iglooWall(state));
        }

        // The floor holds bedrock in its lowest five layers and the nether roof in its top five.
        // The End builds bedrock into its spikes and portals and is left out.
        private void bedrock() {
            boolean nether = in(Level.NETHER);
            if (!nether && !in(Level.OVERWORLD)) {
                return;
            }
            int above = view.minY() + BEDROCK_LAYERS;
            view.forEachMatching(Math.max(low, above), high, state -> state.is(Blocks.BEDROCK), (x, y, z, state) -> {
                boolean roofLayer = nether && y > NETHER_ROOF_TOP - BEDROCK_LAYERS && y <= NETHER_ROOF_TOP;
                if (!roofLayer && shown(x, y, z)) {
                    once(BaseClue.BEDROCK, x, y, z, "placed bedrock");
                }
            });
        }

        // Past the ground only tree tops and snow and fire and what endermen carry. Below it the
        // ground itself does not count either.
        private void sky() {
            view.forEachMatching(Math.max(low, rules.skyHeight()), high,
                state -> !state.isAir() && !NaturalBlocks.aloft(state), (x, y, z, state) -> {
                    boolean ground = y <= NaturalBlocks.GROUND_TOP && NaturalBlocks.contains(state.getBlock());
                    if (!ground && shown(x, y, z)) {
                        once(BaseClue.SKY, x, y, z, "a sky build of " + name(state.getBlock()));
                    }
                });
        }

        // Mushrooms are the only thing the nether places on top of its roof.
        private void roof() {
            view.forEachMatching(Math.max(low, NETHER_ROOF_TOP + 1), high,
                state -> !state.isAir() && !state.is(Blocks.BROWN_MUSHROOM) && !state.is(Blocks.RED_MUSHROOM),
                (x, y, z, state) -> {
                    if (shown(x, y, z)) {
                        once(BaseClue.ROOF, x, y, z, "a roof build of " + name(state.getBlock()));
                    }
                });
        }

        // Under the bottom layer and over the top one of each bedrock band. The lowest floor layer
        // and the highest roof layer are always whole.
        private void rooms() {
            if (!in(Level.OVERWORLD) && !in(Level.NETHER)) {
                return;
            }
            int floor = view.minY();
            rooms(floor + 1, floor + BEDROCK_LAYERS - 1);
            if (in(Level.NETHER)) {
                rooms(NETHER_ROOF_TOP - BEDROCK_LAYERS + 1, NETHER_ROOF_TOP - 1);
            }
        }

        private void rooms(int bottom, int top) {
            int from = Math.max(bottom, low);
            int to = Math.min(top, high);
            if (from > to) {
                return;
            }
            LongSet seen = new LongOpenHashSet();
            view.forEachMatching(from, to, BlockState::isAir, (x, y, z, state) -> {
                if (!found.contains(BaseClue.ROOM) && seen.add(BlockPos.asLong(x, y, z))) {
                    room(new BlockPos(x, y, z), from, to, seen);
                }
            });
        }

        // The air and whatever was put in it that bedrock alone walls in. Nothing in 400 thousand
        // generated chunks came close. A player stands in two air blocks one above the other. A
        // cell an earlier fill already reached belongs to a hollow that was not walled in. Paper's
        // anti xray can send buried blocks as air but never a block entity that is not there.
        private void room(BlockPos start, int from, int to, LongSet seen) {
            LongSet inside = new LongOpenHashSet();
            inside.add(start.asLong());
            Queue<BlockPos> open = new ArrayDeque<>();
            open.add(start);
            BlockPos corner = start;
            boolean standing = false;
            boolean holdsBlockEntity = false;
            while (!open.isEmpty()) {
                BlockPos cell = open.poll();
                if (CORNER_FIRST.compare(cell, corner) < 0) {
                    corner = cell;
                }
                BlockState content = view.get(cell);
                standing |= content.isAir() && view.get(cell.above()).isAir();
                holdsBlockEntity |= content.hasBlockEntity();
                for (Direction side : Direction.values()) {
                    BlockPos next = cell.relative(side);
                    BlockState state = view.get(next);
                    if (state.is(Blocks.BEDROCK) || inside.contains(next.asLong())) {
                        continue;
                    }
                    if (next.getY() < from || next.getY() > to || !roomFill(state) || !seen.add(next.asLong())
                        || inside.size() >= MAX_ROOM) {
                        return;
                    }
                    inside.add(next.asLong());
                    open.add(next);
                }
            }
            boolean ours = SectionPos.blockToSectionCoord(corner.getX()) == view.pos().x()
                && SectionPos.blockToSectionCoord(corner.getZ()) == view.pos().z();
            if (standing && ours && (holdsBlockEntity || !rules.paper())) {
                once(BaseClue.ROOM, corner.getX(), corner.getY(), corner.getZ(), "a room sealed in bedrock");
            }
        }

        // Air or anything nature never fills the band with such as a chest. Void air lies past the
        // chunks the view holds.
        private static boolean roomFill(BlockState state) {
            return state.isAir() ? !state.is(Blocks.VOID_AIR) : !NaturalBlocks.contains(state.getBlock());
        }

        private boolean wants(BaseClue clue) {
            return rules.clues().contains(clue);
        }

        private boolean in(ResourceKey<Level> dimension) {
            return rules.dimension() == dimension;
        }

        // Paper's anti xray fakes only blocks walled in on every side.
        private boolean shown(int x, int y, int z) {
            return !rules.paper() || AntiXray.shownTruly(view, x, y, z);
        }

        // One spot is enough to mark a chunk.
        private void once(BaseClue clue, int x, int y, int z, String what) {
            if (found.add(clue)) {
                out.add(new Hit(clue, new BlockPos(x, y, z), what));
            }
        }
    }

    // A scan view as the game's own block tests read a world.
    private record ViewBlocks(ChunkScanner.View view) implements BlockGetter {

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return view.get(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return view.get(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return view.maxY() - view.minY() + 1;
        }

        @Override
        public int getMinY() {
            return view.minY();
        }
    }

    private static final class Growth {
        private int count;
        private BlockPos first;
        private String firstWords;

        private void add(int x, int y, int z, String words) {
            if (count++ == 0) {
                first = new BlockPos(x, y, z);
                firstWords = words;
            }
        }

        private String words() {
            return count == 1 ? firstWords : Tally.counted(count, "grown plant") + " such as " + firstWords;
        }
    }

    private static String name(Block block) {
        return ChatUtil.words(block);
    }
}
