package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The TNT round a spot as one tick finds it. A blast primes every TNT block its rays reach and
// each of those goes off in turn. TNT is grouped with all it could set off or be set off by.
// Lighting any of a group can set off the whole group. A TNT minecart or TNT cube a blast hurts
// is lit and thrown and where it goes off cannot be judged.
public final class TntChain {

    // Where lit TNT goes off and the block it stood in.
    public record Blast(Vec3 at, BlockPos block) {
    }

    // Lit TNT goes off a sixteenth of the way up its body.
    private static final double BLAST_SHARE = 0.0625;

    // How far the guess follows lit TNT down before it stops looking for ground.
    private static final int MAX_FALL = 32;

    // A blast hurts out to twice its power. The strongest ray of a blast dies out before that
    // distance from the middle of the TNT block it would prime.
    private static final double REACH = 2 * ExplosionUtil.TNT_POWER;
    private static final double REACH_SQR = REACH * REACH;

    // How far round the spot TNT is read. That much is always inside the chunks the server
    // sends. A group that comes within reach of the edge may go on past it and is never judged.
    private static final int RADIUS = 24;

    // The most TNT one read keeps. Past either nothing is judged.
    private static final int MOST_BLOCKS = 1024;
    private static final int MOST_MOVERS = 256;

    // The side of the cells the TNT blocks are kept in. A TNT blast only reaches the cells beside its own.
    private static final int CELL = (int) REACH;

    private static final Minecraft MC = OfflineClient.MC;

    private final BlockPos centre;
    // Every TNT block read.
    private final List<Blast> blasts = new ArrayList<>();
    private final Map<BlockPos, Integer> blockAt = new HashMap<>();
    private final Map<BlockPos, List<Integer>> cells = new HashMap<>();
    // Where each unlit TNT minecart and TNT cube stands.
    private final List<Vec3> movers = new ArrayList<>();
    private int[] parent = new int[0];
    // Each group by the index that stands for it. Nearest to the middle of the reading first.
    private final Map<Integer, List<Blast>> groups = new HashMap<>();
    // Groups that reach past what was read or reach a TNT minecart or TNT cube.
    private final Set<Integer> unjudged = new HashSet<>();
    // False when a chunk round the spot was missing or more TNT stood there than is kept.
    private boolean whole = true;

    private TntChain(BlockPos centre) {
        this.centre = centre.immutable();
    }

    // Reads and groups the TNT round the spot. Game thread only.
    public static TntChain around(BlockPos centre) {
        TntChain chain = new TntChain(centre);
        chain.readBlocks();
        if (chain.whole) {
            chain.readMovers();
        }
        if (chain.whole) {
            chain.group();
        }
        return chain;
    }

    // Every blast that may follow once TNT at the spot is lit. Its own and every group its blast
    // reaches. Null when any of them comes near the edge of what was read or reaches a TNT minecart
    // or TNT cube. Every block of one group hands back the same list.
    public List<Blast> blastsFrom(BlockPos spot) {
        if (!whole) {
            return null;
        }
        Integer standing = blockAt.get(spot);
        if (standing != null) {
            int root = find(standing);
            return unjudged.contains(root) ? null : groups.get(root);
        }
        Blast own = new Blast(blastPoint(spot), spot);
        if (beyondJudging(own)) {
            return null;
        }
        List<Blast> all = new ArrayList<>(List.of(own));
        Set<Integer> roots = new HashSet<>();
        for (int reached : reachedBy(own)) {
            int root = find(reached);
            if (!roots.add(root)) {
                continue;
            }
            if (unjudged.contains(root)) {
                return null;
            }
            all.addAll(groups.get(root));
        }
        return all;
    }

    // Where the lit TNT goes off.
    public static Vec3 blastPoint(PrimedTnt tnt) {
        return new Vec3(tnt.getX(), tnt.getY(BLAST_SHARE), tnt.getZ());
    }

    // A grown cube holding TNT that nobody has lit yet. Babies never hold anything.
    public static boolean unlitTntCube(SulfurCube cube) {
        return !cube.isBaby() && !cube.isPrimed() && holdsTnt(cube);
    }

    public static boolean holdsTnt(SulfurCube cube) {
        return cube.getBodyArmorItem().is(ItemTags.SULFUR_CUBE_ARCHETYPE_EXPLOSIVE);
    }

    // Where TNT lit at the block goes off. Lit TNT drops onto the first block with a top.
    private static Vec3 blastPoint(BlockPos spot) {
        BlockPos cell = spot;
        for (int fallen = 0; fallen < MAX_FALL; fallen++) {
            BlockPos below = cell.below();
            VoxelShape floor = BlockUtil.state(below).getCollisionShape(MC.level, below);
            if (!floor.isEmpty()) {
                double top = below.getY() + floor.max(Direction.Axis.Y);
                return new Vec3(spot.getX() + 0.5, top + EntityTypes.TNT.getHeight() * BLAST_SHARE,
                    spot.getZ() + 0.5);
            }
            cell = below;
        }
        return Vec3.atBottomCenterOf(cell);
    }

    // Two pieces of TNT share a group when either can set the other off.
    private void group() {
        parent = new int[blasts.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < blasts.size(); i++) {
            for (int reached : reachedBy(blasts.get(i))) {
                parent[find(reached)] = find(i);
            }
        }
        Map<Integer, List<Blast>> members = new HashMap<>();
        for (int i = 0; i < blasts.size(); i++) {
            int root = find(i);
            members.computeIfAbsent(root, key -> new ArrayList<>()).add(blasts.get(i));
            if (beyondJudging(blasts.get(i))) {
                unjudged.add(root);
            }
        }
        Vec3 middle = Vec3.atCenterOf(centre);
        Comparator<Blast> nearestFirst = Comparator.comparingDouble(blast -> blast.at().distanceToSqr(middle));
        members.forEach((root, group) -> {
            group.sort(nearestFirst);
            groups.put(root, List.copyOf(group));
        });
    }

    private int find(int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    // The TNT blocks whose middles the blast reaches.
    private List<Integer> reachedBy(Blast blast) {
        List<Integer> reached = new ArrayList<>();
        Vec3 at = blast.at();
        BlockPos low = cellOf(BlockPos.containing(at.subtract(REACH, REACH, REACH)));
        BlockPos high = cellOf(BlockPos.containing(at.add(REACH, REACH, REACH)));
        for (BlockPos cell : BlockPos.betweenClosed(low, high)) {
            for (int index : cells.getOrDefault(cell, List.of())) {
                if (Vec3.atCenterOf(blasts.get(index).block()).distanceToSqr(at) <= REACH_SQR) {
                    reached.add(index);
                }
            }
        }
        return reached;
    }

    // True when the blast reaches past what was read where TNT may stand unseen or reaches a TNT
    // minecart or TNT cube. Any explosion that reaches one hurts it however well it is hidden.
    private boolean beyondJudging(Blast blast) {
        double room = RADIUS - REACH - 1;
        Vec3 at = blast.at();
        if (Math.abs(at.x - centre.getX()) > room || Math.abs(at.y - centre.getY()) > room
            || Math.abs(at.z - centre.getZ()) > room) {
            return true;
        }
        for (Vec3 mover : movers) {
            if (mover.distanceToSqr(at) <= REACH_SQR) {
                return true;
            }
        }
        return false;
    }

    // A minecart or cube already lit goes off whatever is lit here.
    private void readMovers() {
        AABB box = new AABB(centre).inflate(RADIUS);
        List<MinecartTNT> carts = MC.level.getEntitiesOfClass(MinecartTNT.class, box, cart -> !cart.isPrimed());
        List<SulfurCube> cubes = MC.level.getEntitiesOfClass(SulfurCube.class, box, TntChain::unlitTntCube);
        if (carts.size() + cubes.size() > MOST_MOVERS) {
            whole = false;
            return;
        }
        carts.forEach(cart -> movers.add(cart.position()));
        cubes.forEach(cube -> movers.add(cube.position()));
    }

    private void readBlocks() {
        int fromX = SectionPos.blockToSectionCoord(centre.getX() - RADIUS);
        int toX = SectionPos.blockToSectionCoord(centre.getX() + RADIUS);
        int fromZ = SectionPos.blockToSectionCoord(centre.getZ() - RADIUS);
        int toZ = SectionPos.blockToSectionCoord(centre.getZ() + RADIUS);
        for (int chunkX = fromX; chunkX <= toX; chunkX++) {
            for (int chunkZ = fromZ; chunkZ <= toZ; chunkZ++) {
                LevelChunk chunk = MC.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null || !readChunk(chunk)) {
                    whole = false;
                    return;
                }
            }
        }
    }

    // A section whose palette holds no TNT is never read block by block. False once more TNT
    // has turned up than is kept.
    private boolean readChunk(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            int baseY = chunk.getMinY() + SectionPos.sectionToBlockCoord(index);
            boolean outside = baseY + SectionPos.SECTION_MAX_INDEX < centre.getY() - RADIUS
                || baseY > centre.getY() + RADIUS;
            if (outside || section.hasOnlyAir() || !section.maybeHas(TntChain::isTnt)) {
                continue;
            }
            if (!readSection(section, chunk.getPos().getMinBlockX(), baseY, chunk.getPos().getMinBlockZ())) {
                return false;
            }
        }
        return true;
    }

    // Only the part of the section within the radius of the spot is read.
    private boolean readSection(LevelChunkSection section, int baseX, int baseY, int baseZ) {
        int lowX = Math.max(0, centre.getX() - RADIUS - baseX);
        int highX = Math.min(SectionPos.SECTION_MAX_INDEX, centre.getX() + RADIUS - baseX);
        int lowY = Math.max(0, centre.getY() - RADIUS - baseY);
        int highY = Math.min(SectionPos.SECTION_MAX_INDEX, centre.getY() + RADIUS - baseY);
        int lowZ = Math.max(0, centre.getZ() - RADIUS - baseZ);
        int highZ = Math.min(SectionPos.SECTION_MAX_INDEX, centre.getZ() + RADIUS - baseZ);
        for (int y = lowY; y <= highY; y++) {
            for (int x = lowX; x <= highX; x++) {
                for (int z = lowZ; z <= highZ; z++) {
                    if (isTnt(section.getBlockState(x, y, z)) && !keep(new BlockPos(baseX + x, baseY + y, baseZ + z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // False once more TNT has been read than is kept.
    private boolean keep(BlockPos block) {
        if (blasts.size() >= MOST_BLOCKS) {
            return false;
        }
        int index = blasts.size();
        blasts.add(new Blast(blastPoint(block), block));
        blockAt.put(block, index);
        cells.computeIfAbsent(cellOf(block), key -> new ArrayList<>()).add(index);
        return true;
    }

    private static BlockPos cellOf(BlockPos block) {
        return new BlockPos(Math.floorDiv(block.getX(), CELL), Math.floorDiv(block.getY(), CELL),
            Math.floorDiv(block.getZ(), CELL));
    }

    private static boolean isTnt(BlockState state) {
        return state.is(Blocks.TNT);
    }
}
