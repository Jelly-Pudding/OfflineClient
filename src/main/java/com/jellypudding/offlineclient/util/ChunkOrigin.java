package com.jellypudding.offlineclient.util;

import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Reads what a chunk from the server gives away about its past. Generation grows every
// palette from an empty section. A save rebuilds each palette from the cells in order.
// Only the game thread may call this and only as the chunk arrives. A resize later on
// rebuilds the order.
public final class ChunkOrigin {

    public enum Age { FRESH, OLD }

    // A verdict and the index of the section that settled it.
    public record Finding(Age age, int section) {
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    // Wider block palettes fall back to the global one which keeps no order.
    private static final int MAX_LISTED_BLOCK_BITS = 8;
    // Biome palettes fall back to the global one past eight entries.
    private static final int MAX_LISTED_BIOME_BITS = 3;
    // Biomes sit on a grid four cells wide inside a section.
    private static final int BIOME_CELLS = 4;
    // One section holds sixteen cubed cells. Reused as every palette is read.
    private static final int[] CELLS = new int[SectionPos.SECTION_SIZE * SectionPos.SECTION_SIZE
        * SectionPos.SECTION_SIZE];
    // Without a lead from the lowest section this many sections must agree.
    private static final int MIN_VOTES = 2;

    // Since 1.18 stone turns to deepslate at random below y 8. Upgrading an older chunk only
    // swaps its old bedrock at y 0 to 4 for deepslate. Stone from y 5 to 7 stays stone.
    private static final int BAND_BOTTOM = 5;
    private static final int BAND_TOP = 7;
    // Native terrain would show deepslate among this much stone all but surely.
    private static final int MIN_BAND_STONE = 192;
    private static final Set<Block> STONE_ORES = Set.of(Blocks.COAL_ORE, Blocks.IRON_ORE,
        Blocks.GOLD_ORE, Blocks.REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE);
    private static final Set<Block> DEEPSLATE = Set.of(Blocks.DEEPSLATE, Blocks.DEEPSLATE_COAL_ORE,
        Blocks.DEEPSLATE_IRON_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.DEEPSLATE_GOLD_ORE,
        Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
        Blocks.DEEPSLATE_EMERALD_ORE);

    // Blocks the nether gained in 1.16. Nether gold ore alone turns up in nearly every chunk.
    private static final Set<Block> NETHER_1_16 = Set.of(Blocks.NETHER_GOLD_ORE, Blocks.ANCIENT_DEBRIS,
        Blocks.BLACKSTONE, Blocks.GILDED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.BASALT,
        Blocks.SOUL_SOIL, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.CRIMSON_STEM,
        Blocks.WARPED_STEM, Blocks.NETHER_WART_BLOCK, Blocks.WARPED_WART_BLOCK, Blocks.SHROOMLIGHT,
        Blocks.CRIMSON_ROOTS, Blocks.WARPED_ROOTS, Blocks.CRIMSON_FUNGUS, Blocks.WARPED_FUNGUS,
        Blocks.NETHER_SPROUTS, Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT, Blocks.TWISTING_VINES,
        Blocks.TWISTING_VINES_PLANT, Blocks.CRYING_OBSIDIAN);
    // Nether ores spread from y 10 to 117. These sections lie wholly inside that range.
    private static final int ORE_SECTION_LOW = 1;
    private static final int ORE_SECTION_HIGH = 6;
    // With this much netherrack the ten nether gold attempts per chunk all but surely land.
    private static final double MIN_NETHERRACK_SHARE = 0.5;

    // The End only hands out the_end biome within 64 chunks of the centre. Further out it
    // gives the outer island biomes it has had since 1.13.
    private static final long CENTRE_CHUNKS_SQUARED = 64 * 64;

    // Paper anti xray adds these to every low section it loads in its hiding mode.
    private static final Set<Block> ANTI_XRAY_FILLERS = Set.of(Blocks.STONE, Blocks.DEEPSLATE,
        Blocks.NETHERRACK, Blocks.END_STONE);
    // In its other modes it adds its whole hidden list to every low section instead. A block
    // left over in this many sections of one chunk is taken for that list.
    private static final int ANTI_XRAY_SECTIONS = 3;
    // The blocks Paper anti xray hides by default and the nether ores servers add to it. It
    // swaps a buried one for a filler in the packet and leaves its palette entry unused.
    private static final Set<Block> ANTI_XRAY_HIDDEN = Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
        Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
        Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
        Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
        Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, Blocks.RAW_IRON_BLOCK, Blocks.RAW_COPPER_BLOCK,
        Blocks.MOSSY_COBBLESTONE, Blocks.OBSIDIAN, Blocks.CHEST, Blocks.ENDER_CHEST, Blocks.CLAY,
        Blocks.OAK_PLANKS, Blocks.ANCIENT_DEBRIS, Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE);
    // Players rarely leave two of these gone from one chunk at once. Anti xray does it to
    // nearly every chunk underground.
    private static final int ANTI_XRAY_SIGNS = 2;

    private ChunkOrigin() {
    }

    // Null when the palettes say nothing either way. Biomes settle it whenever a section
    // holds any biome but plains. Blocks decide only in plains where biome palettes match.
    public static Finding readPalettes(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int fresh = -1;
        int old = -1;
        for (int i = 0; i < sections.length; i++) {
            Age age = biomeAge(sections[i]);
            if (age == Age.FRESH && fresh < 0) {
                fresh = i;
            } else if (age == Age.OLD && old < 0) {
                old = i;
            }
        }
        if (fresh >= 0 && old >= 0) {
            // A saved chunk with some sections built anew such as one upgraded from 1.17.
            return null;
        }
        if (fresh >= 0) {
            return new Finding(Age.FRESH, fresh);
        }
        if (old >= 0) {
            // The server saves chunks it only got as far as biomes with. Terrain built on
            // them this session still starts every block palette with air.
            int lowest = lowestWithBlocks(sections);
            if (lowest >= 0 && blockAge(sections[lowest]) == Age.FRESH) {
                return new Finding(Age.FRESH, lowest);
            }
            return new Finding(Age.OLD, old);
        }
        return readBlocks(sections);
    }

    private static int lowestWithBlocks(LevelChunkSection[] sections) {
        for (int i = 0; i < sections.length; i++) {
            if (!sections[i].hasOnlyAir()) {
                return i;
            }
        }
        return -1;
    }

    // Generation fills biomes into a palette that starts as plains and keeps it even unused.
    // A save keeps only the biomes in use in the order the cells show them.
    private static Age biomeAge(LevelChunkSection section) {
        if (!(section.getBiomes() instanceof PalettedContainer<Holder<Biome>> biomes)) {
            return null;
        }
        PalettedContainer.Data<Holder<Biome>> data = biomes.data;
        Palette<Holder<Biome>> palette = data.palette();
        int bits = data.storage().getBits();
        if (bits == 0) {
            return palette.valueFor(0).is(Biomes.PLAINS) ? null : Age.OLD;
        }
        if (bits > MAX_LISTED_BIOME_BITS) {
            return null;
        }
        if (!inCellOrder(palette, data.storage())) {
            return Age.FRESH;
        }
        return holdsPlains(palette) ? null : Age.OLD;
    }

    private static boolean holdsPlains(Palette<Holder<Biome>> palette) {
        for (int i = 0; i < palette.getSize(); i++) {
            if (palette.valueFor(i).is(Biomes.PLAINS)) {
                return true;
            }
        }
        return false;
    }

    // The lowest section with blocks leads. The rest can only hold it back.
    private static Finding readBlocks(LevelChunkSection[] sections) {
        int lowest = lowestWithBlocks(sections);
        if (lowest < 0) {
            return null;
        }
        Age lead = blockAge(sections[lowest]);
        int freshVotes = 0;
        int oldVotes = 0;
        int firstFresh = -1;
        int firstOld = -1;
        for (int i = lowest + 1; i < sections.length; i++) {
            if (sections[i].hasOnlyAir()) {
                continue;
            }
            Age age = blockAge(sections[i]);
            if (age == Age.FRESH) {
                freshVotes++;
                firstFresh = firstFresh < 0 ? i : firstFresh;
            } else if (age == Age.OLD) {
                oldVotes++;
                firstOld = firstOld < 0 ? i : firstOld;
            }
        }
        if (lead == Age.FRESH) {
            return oldVotes > freshVotes ? null : new Finding(Age.FRESH, lowest);
        }
        if (lead == Age.OLD) {
            return freshVotes > oldVotes ? null : new Finding(Age.OLD, lowest);
        }
        if (freshVotes >= MIN_VOTES && freshVotes > oldVotes) {
            return new Finding(Age.FRESH, firstFresh);
        }
        if (oldVotes >= MIN_VOTES && oldVotes > freshVotes) {
            return new Finding(Age.OLD, firstOld);
        }
        return null;
    }

    // Generation starts every section as air. A save starts the palette with the block in
    // the first cell. A section that grew past its palette during generation also starts
    // with that block which is why the order has to hold as well.
    private static Age blockAge(LevelChunkSection section) {
        PalettedContainer.Data<BlockState> data = section.getStates().data;
        BitStorage storage = data.storage();
        int bits = storage.getBits();
        if (bits == 0 || bits > MAX_LISTED_BLOCK_BITS) {
            return null;
        }
        Palette<BlockState> palette = data.palette();
        if (palette.valueFor(0) == AIR) {
            // Air heads the palette yet the first cell holds a block. Only generation does that.
            return storage.get(0) != 0 ? Age.FRESH : null;
        }
        return inCellOrder(palette, storage) ? Age.OLD : null;
    }

    // True when the palette lists just the entries in use in the order the cells first show
    // them. A section read back from a save always does.
    private static boolean inCellOrder(Palette<?> palette, BitStorage storage) {
        int size = storage.getSize();
        storage.unpack(CELLS);
        int next = 0;
        for (int i = 0; i < size; i++) {
            int id = CELLS[i];
            if (id == next) {
                next++;
            } else if (id > next) {
                return false;
            }
        }
        return next == palette.getSize();
    }

    // The index of a section whose palette still lists a block that no cell holds or minus
    // one. Only means something for a chunk the palettes call old. The server keeps a loaded
    // palette as it is until the chunk is saved and unloaded. A block gone from every cell
    // was broken or replaced since the chunk loaded. A block that only changed its state
    // still fills a cell and does not count.
    public static int changedSection(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        List<Set<Block>> gone = new ArrayList<>(sections.length);
        Map<Block, Integer> sectionsMissing = new HashMap<>();
        int hiddenGone = 0;
        for (LevelChunkSection section : sections) {
            Set<Block> missing = goneBlocks(section);
            gone.add(missing);
            for (Block block : missing) {
                sectionsMissing.merge(block, 1, Integer::sum);
                if (ANTI_XRAY_HIDDEN.contains(block)) {
                    hiddenGone++;
                }
            }
        }
        boolean obfuscated = hiddenGone >= ANTI_XRAY_SIGNS;
        for (int i = 0; i < gone.size(); i++) {
            for (Block block : gone.get(i)) {
                if (sectionsMissing.get(block) < ANTI_XRAY_SECTIONS
                    && !(obfuscated && ANTI_XRAY_HIDDEN.contains(block))) {
                    return i;
                }
            }
        }
        return -1;
    }

    // The blocks a section's palette lists with no cell of any of their states left.
    private static Set<Block> goneBlocks(LevelChunkSection section) {
        PalettedContainer.Data<BlockState> data = section.getStates().data;
        BitStorage storage = data.storage();
        int bits = storage.getBits();
        if (bits == 0 || bits > MAX_LISTED_BLOCK_BITS) {
            return Set.of();
        }
        Palette<BlockState> palette = data.palette();
        boolean[] used = new boolean[palette.getSize()];
        storage.unpack(CELLS);
        for (int i = 0; i < storage.getSize(); i++) {
            if (CELLS[i] < used.length) {
                used[CELLS[i]] = true;
            }
        }
        Set<Block> inUse = new HashSet<>();
        for (int id = 0; id < used.length; id++) {
            if (used[id]) {
                inUse.add(palette.valueFor(id).getBlock());
            }
        }
        Set<Block> missing = new HashSet<>();
        for (int id = 0; id < used.length; id++) {
            BlockState state = palette.valueFor(id);
            if (!used[id] && !state.isAir() && !inUse.contains(state.getBlock())
                && !ANTI_XRAY_FILLERS.contains(state.getBlock())) {
                missing.add(state.getBlock());
            }
        }
        return missing;
    }

    // The index of a section showing the chunk was first generated before the terrain of its
    // dimension last changed. Minus one when nothing shows it.
    public static int oldVersionSection(LevelChunk chunk) {
        ResourceKey<Level> dimension = chunk.getLevel().dimension();
        if (dimension == Level.OVERWORLD) {
            return overworldBefore118(chunk);
        }
        if (dimension == Level.NETHER) {
            return netherBefore116(chunk);
        }
        if (dimension == Level.END) {
            return endBefore113(chunk);
        }
        return -1;
    }

    private static int overworldBefore118(LevelChunk chunk) {
        if (chunk.getMinY() > BAND_BOTTOM || chunk.getMaxY() < BAND_TOP) {
            return -1;
        }
        int index = chunk.getSectionIndex(BAND_BOTTOM);
        LevelChunkSection section = chunk.getSections()[index];
        int stone = 0;
        for (int y = BAND_BOTTOM; y <= BAND_TOP; y++) {
            int localY = SectionPos.sectionRelative(y);
            for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                    BlockState state = section.getBlockState(x, localY, z);
                    if (state.is(Blocks.STONE)) {
                        stone++;
                    } else if (DEEPSLATE.contains(state.getBlock())) {
                        return -1;
                    }
                }
            }
        }
        return stone >= MIN_BAND_STONE && hasStoneOres(chunk) ? index : -1;
    }

    // Natural terrain rather than a flat or built world.
    private static boolean hasStoneOres(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = Math.max(0, chunk.getSectionIndex(0)); i < sections.length; i++) {
            if (!sections[i].hasOnlyAir() && sections[i].maybeHas(state -> STONE_ORES.contains(state.getBlock()))) {
                return true;
            }
        }
        return false;
    }

    private static int netherBefore116(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int quartz = -1;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir()) {
                continue;
            }
            if (section.maybeHas(state -> NETHER_1_16.contains(state.getBlock()))) {
                return -1;
            }
            if (quartz < 0 && section.maybeHas(state -> state.is(Blocks.NETHER_QUARTZ_ORE))) {
                quartz = i;
            }
        }
        if (quartz < 0 || sections.length <= ORE_SECTION_HIGH) {
            return -1;
        }
        return netherrackShare(sections) >= MIN_NETHERRACK_SHARE ? quartz : -1;
    }

    private static double netherrackShare(LevelChunkSection[] sections) {
        long[] netherrack = new long[1];
        for (int i = ORE_SECTION_LOW; i <= ORE_SECTION_HIGH; i++) {
            sections[i].getStates().count((state, count) -> {
                if (state.is(Blocks.NETHERRACK)) {
                    netherrack[0] += count;
                }
            });
        }
        return netherrack[0] / (double) ((ORE_SECTION_HIGH - ORE_SECTION_LOW + 1) * CELLS.length);
    }

    private static int endBefore113(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        if ((long) pos.x() * pos.x() + (long) pos.z() * pos.z() <= CENTRE_CHUNKS_SQUARED) {
            return -1;
        }
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            if (holdsBiome(sections[i].getBiomes(), Biomes.THE_END)) {
                return i;
            }
        }
        return -1;
    }

    // Reads the cells rather than the palette. A generated palette keeps unused entries.
    private static boolean holdsBiome(PalettedContainerRO<Holder<Biome>> biomes, ResourceKey<Biome> biome) {
        for (int x = 0; x < BIOME_CELLS; x++) {
            for (int y = 0; y < BIOME_CELLS; y++) {
                for (int z = 0; z < BIOME_CELLS; z++) {
                    if (biomes.get(x, y, z).is(biome)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
