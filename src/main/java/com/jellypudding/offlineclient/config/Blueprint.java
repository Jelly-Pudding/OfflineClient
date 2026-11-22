package com.jellypudding.offlineclient.config;

import net.minecraft.core.Vec3i;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// A box of cells counted from the build's own anchor. A cell names the block state it wants or
// takes any block or has no say. Air is a wish like any other.
public final class Blueprint {

    public static final int NO_SAY = -1;
    public static final int NO_CELL = -1;

    // The most cells one build may hold. Every cell costs a few bits for as long as it is loaded.
    public static final long MAX_CELLS = 1L << 26;

    @FunctionalInterface
    public interface CellVisitor {
        // The state is null where any block will do.
        void visit(int x, int y, int z, BlockState state);
    }

    private final String name;
    private final Vec3i min;
    private final Vec3i size;
    // A null entry stands for any block.
    private final List<BlockState> palette;
    // Nought where the build has no say and otherwise one more than the palette index. X runs
    // fastest then z then y.
    private final SimpleBitStorage cells;
    private final int blocks;

    private Blueprint(String name, Vec3i min, Vec3i size, List<BlockState> palette, SimpleBitStorage cells,
                      int blocks) {
        this.name = name;
        this.min = min;
        this.size = size;
        this.palette = Collections.unmodifiableList(palette);
        this.cells = cells;
        this.blocks = blocks;
    }

    public String name() {
        return name;
    }

    public Vec3i min() {
        return min;
    }

    public Vec3i size() {
        return size;
    }

    // Cells that want to be empty are not counted.
    public int blocks() {
        return blocks;
    }

    public List<BlockState> palette() {
        return palette;
    }

    public int indexAt(int x, int y, int z) {
        int cell = cellAt(x, y, z);
        return cell == NO_CELL ? NO_SAY : indexOf(cell);
    }

    public int cellAt(int x, int y, int z) {
        int dx = x - min.getX();
        int dy = y - min.getY();
        int dz = z - min.getZ();
        if (dx < 0 || dy < 0 || dz < 0 || dx >= size.getX() || dy >= size.getY() || dz >= size.getZ()) {
            return NO_CELL;
        }
        return (dy * size.getZ() + dz) * size.getX() + dx;
    }

    public int cellCount() {
        return cells.getSize();
    }

    public int indexOf(int cell) {
        return cells.get(cell) - 1;
    }

    public int xOf(int cell) {
        return min.getX() + cell % size.getX();
    }

    public int yOf(int cell) {
        return min.getY() + cell / (size.getX() * size.getZ());
    }

    public int zOf(int cell) {
        return min.getZ() + cell / size.getX() % size.getZ();
    }

    public void forEach(CellVisitor visitor) {
        for (int cell = 0; cell < cells.getSize(); cell++) {
            int index = indexOf(cell);
            if (index != NO_SAY) {
                visitor.visit(xOf(cell), yOf(cell), zOf(cell), palette.get(index));
            }
        }
    }

    public Blueprint plain() {
        Builder builder = new Builder(name, min, size, 1);
        int any = builder.kind(null);
        for (int cell = 0; cell < cells.getSize(); cell++) {
            if (indexOf(cell) != NO_SAY) {
                builder.setAt(cell, any);
            }
        }
        return builder.build();
    }

    public static final class Box {

        private int minX = Integer.MAX_VALUE;
        private int minY = Integer.MAX_VALUE;
        private int minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE;
        private int maxY = Integer.MIN_VALUE;
        private int maxZ = Integer.MIN_VALUE;

        public void add(int x, int y, int z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        public Vec3i min() {
            return new Vec3i(minX, minY, minZ);
        }

        // Corners far apart are wider than an int can count.
        public long sizeX() {
            return (long) maxX - minX + 1;
        }

        public long sizeY() {
            return (long) maxY - minY + 1;
        }

        public long sizeZ() {
            return (long) maxZ - minZ + 1;
        }

        public Vec3i size() {
            return new Vec3i((int) sizeX(), (int) sizeY(), (int) sizeZ());
        }
    }

    public static final class Builder {

        private final String name;
        private final Vec3i min;
        private final Vec3i size;
        private final List<BlockState> palette = new ArrayList<>();
        private final Map<BlockState, Integer> indices = new HashMap<>();
        private final SimpleBitStorage cells;

        public Builder(String name, Vec3i min, Vec3i size, int mostKinds) {
            if (!fits(size.getX(), size.getY(), size.getZ())) {
                throw new IllegalArgumentException("A build cannot be " + size.toShortString() + " cells");
            }
            this.name = name;
            this.min = min;
            this.size = size;
            // The values run from nought for no say up to the number of kinds.
            int bits = Math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(mostKinds));
            this.cells = new SimpleBitStorage(bits, size.getX() * size.getY() * size.getZ());
        }

        public static boolean fits(long sizeX, long sizeY, long sizeZ) {
            return sizeX > 0 && sizeY > 0 && sizeZ > 0 && sizeX <= MAX_CELLS && sizeY <= MAX_CELLS
                && sizeX * sizeY <= MAX_CELLS && sizeX * sizeY * sizeZ <= MAX_CELLS;
        }

        public int kind(BlockState state) {
            Integer known = indices.get(state);
            if (known != null) {
                return known;
            }
            palette.add(state);
            indices.put(state, palette.size() - 1);
            return palette.size() - 1;
        }

        public void set(int x, int y, int z, int kind) {
            setAt(cell(x, y, z), kind);
        }

        public void setAt(int cell, int kind) {
            cells.set(cell, kind + 1);
        }

        public boolean isSet(int x, int y, int z) {
            return cells.get(cell(x, y, z)) != 0;
        }

        private int cell(int x, int y, int z) {
            return ((y - min.getY()) * size.getZ() + z - min.getZ()) * size.getX() + x - min.getX();
        }

        public Blueprint build() {
            int blocks = 0;
            for (int cell = 0; cell < cells.getSize(); cell++) {
                int index = cells.get(cell) - 1;
                if (index != NO_SAY && (palette.get(index) == null || !palette.get(index).isAir())) {
                    blocks++;
                }
            }
            return new Blueprint(name, min, size, palette, cells, blocks);
        }
    }
}
