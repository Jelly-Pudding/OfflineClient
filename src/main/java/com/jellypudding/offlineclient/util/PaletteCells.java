package com.jellypudding.offlineclient.util;

import net.minecraft.core.SectionPos;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

// Reads a palette against the cells that point into it. The server sends each palette as
// it holds it. Generation and a save each leave their own order and a palette can still
// list entries no cell uses. Game thread only because every read shares one buffer.
public final class PaletteCells {

    // Wider block palettes fall back to the global one which lists nothing.
    private static final int MAX_LISTED_BLOCK_BITS = 8;

    // One section holds sixteen cubed cells. Its grid of biomes fits as well.
    private static final int[] CELLS = new int[SectionPos.SECTION_SIZE * SectionPos.SECTION_SIZE
        * SectionPos.SECTION_SIZE];

    private PaletteCells() {
    }

    // The block palette of a section that lists its entries. Null for a section of a single
    // block and for one on the global palette.
    public static Palette<BlockState> listedBlocks(LevelChunkSection section) {
        PalettedContainer.Data<BlockState> data = section.getStates().data;
        int bits = data.storage().getBits();
        return bits == 0 || bits > MAX_LISTED_BLOCK_BITS ? null : data.palette();
    }

    // Which entries of a section's block palette at least one cell holds. Only for a section
    // whose palette is listed.
    public static boolean[] usedBlocks(LevelChunkSection section) {
        PalettedContainer.Data<BlockState> data = section.getStates().data;
        BitStorage storage = data.storage();
        boolean[] used = new boolean[data.palette().getSize()];
        storage.unpack(CELLS);
        for (int i = 0; i < storage.getSize(); i++) {
            if (CELLS[i] < used.length) {
                used[CELLS[i]] = true;
            }
        }
        return used;
    }

    // True when the palette lists just the entries in use in the order the cells first show
    // them. A section a modern server saved always does.
    public static boolean inCellOrder(Palette<?> palette, BitStorage storage) {
        return inCellOrder(palette, storage, 0);
    }

    // The same after a number of leading entries the save listed before reading any cell.
    // A game from 1.13 to 1.17 listed air first whatever the cells held.
    public static boolean inCellOrder(Palette<?> palette, BitStorage storage, int seeded) {
        int size = storage.getSize();
        storage.unpack(CELLS);
        int next = seeded;
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

    // True when the palette lists one entry twice. Generation and saving never do. A palette
    // converted from an older save and read back unchanged can.
    public static boolean repeats(Palette<?> palette) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < palette.getSize(); i++) {
            if (!seen.add(palette.valueFor(i))) {
                return true;
            }
        }
        return false;
    }
}
