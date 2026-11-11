package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.ChunkScanner;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

// The cave air round the player drawn as one surface. Each chunk keeps its cave air packed as it
// is scanned. The nearest chunks are taken whole until the limit is reached and only the last of
// them is sorted block by block. The cut is worked out again when the player crosses into another
// chunk or the limit changes and at most once a second as chunks come and go.
public final class CaveSurface {

    // The cave air of one chunk as packed block positions.
    private record Chunk(int x, int z, long[] cells) {

        // Measured flat from the eyes to the nearest edge of the chunk.
        double distanceSqr(Vec3 eye) {
            double dx = Math.max(0, Math.abs(eye.x - middle(x)) - HALF_CHUNK);
            double dz = Math.max(0, Math.abs(eye.z - middle(z)) - HALF_CHUNK);
            return dx * dx + dz * dz;
        }

        private static double middle(int section) {
            return SectionPos.sectionToBlockCoord(section) + HALF_CHUNK;
        }
    }

    // A drawn block that shows at least one face and the sides it hides behind its neighbours.
    private record Shown(AABB box, int hidden) {
    }

    private static final double HALF_CHUNK = SectionPos.SECTION_SIZE / 2.0;
    // New scans wait this long before the cut is worked out again.
    private static final int RECUT_TICKS = 20;
    // A block hidden on every side lies inside the cave and shows nothing.
    private static final int ENCLOSED = (1 << Direction.values().length) - 1;

    private final ChunkScanner<Chunk> scanner = new ChunkScanner<>();
    private List<Chunk> cutFrom = List.of();
    private long cutChunk;
    private int cutLimit = -1;
    private int cutAt;
    private List<Shown> shown = List.of();

    // Safe from any thread.
    public void markChanged(Packet<?> packet) {
        scanner.markChanged(packet);
    }

    // Once a tick whilst the surface is shown.
    public void update(int radius, HeightBand.Rows rows, int limit) {
        scanner.update(radius, (view, out) -> {
            LongArrayList cells = new LongArrayList();
            view.forEachMatching(Math.max(view.minY(), rows.from()), Math.min(view.maxY(), rows.to()),
                state -> state.getBlock() == Blocks.CAVE_AIR, (x, y, z, state) -> cells.add(BlockPos.asLong(x, y, z)));
            if (!cells.isEmpty()) {
                out.add(new Chunk(view.pos().x(), view.pos().z(), cells.toLongArray()));
            }
        });
        LocalPlayer player = OfflineClient.MC.player;
        long here = ChunkPos.pack(player.blockPosition());
        int now = player.tickCount;
        List<Chunk> found = scanner.results();
        boolean moved = here != cutChunk || limit != cutLimit;
        // Whilst nothing shows a new scan is cut at once.
        boolean landed = found != cutFrom && (shown.isEmpty() || now - cutAt >= RECUT_TICKS || now < cutAt);
        if (moved || landed) {
            cutFrom = found;
            cutChunk = here;
            cutLimit = limit;
            cutAt = now;
            cut(found, player.getEyePosition(), limit);
        }
    }

    // Whole chunks nearest first until the limit. The chunk that does not fit whole gives its
    // nearest blocks.
    private void cut(List<Chunk> found, Vec3 eye, int limit) {
        List<Chunk> order = new ArrayList<>(found);
        order.sort(Comparator.comparingDouble(chunk -> chunk.distanceSqr(eye)));
        LongArrayList picked = new LongArrayList();
        for (Chunk chunk : order) {
            int room = limit - picked.size();
            if (room <= 0) {
                break;
            }
            if (chunk.cells().length <= room) {
                picked.addElements(picked.size(), chunk.cells());
            } else {
                nearest(chunk.cells(), room, eye, picked);
            }
        }
        join(picked);
    }

    // As many of the nearest cells as there is room for. A distance and the place of its cell share
    // one long with the distance on top and a plain sort puts the nearest first.
    private static void nearest(long[] cells, int room, Vec3 eye, LongArrayList picked) {
        long[] keyed = new long[cells.length];
        for (int i = 0; i < cells.length; i++) {
            double dx = BlockPos.getX(cells[i]) + 0.5 - eye.x;
            double dy = BlockPos.getY(cells[i]) + 0.5 - eye.y;
            double dz = BlockPos.getZ(cells[i]) + 0.5 - eye.z;
            keyed[i] = (long) Float.floatToIntBits((float) (dx * dx + dy * dy + dz * dz)) << 32 | i;
        }
        Arrays.sort(keyed);
        for (int i = 0; i < room; i++) {
            picked.add(cells[(int) keyed[i]]);
        }
    }

    // Two picked blocks side by side hide the faces they share on all six sides.
    private void join(LongArrayList picked) {
        Long2ObjectOpenHashMap<Block> drawn = new Long2ObjectOpenHashMap<>(picked.size());
        for (int i = 0; i < picked.size(); i++) {
            drawn.put(picked.getLong(i), Blocks.CAVE_AIR);
        }
        List<Shown> faces = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            long cell = picked.getLong(i);
            int hidden = DrawBatch.sharedSides(drawn, cell, Blocks.CAVE_AIR);
            if (hidden != ENCLOSED) {
                int x = BlockPos.getX(cell);
                int y = BlockPos.getY(cell);
                int z = BlockPos.getZ(cell);
                faces.add(new Shown(new AABB(x, y, z, x + 1, y + 1, z + 1), hidden));
            }
        }
        shown = faces;
    }

    public void draw(DrawBatch batch, BoxStyle style) {
        for (Shown cell : shown) {
            style.drawJoined(batch, cell.box(), cell.hidden(), true);
        }
    }

    public void reset() {
        scanner.reset();
        cutFrom = List.of();
        cutLimit = -1;
        shown = List.of();
    }
}
