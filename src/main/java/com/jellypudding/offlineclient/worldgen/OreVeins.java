package com.jellypudding.offlineclient.worldgen;

import com.jellypudding.offlineclient.util.BoundedMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.OreVeinRule;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The large iron and copper veins of a seeded world. The game lays them with a material rule on
// every solid block of a chunk before the caves are carved and long before the ore features run.
// Each rule is only sampled across the band of heights its vein lives in. One thread at a time.
public final class OreVeins {

    @FunctionalInterface
    public interface Sink {
        void place(OreKind kind, int x, int y, int z);
    }

    // The band of heights the game keeps each vein in.
    private static final Map<OreKind, OreVeinRule.VeinType> BANDS = Map.of(
        OreKind.IRON_VEIN, OreVeinRule.VeinType.IRON, OreKind.COPPER_VEIN, OreVeinRule.VeinType.COPPER);

    // OreVeinRule asks for its random numbers under this name.
    private static final Identifier RANDOM_NAME = Identifier.withDefaultNamespace("ore");

    // A replay reads the veins of a chunk and its neighbours. This covers the widest range by far.
    private static final int REMEMBERED_CHUNKS = 1024;

    // A dimension without large veins.
    public static final OreVeins NONE = new OreVeins(null, List.of());

    // One vein rule of the dimension and the pick its ore shows as.
    private record Vein(OreVeinRule rule, OreKind kind, int minY, int maxY) {
    }

    private final SeedWorld world;
    private final List<Vein> veins;
    private final Map<BlockState, OreKind> kinds = new HashMap<>();
    // The heights outside which no vein of the dimension reaches.
    private final int lowest;
    private final int highest;
    // The ore and raw ore blocks of each chunk worked out by block position.
    private final Map<Long, Long2ObjectMap<BlockState>> chunks = new BoundedMap<>(REMEMBERED_CHUNKS, true);
    private long lastKey;
    private Long2ObjectMap<BlockState> last;
    private DensitySamplerSet samplers;
    private PositionalRandomFactory random;

    public OreVeins(SeedWorld world) {
        this(world, veinsOf(world));
    }

    private OreVeins(SeedWorld world, List<Vein> veins) {
        this.world = world;
        this.veins = veins;
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (Vein vein : veins) {
            kinds.put(vein.rule().oreBlock(), vein.kind());
            low = Math.min(low, vein.minY());
            high = Math.max(high, vein.maxY());
        }
        this.lowest = low;
        this.highest = high;
    }

    // The vein rules the dimension runs on all of its ground. Its material rule is a sequence
    // and the first step that answers a block wins.
    private static List<Vein> veinsOf(SeedWorld world) {
        List<Vein> veins = new ArrayList<>();
        if (!(named(world.settings().materialRule().value()) instanceof SequenceRule steps)) {
            return veins;
        }
        for (MaterialRule step : steps.sequence()) {
            if (named(step) instanceof OreVeinRule rule) {
                OreKind ore = OreTables.kindOf(rule.oreBlock().getBlock());
                BANDS.forEach((kind, band) -> {
                    if (kind.ore() == ore) {
                        veins.add(new Vein(rule, kind, band.minY, band.maxY));
                    }
                });
            }
        }
        return veins;
    }

    // A rule the data pack names is held by reference.
    private static MaterialRule named(MaterialRule rule) {
        return rule instanceof MaterialRule.HolderHolder held ? held.holder().value() : rule;
    }

    // The ore or raw ore block a vein put at a block. Null for the filler stone of a vein and for
    // ground the veins left alone.
    public BlockState lump(int x, int y, int z) {
        if (y < lowest || y > highest) {
            return null;
        }
        return chunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))
            .get(BlockPos.asLong(x, y, z));
    }

    // Every vein ore block of the chunk the ground still holds.
    public void run(int chunkX, int chunkZ, OreSimulation.Ground ground, Sink sink) {
        if (veins.isEmpty()) {
            return;
        }
        for (Long2ObjectMap.Entry<BlockState> lump : chunk(chunkX, chunkZ).long2ObjectEntrySet()) {
            OreKind kind = kinds.get(lump.getValue());
            int x = BlockPos.getX(lump.getLongKey());
            int y = BlockPos.getY(lump.getLongKey());
            int z = BlockPos.getZ(lump.getLongKey());
            if (kind != null && ground.block(x, y, z) == lump.getValue()) {
                sink.place(kind, x, y, z);
            }
        }
    }

    private Long2ObjectMap<BlockState> chunk(int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        if (last == null || key != lastKey) {
            last = chunks.computeIfAbsent(key, missing -> sample(chunkX, chunkZ));
            lastKey = key;
        }
        return last;
    }

    // OreVeinRule block by block. The density decides whether a block belongs to the vein at all.
    // Richness and the gap noise then pick ore over filler and a last roll turns ore into a raw
    // ore block.
    private Long2ObjectMap<BlockState> sample(int chunkX, int chunkZ) {
        if (samplers == null) {
            samplers = world.samplers();
            random = world.random().getOrCreateRandomFactory(RANDOM_NAME);
        }
        Long2ObjectMap<BlockState> lumps = null;
        int minX = SectionPos.sectionToBlockCoord(chunkX);
        int minZ = SectionPos.sectionToBlockCoord(chunkZ);
        for (Vein vein : veins) {
            OreVeinRule rule = vein.rule();
            DensityVolume band = new DensityVolume(SectionPos.SECTION_SIZE, vein.maxY() - vein.minY() + 1,
                SectionPos.SECTION_SIZE, minX, vein.minY(), minZ);
            DensityBuffer densities = DensityBuffer.createUnpooled(band.size());
            samplers.get(rule.density()).sampleVolume(densities, band);
            DensitySampler.Bound richness = samplers.get(rule.richness());
            DensitySampler.Bound gap = samplers.get(rule.fillerGap());
            for (int x = minX; x < minX + SectionPos.SECTION_SIZE; x++) {
                for (int z = minZ; z < minZ + SectionPos.SECTION_SIZE; z++) {
                    for (int y = vein.minY(); y <= vein.maxY(); y++) {
                        float density = densities.get(band.indexOfBlock(x, y, z));
                        if (density <= 0) {
                            continue;
                        }
                        RandomSource roll = random.at(x, y, z);
                        if (roll.nextFloat() > density
                            || roll.nextFloat() >= richness.sampleValue(x, y, z) || gap.sampleValue(x, y, z) >= 0) {
                            continue;
                        }
                        if (lumps == null) {
                            lumps = new Long2ObjectOpenHashMap<>();
                        }
                        lumps.put(BlockPos.asLong(x, y, z),
                            roll.nextFloat() < rule.rawOreChance() ? rule.rawOreBlock() : rule.oreBlock());
                    }
                }
            }
        }
        return lumps == null ? Long2ObjectMaps.emptyMap() : lumps;
    }
}
