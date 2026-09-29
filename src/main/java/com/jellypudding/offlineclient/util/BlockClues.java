package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.worldgen.BaseBlocks;
import com.jellypudding.offlineclient.worldgen.NaturalBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Reads one chunk for blocks a player left. It runs on a scanner thread with the settings as they
// stood. Every rule was checked against the structure templates and world generation of 26.3.
public final class BlockClues {

    // What one chunk showed and where. The words read after found such as a lit nether portal. A
    // sign comes without words and the game thread reads what it says.
    public record Hit(BaseClue clue, BlockPos pos, String what) {
    }

    // A group of blocks and how many of them one chunk needs.
    public record Group(BaseClue clue, Set<Block> blocks, int needed) {
    }

    // The settings a scan reads by. The margins are layers left out at the bottom and top.
    public record Rules(Set<BaseClue> clues, List<Group> groups, int skyHeight, int floorMargin,
                        int ceilingMargin, boolean paper, ResourceKey<Level> dimension) {
    }

    // The floor and the nether roof are each five layers of bedrock at most.
    private static final int BEDROCK_LAYERS = 5;
    // The nether generator stops at this height. Its roof ends there and only air lies above.
    private static final int NETHER_ROOF_TOP = 127;
    // Every structure that holds a spawner has blocks of its own this close to it.
    private static final int COMPANION_REACH = 5;

    private BlockClues() {
    }

    public static void scan(Rules rules, ChunkScanner.View view, List<Hit> out) {
        new Reading(rules, view, out).run();
    }

    // How many blocks of one group a chunk holds and the kind there is most of.
    private static final class GroupCount {
        private final Map<Block, Integer> counts = new HashMap<>();
        // The highest of each kind. The find stands on it.
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

        private Block most() {
            return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
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
        private final Map<BaseClue, GroupCount> groupCounts = new EnumMap<>(BaseClue.class);

        private Reading(Rules rules, ChunkScanner.View view, List<Hit> out) {
            this.rules = rules;
            this.view = view;
            this.out = out;
            low = view.minY() + rules.floorMargin();
            high = view.maxY() - rules.ceilingMargin();
            settled = !rules.paper() || view.complete();
        }

        private void run() {
            if (low > high) {
                return;
            }
            placedBlocks();
            if (settled && rules.clues().contains(BaseClue.BEDROCK)) {
                bedrock();
            }
            if (settled && rules.clues().contains(BaseClue.SKY) && in(Level.OVERWORLD)) {
                sky();
            }
            if (settled && rules.clues().contains(BaseClue.ROOF) && in(Level.NETHER)) {
                roof();
            }
            for (Group group : rules.groups()) {
                GroupCount count = groupCounts.get(group.clue());
                if (count != null && count.total >= group.needed()) {
                    Block most = count.most();
                    out.add(new Hit(group.clue(), count.tops.get(most), groupWords(group, count.total, most)));
                }
            }
        }

        // Signs and portals and bubbles and spawners and the block groups in one pass.
        private void placedBlocks() {
            boolean groups = settled && !rules.groups().isEmpty();
            view.forEachMatching(low, high, state -> placedClue(state) || (groups && inGroup(state.getBlock())),
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
                    }
                    if (groups && shown(x, y, z)) {
                        countInGroups(block, x, y, z);
                    }
                });
        }

        private boolean placedClue(BlockState state) {
            Block block = state.getBlock();
            return (block instanceof SignBlock && wants(BaseClue.SIGN)) || lit(state) || rising(state)
                || (block == Blocks.SPAWNER && wants(BaseClue.SPAWNER));
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

        private boolean inGroup(Block block) {
            for (Group group : rules.groups()) {
                if (group.blocks().contains(block)) {
                    return true;
                }
            }
            return false;
        }

        private void countInGroups(Block block, int x, int y, int z) {
            for (Group group : rules.groups()) {
                if (group.blocks().contains(block)) {
                    groupCounts.computeIfAbsent(group.clue(), clue -> new GroupCount()).add(block, x, y, z);
                }
            }
        }

        // A dungeon or mineshaft or other structure leaves its own blocks around its spawner.
        // Players clear them to build a mob farm.
        private boolean lone(int x, int y, int z) {
            Set<Block> companions = BaseBlocks.spawnerCompanions(rules.dimension());
            for (int dx = -COMPANION_REACH; dx <= COMPANION_REACH; dx++) {
                for (int dy = -COMPANION_REACH; dy <= COMPANION_REACH; dy++) {
                    for (int dz = -COMPANION_REACH; dz <= COMPANION_REACH; dz++) {
                        if (companions.contains(view.get(x + dx, y + dy, z + dz).getBlock())
                            && shown(x + dx, y + dy, z + dz)) {
                            return false;
                        }
                    }
                }
            }
            return true;
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

    // What a group found such as a beacon or 7 building blocks such as black concrete.
    private static String groupWords(Group group, int count, Block most) {
        return count == 1 ? ChatUtil.withArticle(name(most))
            : count + " " + group.clue().noun() + " such as " + name(most);
    }

    private static String name(Block block) {
        return ChatUtil.words(block);
    }
}
