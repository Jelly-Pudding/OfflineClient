package com.jellypudding.offlineclient.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalStructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

// Finds where a seeded world starts the wanted structures from the nearest outwards. The game's own
// placement names the chunks a structure set may use and the structure's own test says whether it
// starts there. One search belongs to one thread.
public final class StructureSearch {

    // One start. The spot is the one the game's own locate names and its height is nought. A start
    // is certain when the game's start test said yes. One that hangs on a structure pieced together
    // from templates is only likely.
    public record Find(Holder<Structure> structure, BlockPos spot, double distance, boolean certain) {
    }

    private record Start(Holder<Structure> structure, boolean certain) {
    }

    private static final Comparator<Find> NEAREST_FIRST = Comparator.comparingDouble(Find::distance);

    // The game's own locate gives up this many placement regions out.
    private static final int FARTHEST_RING = 100;

    private final SeedWorld world;
    private final ChunkGeneratorStructureState state;
    private final Climate.Sampler climate;
    private final Set<Holder<Structure>> wanted;
    private final BlockPos origin;
    private final BooleanSupplier stopped;

    // The search ends early once stopped answers true.
    public StructureSearch(SeedWorld world, Set<Holder<Structure>> wanted, BlockPos origin, BooleanSupplier stopped) {
        this.world = world;
        this.state = world.structures();
        this.climate = world.climate();
        this.wanted = wanted;
        this.origin = origin;
        this.stopped = stopped;
    }

    // How far out in blocks the search looks. Nought when every wanted structure sits on rings the
    // game works out whole.
    public int reach() {
        int reach = 0;
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            if (holdsWanted(set.value()) && set.value().placement() instanceof RandomSpreadStructurePlacement spread) {
                reach = Math.max(reach, FARTHEST_RING * spread.spacing() * SectionPos.SECTION_SIZE);
            }
        }
        return reach;
    }

    // The nearest starts in order. The list is shorter when the reach holds too few or the search was stopped.
    public List<Find> nearest(int count) {
        List<Find> finds = new ArrayList<>();
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            if (holdsWanted(set.value())) {
                finds.addAll(nearest(set.value(), count));
            }
        }
        finds.sort(NEAREST_FIRST);
        return finds.size() > count ? List.copyOf(finds.subList(0, count)) : finds;
    }

    private boolean holdsWanted(StructureSet set) {
        for (StructureSet.StructureSelectionEntry entry : set.structures()) {
            if (wanted.contains(entry.structure())) {
                return true;
            }
        }
        return false;
    }

    private List<Find> nearest(StructureSet set, int count) {
        List<Find> finds = new ArrayList<>();
        StructurePlacement placement = set.placement();
        if (placement instanceof ConcentricRingsStructurePlacement rings) {
            for (ChunkPos chunk : state.getRingPositionsFor(rings)) {
                check(set, chunk, finds);
            }
        } else if (placement instanceof RandomSpreadStructurePlacement spread) {
            spread(set, spread, count, finds);
        }
        return finds;
    }

    // A spread placement allows one chunk in each square region of its spacing. The regions are
    // walked ring by ring around the one the search starts in.
    private void spread(StructureSet set, RandomSpreadStructurePlacement placement, int count, List<Find> finds) {
        int spacing = placement.spacing();
        int chunkX = SectionPos.blockToSectionCoord(origin.getX());
        int chunkZ = SectionPos.blockToSectionCoord(origin.getZ());
        for (int ring = 0; ring <= FARTHEST_RING; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                // Only the two ends of an inner column lie on the ring.
                int step = Math.abs(dx) == ring ? 1 : 2 * ring;
                for (int dz = -ring; dz <= ring; dz += step) {
                    if (stopped.getAsBoolean()) {
                        return;
                    }
                    check(set, placement.getPotentialStructureChunk(world.seed(), chunkX + spacing * dx,
                        chunkZ + spacing * dz), finds);
                }
            }
            finds.sort(NEAREST_FIRST);
            // Every region not yet walked lies more than this many chunks away.
            long walked = (long) ring * spacing - 1;
            if (finds.size() >= count && finds.get(count - 1).distance() <= walked * SectionPos.SECTION_SIZE) {
                return;
            }
        }
    }

    private void check(StructureSet set, ChunkPos chunk, List<Find> finds) {
        if (!set.placement().isStructureChunk(state, chunk.x(), chunk.z())) {
            return;
        }
        Start start = startIn(set, chunk);
        if (start != null && wanted.contains(start.structure())) {
            BlockPos spot = set.placement().getLocatePos(chunk);
            double distance = Math.hypot(spot.getX() - origin.getX(), spot.getZ() - origin.getZ());
            finds.add(new Find(start.structure(), spot, distance, start.certain()));
        }
    }

    // ChunkGenerator.createStructures rolls for an entry of the set by weight and rolls again among
    // the rest when that one cannot start. Null when none of them can.
    private Start startIn(StructureSet set, ChunkPos chunk) {
        List<StructureSet.StructureSelectionEntry> left = new ArrayList<>(set.structures());
        WorldgenRandom roll = new WorldgenRandom(new LegacyRandomSource(0L));
        roll.setLargeFeatureSeed(world.seed(), chunk.x(), chunk.z());
        int weight = 0;
        for (StructureSet.StructureSelectionEntry entry : left) {
            weight += entry.weight();
        }
        boolean certain = true;
        while (!left.isEmpty()) {
            int rolled = roll.nextInt(weight);
            int index = 0;
            while (rolled >= left.get(index).weight()) {
                rolled -= left.get(index).weight();
                index++;
            }
            StructureSet.StructureSelectionEntry entry = left.remove(index);
            Structure structure = entry.structure().value();
            certain &= !readsTemplates(structure);
            if (starts(structure, chunk)) {
                return new Start(entry.structure(), certain);
            }
            weight -= entry.weight();
        }
        return null;
    }

    // A structure that reads templates is only asked whether a biome it accepts lies anywhere in
    // the column it begins in. The game tests the middle of its first piece.
    private boolean starts(Structure structure, ChunkPos chunk) {
        Structure.GenerationContext context = new Structure.GenerationContext(world.registries(), world.generator(),
            world.generator().getBiomeSource(), climate, world.random(), null, world.seed(), chunk, world.height(),
            structure.biomes()::contains);
        if (readsTemplates(structure)) {
            return context.couldValidBiomeExistInTerrainColumn(chunk.getMinBlockX(), chunk.getMinBlockZ());
        }
        return structure.findValidGenerationPoint(context).isPresent();
    }

    // These size their first piece from a structure template. The game only hands templates out
    // through a manager tied to a world save.
    private static boolean readsTemplates(Structure structure) {
        return structure instanceof JigsawStructure || structure instanceof RuinedPortalStructure;
    }
}
