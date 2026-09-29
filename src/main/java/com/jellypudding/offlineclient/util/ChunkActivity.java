package com.jellypudding.offlineclient.util;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Reads what a chunk the server loaded from its save gives away about who has been near
// it. The server keeps a loaded palette as it is until the chunk is saved and unloaded.
// An entry no cell holds any more is a block broken or replaced since then. An entry whose
// block is still there in another state is a block that was used. Only means something
// for a chunk the palettes call old. Game thread only.
public final class ChunkActivity {

    // How a block changed. A used block flipped one property and the words read after its
    // name for either way it went.
    public enum Use {
        GONE(null, "gone", "gone"),
        OPENED(BlockStateProperties.OPEN, "opened", "closed"),
        LIT(BlockStateProperties.LIT, "lit", "put out"),
        SWITCHED(BlockStateProperties.POWERED, "switched on", "switched off"),
        ENABLED(BlockStateProperties.ENABLED, "switched on", "switched off"),
        TRIGGERED(BlockStateProperties.TRIGGERED, "triggered", "reset"),
        EXTENDED(BlockStateProperties.EXTENDED, "extended", "retracted"),
        BOOK(BlockStateProperties.HAS_BOOK, "given a book", "emptied"),
        RECORD(BlockStateProperties.HAS_RECORD, "given a disc", "emptied"),
        EYE(BlockStateProperties.EYE, "given an eye", "emptied"),
        SLEPT(BlockStateProperties.OCCUPIED, "slept in", "left"),
        INVERTED(BlockStateProperties.INVERTED, "flipped", "flipped");

        private final BooleanProperty property;
        private final String onWord;
        private final String offWord;

        Use(BooleanProperty property, String onWord, String offWord) {
            this.property = property;
            this.onWord = onWord;
            this.offWord = offWord;
        }

        // True for a use no tick can make. A chunk the server ticks for you counts only these.
        private boolean byHandOnly(Block block) {
            return switch (this) {
                case BOOK, EYE, INVERTED -> true;
                case SWITCHED -> block instanceof LeverBlock;
                default -> false;
            };
        }
    }

    // One block that changed and the way it went.
    public record Sign(Block block, Use use, boolean on) {

        // Such as oak door opened or chest gone.
        public String text() {
            return ChatUtil.words(block) + " " + (on ? use.onWord : use.offWord);
        }
    }

    // A section of the chunk with what changed in it.
    public record Change(int section, List<Sign> signs) {
    }

    // How one chunk is read. Near is for a chunk the server ticks for you where only what
    // nothing but a player could have done counts. Paper is for a server that may run its anti
    // xray. Ignored blocks never count.
    public record Rules(boolean usedBlocks, boolean ores, boolean near, boolean paper, Set<Block> ignored) {
    }

    // The uses a flipped property can show.
    private static final List<Use> USES = Arrays.stream(Use.values()).filter(use -> use.property != null).toList();

    // How many signs a description names before it counts the rest.
    private static final int MAX_NAMED = 3;

    // Paper anti xray in its other modes adds its whole hidden list to every low section. A
    // block gone from this many sections of one chunk is taken for that list.
    private static final int ANTI_XRAY_SECTIONS = 3;
    // Players rarely leave two hidden blocks gone from one chunk at once. Anti xray does it to
    // nearly every chunk underground.
    private static final int ANTI_XRAY_SIGNS = 2;

    // Ores the ores tag leaves out. Ore veins leave the raw blocks.
    private static final Set<Block> EXTRA_ORES = Set.of(Blocks.ANCIENT_DEBRIS, Blocks.RAW_IRON_BLOCK,
        Blocks.RAW_COPPER_BLOCK, Blocks.GILDED_BLACKSTONE);

    private ChunkActivity() {
    }

    // Every section with a change the rules count from the bottom up. Empty when there is none.
    public static List<Change> read(LevelChunk chunk, Rules rules) {
        LevelChunkSection[] sections = chunk.getSections();
        List<List<Sign>> found = new ArrayList<>(sections.length);
        Map<Block, Integer> sectionsGone = new HashMap<>();
        int hiddenGone = 0;
        for (LevelChunkSection section : sections) {
            List<Sign> signs = signs(section, rules);
            found.add(signs);
            for (Sign sign : signs) {
                if (sign.use() == Use.GONE) {
                    sectionsGone.merge(sign.block(), 1, Integer::sum);
                    hiddenGone += AntiXray.HIDDEN.contains(sign.block()) ? 1 : 0;
                }
            }
        }
        boolean obfuscated = hiddenGone >= ANTI_XRAY_SIGNS;
        List<Change> changes = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            List<Sign> kept = new ArrayList<>();
            for (Sign sign : found.get(i)) {
                if (sign.use() != Use.GONE || !rules.paper() || !antiXrayLoss(sign.block(), sectionsGone, obfuscated)) {
                    kept.add(sign);
                }
            }
            if (!kept.isEmpty()) {
                changes.add(new Change(i, kept));
            }
        }
        return changes;
    }

    // A block anti xray took out of the palette rather than a player. It is gone from several
    // sections at once or from a chunk that lost other hidden blocks as well.
    private static boolean antiXrayLoss(Block block, Map<Block, Integer> sectionsGone, boolean obfuscated) {
        return sectionsGone.get(block) >= ANTI_XRAY_SECTIONS || (obfuscated && AntiXray.HIDDEN.contains(block));
    }

    // The signs in words such as chest gone and oak door opened. Past a few the rest are counted.
    public static String describe(List<Change> changes) {
        Set<String> texts = new LinkedHashSet<>();
        for (Change change : changes) {
            for (Sign sign : change.signs()) {
                texts.add(sign.text());
            }
        }
        List<String> named = texts.stream().limit(MAX_NAMED).toList();
        String text = String.join(" and ", named);
        int more = texts.size() - named.size();
        return more > 0 ? text + " and " + more + " more" : text;
    }

    // One sign per block. A block with no cell left in any state is gone. One still there
    // shows a use when a state the palette lists turned into one a cell holds.
    private static List<Sign> signs(LevelChunkSection section, Rules rules) {
        Palette<BlockState> palette = PaletteCells.listedBlocks(section);
        if (palette == null) {
            return List.of();
        }
        boolean[] used = PaletteCells.usedBlocks(section);
        Set<Block> present = new HashSet<>();
        Set<BlockState> usedStates = new HashSet<>();
        for (int id = 0; id < used.length; id++) {
            if (used[id]) {
                BlockState state = palette.valueFor(id);
                present.add(state.getBlock());
                usedStates.add(state);
            }
        }
        Map<Block, Sign> signs = new LinkedHashMap<>();
        for (int id = 0; id < used.length; id++) {
            BlockState state = palette.valueFor(id);
            Block block = state.getBlock();
            if (used[id] || state.isAir() || rules.ignored().contains(block) || signs.containsKey(block)) {
                continue;
            }
            Sign sign = present.contains(block) ? useOf(state, usedStates, rules) : goneOf(state, rules);
            if (sign != null) {
                signs.put(block, sign);
            }
        }
        return List.copyOf(signs.values());
    }

    private static Sign goneOf(BlockState state, Rules rules) {
        boolean counts = !(rules.paper() && AntiXray.FILLERS.contains(state.getBlock()))
            && (rules.ores() || !isOre(state))
            && !(rules.near() && changesOnItsOwn(state));
        return counts ? new Sign(state.getBlock(), Use.GONE, false) : null;
    }

    // The use that turned this unused state into one a cell holds now. Null when there is
    // none. Anti xray lists the default states of its hidden blocks and those say nothing.
    private static Sign useOf(BlockState state, Set<BlockState> usedStates, Rules rules) {
        Block block = state.getBlock();
        if (!rules.usedBlocks() || (rules.paper() && AntiXray.HIDDEN.contains(block))) {
            return null;
        }
        for (Use use : USES) {
            if (!state.hasProperty(use.property) || (rules.near() && !use.byHandOnly(block))) {
                continue;
            }
            BlockState now = state.cycle(use.property);
            if (usedStates.contains(now)) {
                return new Sign(block, use, now.getValue(use.property));
            }
        }
        return null;
    }

    // Ores and the blocks ore veins leave. Anti xray engines hide these.
    private static boolean isOre(BlockState state) {
        return state.is(BlockTags.ORES) || EXTRA_ORES.contains(state.getBlock());
    }

    // What a ticking chunk can lose with nobody there. Random ticks grow and decay and melt
    // blocks. Liquids flow and loose blocks fall and fire burns and endermen carry blocks off.
    // Sheep graze and lightning burns trees and tall grass and zombies break doors.
    private static boolean changesOnItsOwn(BlockState state) {
        return state.isRandomlyTicking() || !state.getFluidState().isEmpty() || state.getBlock() instanceof Fallable
            || state.is(BlockTags.FIRE) || state.is(BlockTags.ENDERMAN_HOLDABLE)
            || state.is(BlockTags.EDIBLE_FOR_SHEEP) || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)
            || state.is(Blocks.TALL_GRASS) || state.is(Blocks.LARGE_FERN) || DoorBlock.isWoodenDoor(state);
    }
}
