package com.jellypudding.offlineclient.config;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

// Gzipped NBT holding one or more regions. The size of a region may run backwards along any axis.
// Its blocks are palette indices packed end to end into longs with the low bits first and an index
// may start in one long and end in the next. X runs fastest then Z then Y.
final class Litematic {

    static final String EXTENSION = ".litematic";

    private static final String REGIONS_KEY = "Regions";
    private static final String DATA_VERSION_KEY = "MinecraftDataVersion";
    private static final String POSITION_KEY = "Position";
    private static final String SIZE_KEY = "Size";
    private static final String PALETTE_KEY = "BlockStatePalette";
    private static final String BLOCKS_KEY = "BlockStates";

    // A palette of one or two kinds still takes this many bits a block.
    private static final int MIN_BITS = 2;

    private record Region(Vec3i corner, Vec3i size, ListTag palette, long[] packed) {

        private int volume() {
            return size.getX() * size.getY() * size.getZ();
        }
    }

    private Litematic() {
    }

    static boolean holds(CompoundTag root) {
        return root.contains(REGIONS_KEY);
    }

    static int dataVersion(CompoundTag root) {
        return root.getIntOr(DATA_VERSION_KEY, 0);
    }

    static Blueprint read(String name, CompoundTag root, SavedStates states) throws BuildFiles.Unreadable {
        List<Region> regions = regions(root.getCompoundOrEmpty(REGIONS_KEY));
        if (regions.isEmpty()) {
            throw new BuildFiles.Unreadable("holds no blocks");
        }
        Blueprint.Box box = new Blueprint.Box();
        int mostKinds = 0;
        for (Region region : regions) {
            Vec3i corner = region.corner();
            box.add(corner.getX(), corner.getY(), corner.getZ());
            box.add(corner.getX() + region.size().getX() - 1, corner.getY() + region.size().getY() - 1,
                corner.getZ() + region.size().getZ() - 1);
            mostKinds += region.palette().size();
        }
        BuildFiles.checkSize(box.sizeX(), box.sizeY(), box.sizeZ());
        Blueprint.Builder builder = new Blueprint.Builder(name, box.min(), box.size(), mostKinds);
        for (Region region : regions) {
            fill(builder, region, states);
        }
        return builder.build();
    }

    private static List<Region> regions(CompoundTag saved) throws BuildFiles.Unreadable {
        List<Region> regions = new ArrayList<>();
        for (String key : saved.keySet()) {
            CompoundTag region = saved.getCompoundOrEmpty(key);
            Vec3i position = vector(region.getCompoundOrEmpty(POSITION_KEY));
            Vec3i size = vector(region.getCompoundOrEmpty(SIZE_KEY));
            ListTag palette = region.getListOrEmpty(PALETTE_KEY);
            long[] packed = region.getLongArray(BLOCKS_KEY).orElse(null);
            if (palette.isEmpty() || packed == null) {
                continue;
            }
            Vec3i sides = new Vec3i(Math.abs(size.getX()), Math.abs(size.getY()), Math.abs(size.getZ()));
            BuildFiles.checkSize(sides.getX(), sides.getY(), sides.getZ());
            regions.add(new Region(new Vec3i(lowest(position.getX(), size.getX()),
                lowest(position.getY(), size.getY()), lowest(position.getZ(), size.getZ())), sides, palette, packed));
        }
        return regions;
    }

    // A size below nought runs back from the corner the region names.
    private static int lowest(int corner, int size) {
        return size < 0 ? corner + size + 1 : corner;
    }

    private static Vec3i vector(CompoundTag saved) {
        return new Vec3i(saved.getIntOr("x", 0), saved.getIntOr("y", 0), saved.getIntOr("z", 0));
    }

    // Air in one region never wipes a block another region put in the same cell.
    private static void fill(Blueprint.Builder builder, Region region, SavedStates states)
        throws BuildFiles.Unreadable {
        int[] kinds = new int[region.palette().size()];
        boolean[] empty = new boolean[kinds.length];
        for (int id = 0; id < kinds.length; id++) {
            BlockState state = states.read(region.palette().getCompoundOrEmpty(id));
            kinds[id] = SavedStates.kindIn(builder, state);
            empty[id] = state == null || state.isAir();
        }
        int bits = Math.max(MIN_BITS, Integer.SIZE - Integer.numberOfLeadingZeros(kinds.length - 1));
        long needed = ((long) region.volume() * bits + Long.SIZE - 1) / Long.SIZE;
        if (region.packed().length < needed) {
            throw new BuildFiles.Unreadable("stops before the last of its blocks");
        }
        long mask = (1L << bits) - 1;
        int sizeX = region.size().getX();
        int sizeZ = region.size().getZ();
        for (int index = 0; index < region.volume(); index++) {
            int id = unpack(region.packed(), bits, mask, index);
            if (id >= kinds.length) {
                throw new BuildFiles.Unreadable("names a block its palette does not hold");
            }
            int x = region.corner().getX() + index % sizeX;
            int y = region.corner().getY() + index / (sizeX * sizeZ);
            int z = region.corner().getZ() + index / sizeX % sizeZ;
            if (!empty[id] || !builder.isSet(x, y, z)) {
                builder.set(x, y, z, kinds[id]);
            }
        }
    }

    private static int unpack(long[] packed, int bits, long mask, int index) {
        long start = (long) index * bits;
        int word = (int) (start / Long.SIZE);
        int shift = (int) (start % Long.SIZE);
        long value = packed[word] >>> shift;
        if (shift + bits > Long.SIZE) {
            value |= packed[word + 1] << (Long.SIZE - shift);
        }
        return (int) (value & mask);
    }
}
