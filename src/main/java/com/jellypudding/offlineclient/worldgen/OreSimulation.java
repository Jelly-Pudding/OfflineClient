package com.jellypudding.offlineclient.worldgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.AbstractOreFeature;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;

import java.util.BitSet;
import java.util.function.Function;

// Replays the ore features of one chunk the way ChunkGenerator.applyBiomeDecoration places
// them. Every random draw matches the server in number and order. Where an ore lands still
// depends on the ground each feature met. The simulation keeps the ores it places and later
// features see them.
public final class OreSimulation {

    // The world as the ore features found it.
    public interface Ground {

        BlockState block(int x, int y, int z);

        // One above the highest block that blocks motion. This is what WorldGenRegion answers
        // for the OCEAN_FLOOR_WG heightmap.
        int floor(int x, int z);

        Holder<Biome> noiseBiome(int quartX, int quartY, int quartZ);
    }

    @FunctionalInterface
    public interface Sink {
        void place(PlacedOre ore, int x, int y, int z);
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    // InSquarePlacement spreads the spots across one chunk.
    private static final int CHUNK_WIDTH = 16;
    // ScatteredOreFeature spreads its tries no further than this from the spot.
    private static final int SCATTER_REACH = 7;

    private final OreTables.Table table;
    private final long seed;
    private final long biomeZoomSeed;

    public OreSimulation(OreTables.Table table, long seed) {
        this.table = table;
        this.seed = seed;
        this.biomeZoomSeed = BiomeManager.obfuscateSeed(seed);
    }

    public OreTables.Table table() {
        return table;
    }

    // Every ore feature of the chunk in the order the server runs them.
    public void run(int chunkX, int chunkZ, Ground ground, Sink sink) {
        Replay replay = new Replay(chunkX, chunkZ, ground, sink);
        for (PlacedOre ore : table.ores()) {
            replay.place(ore);
        }
    }

    // One chunk being replayed.
    private final class Replay {

        private final int originX;
        private final int originZ;
        private final Ground ground;
        private final Sink sink;
        private final WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0));
        private final long decorationSeed;
        private final BiomeManager biomes;
        // What the replayed features wrote over the ground.
        private final Long2ObjectOpenHashMap<BlockState> written = new Long2ObjectOpenHashMap<>();
        private final Function<BlockPos, BlockState> reader = pos -> read(pos.getX(), pos.getY(), pos.getZ());

        private Replay(int chunkX, int chunkZ, Ground ground, Sink sink) {
            this.originX = chunkX << 4;
            this.originZ = chunkZ << 4;
            this.ground = ground;
            this.sink = sink;
            this.decorationSeed = random.setDecorationSeed(seed, originX, originZ);
            this.biomes = new BiomeManager(ground::noiseBiome, biomeZoomSeed);
        }

        // FeaturePlacer works depth first. Each spot is placed before the next is drawn.
        private void place(PlacedOre ore) {
            random.setFeatureSeed(decorationSeed, ore.index(), ore.step());
            int tries = ore.attempts().roll(random);
            for (int i = 0; i < tries; i++) {
                int x = random.nextInt(CHUNK_WIDTH) + originX;
                int z = random.nextInt(CHUNK_WIDTH) + originZ;
                int y = ore.height().sample(random);
                if (!biomes.getBiome(x, y, z).unwrapKey().map(ore.biomes()::test).orElse(false)) {
                    continue;
                }
                if (ore.scattered()) {
                    scatter(ore, x, y, z);
                } else {
                    blob(ore, x, y, z);
                }
            }
        }

        // OreFeature.place. The vein is skipped when its box sits wholly above the ground.
        private void blob(PlacedOre ore, int x, int y, int z) {
            int size = ore.feature().size();
            float angle = random.nextFloat() * (float) Math.PI;
            float spread = size / 8.0F;
            int pad = Mth.ceil((size / 16.0F * 2.0F + 1.0F) / 2.0F);
            double x1 = x + Math.sin(angle) * spread;
            double x2 = x - Math.sin(angle) * spread;
            double z1 = z + Math.cos(angle) * spread;
            double z2 = z - Math.cos(angle) * spread;
            double y1 = y + random.nextInt(3) - 2;
            double y2 = y + random.nextInt(3) - 2;
            int minX = x - Mth.ceil(spread) - pad;
            int minY = y - 2 - pad;
            int minZ = z - Mth.ceil(spread) - pad;
            int width = 2 * (Mth.ceil(spread) + pad);
            int height = 2 * (2 + pad);
            for (int columnX = minX; columnX <= minX + width; columnX++) {
                for (int columnZ = minZ; columnZ <= minZ + width; columnZ++) {
                    if (minY <= ground.floor(columnX, columnZ)) {
                        fill(ore, x1, x2, z1, z2, y1, y2, minX, minY, minZ, width, height);
                        return;
                    }
                }
            }
        }

        // OreFeature.doPlace. A chain of spheres from one end of the vein to the other.
        private void fill(PlacedOre ore, double x1, double x2, double z1, double z2, double y1, double y2,
                          int minX, int minY, int minZ, int width, int height) {
            int size = ore.feature().size();
            BitSet visited = new BitSet(width * height * width);
            double[] spheres = new double[size * 4];
            for (int k = 0; k < size; k++) {
                float step = (float) k / (float) size;
                double cx = Mth.lerp(step, x1, x2);
                double cy = Mth.lerp(step, y1, y2);
                double cz = Mth.lerp(step, z1, z2);
                double scale = random.nextDouble() * size / 16.0;
                double radius = ((Mth.sin((float) Math.PI * step) + 1.0F) * scale + 1.0) / 2.0;
                spheres[k * 4] = cx;
                spheres[k * 4 + 1] = cy;
                spheres[k * 4 + 2] = cz;
                spheres[k * 4 + 3] = radius;
            }
            // A sphere wholly inside a bigger one is dropped.
            for (int a = 0; a < size - 1; a++) {
                if (spheres[a * 4 + 3] <= 0.0) {
                    continue;
                }
                for (int b = a + 1; b < size; b++) {
                    if (spheres[b * 4 + 3] <= 0.0) {
                        continue;
                    }
                    double dx = spheres[a * 4] - spheres[b * 4];
                    double dy = spheres[a * 4 + 1] - spheres[b * 4 + 1];
                    double dz = spheres[a * 4 + 2] - spheres[b * 4 + 2];
                    double dr = spheres[a * 4 + 3] - spheres[b * 4 + 3];
                    if (dr * dr > dx * dx + dy * dy + dz * dz) {
                        if (dr > 0.0) {
                            spheres[b * 4 + 3] = -1.0;
                        } else {
                            spheres[a * 4 + 3] = -1.0;
                        }
                    }
                }
            }
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int k = 0; k < size; k++) {
                double radius = spheres[k * 4 + 3];
                if (radius < 0.0) {
                    continue;
                }
                double cx = spheres[k * 4];
                double cy = spheres[k * 4 + 1];
                double cz = spheres[k * 4 + 2];
                int fromX = Math.max(Mth.floor(cx - radius), minX);
                int fromY = Math.max(Mth.floor(cy - radius), minY);
                int fromZ = Math.max(Mth.floor(cz - radius), minZ);
                int toX = Math.max(Mth.floor(cx + radius), fromX);
                int toY = Math.max(Mth.floor(cy + radius), fromY);
                int toZ = Math.max(Mth.floor(cz + radius), fromZ);
                for (int bx = fromX; bx <= toX; bx++) {
                    double fx = (bx + 0.5 - cx) / radius;
                    if (fx * fx >= 1.0) {
                        continue;
                    }
                    for (int by = fromY; by <= toY; by++) {
                        double fy = (by + 0.5 - cy) / radius;
                        if (fx * fx + fy * fy >= 1.0) {
                            continue;
                        }
                        for (int bz = fromZ; bz <= toZ; bz++) {
                            double fz = (bz + 0.5 - cz) / radius;
                            if (fx * fx + fy * fy + fz * fz >= 1.0 || table.outsideBuildHeight(by)) {
                                continue;
                            }
                            int bit = bx - minX + (by - minY) * width + (bz - minZ) * width * height;
                            if (visited.get(bit)) {
                                continue;
                            }
                            visited.set(bit);
                            pos.set(bx, by, bz);
                            tryPlace(ore, pos);
                        }
                    }
                }
            }
        }

        // ScatteredOreFeature.place. Single blocks strewn around the spot.
        private void scatter(PlacedOre ore, int x, int y, int z) {
            int tries = random.nextInt(ore.feature().size() + 1);
            BlockPos origin = new BlockPos(x, y, z);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int i = 0; i < tries; i++) {
                int reach = Math.min(i, SCATTER_REACH);
                int dx = offset(reach);
                int dy = offset(reach);
                int dz = offset(reach);
                pos.setWithOffset(origin, dx, dy, dz);
                tryPlace(ore, pos);
            }
        }

        private int offset(int reach) {
            return Math.round((random.nextFloat() - random.nextFloat()) * reach);
        }

        // The first target that takes the block wins. The vanilla test also draws the
        // random for the air check.
        private void tryPlace(PlacedOre ore, BlockPos.MutableBlockPos pos) {
            AbstractOreFeature feature = ore.feature();
            BlockState state = read(pos.getX(), pos.getY(), pos.getZ());
            for (BlockReplacement target : feature.targetStates()) {
                if (feature.canPlaceOre(state, reader, random, target, pos)) {
                    written.put(pos.asLong(), target.state());
                    sink.place(ore, pos.getX(), pos.getY(), pos.getZ());
                    return;
                }
            }
        }

        // BulkSectionAccess reads air outside the build height.
        private BlockState read(int x, int y, int z) {
            if (table.outsideBuildHeight(y)) {
                return AIR;
            }
            BlockState mine = written.get(BlockPos.asLong(x, y, z));
            return mine != null ? mine : ground.block(x, y, z);
        }
    }
}
