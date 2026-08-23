package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

// Marks the chunks the server generated for the first time whilst the client watched.
public final class NewChunks extends Module {

    private static final int SCANS_PER_TICK = 8;

    private static final int MAX_CHUNKS = 32768;

    private final BoolSetting showOld = new BoolSetting("Show old chunks",
        "Also marks the chunks that were already on disk.", false);
    private final ColorSetting newColor = new ColorSetting("New color",
        "Colour of the fresh chunks.", 0, false);
    private final ColorSetting oldColor = new ColorSetting("Old color",
        "Colour of the chunks that were already on disk.", 220, false)
        .visibleWhen(showOld::isOn);
    private final BoolSetting fill = new BoolSetting("Fill",
        "Adds a faint tint inside each square.", true);
    private final BoolSetting followHeight = new BoolSetting("Follow height",
        "Draws the squares at your own height instead of a fixed one.", true);
    private final NumberSetting drawHeight = new NumberSetting("Height",
        "The height the squares sit at.", 64, -64, 320, 1)
        .min(-2048).max(2048).visibleWhen(() -> !followHeight.isOn());
    private final NumberSetting distance = new NumberSetting("Distance",
        "How far away a chunk may be and still be drawn.", 32, 8, 64, 1, " chunks")
        .min(1).max(256);

    // Insertion ordered. The oldest entry drops when full.
    private final Set<Long> newChunks = new LinkedHashSet<>();
    private final Set<Long> oldChunks = new LinkedHashSet<>();

    // Filled from the network thread and drained on the next tick.
    private final Queue<Long> flowing = new ConcurrentLinkedQueue<>();
    private final Queue<Long> arrived = new ConcurrentLinkedQueue<>();

    private WeakReference<Level> world;

    public NewChunks() {
        super("NewChunks", "Marks chunks the server had never generated before.", Category.RENDER);
        addSettings(showOld, newColor, oldColor, fill, followHeight, drawHeight, distance);
        searchTags("new chunks", "fresh terrain", "exploit");
    }

    @Override
    public String getSuffix() {
        return String.valueOf(newChunks.size());
    }

    @Override
    protected void onEnable() {
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        newChunks.clear();
        oldChunks.clear();
        flowing.clear();
        arrived.clear();
    }

    private static void addBounded(Set<Long> chunks, long key) {
        chunks.add(key);
        if (chunks.size() <= MAX_CHUNKS) {
            return;
        }
        Iterator<Long> iterator = chunks.iterator();
        while (chunks.size() > MAX_CHUNKS && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    // Fired on the netty thread.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundLevelChunkWithLightPacket chunk) {
            arrived.add(ChunkPos.pack(chunk.getX(), chunk.getZ()));
        } else if (event.getPacket() instanceof ClientboundBlockUpdatePacket update) {
            noteFlow(update.getPos(), update.getBlockState());
        } else if (event.getPacket() instanceof ClientboundSectionBlocksUpdatePacket update) {
            update.runUpdates(this::noteFlow);
        }
    }

    /**
     * Generation writes liquid as source blocks and the spreading only
     * starts once the chunk is live. A flow update is the giveaway.
     */
    private void noteFlow(BlockPos pos, BlockState state) {
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty() && !fluid.isSource()) {
            flowing.add(ChunkPos.pack(pos));
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (world == null || world.get() != mc.level) {
            // A rejoin gives a fresh level object even in the same dimension.
            world = new WeakReference<>(mc.level);
            reset();
            return;
        }

        // Flow updates are read before arrivals.
        Long key;
        while ((key = flowing.poll()) != null) {
            if (!oldChunks.contains(key)) {
                addBounded(newChunks, key);
            }
        }

        int budget = SCANS_PER_TICK;
        while (budget > 0 && (key = arrived.poll()) != null) {
            budget--;
            if (newChunks.contains(key) || oldChunks.contains(key)) {
                continue;
            }
            LevelChunk chunk = mc.level.getChunkSource()
                .getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false);
            if (chunk != null && hasFlowingLiquid(chunk)) {
                addBounded(oldChunks, key);
            }
        }
    }

    // Palette test on every section that holds any liquid.
    private static boolean hasFlowingLiquid(LevelChunk chunk) {
        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null || !section.hasFluid()) {
                continue;
            }
            if (section.maybeHas(NewChunks::isFlowing)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFlowing(BlockState state) {
        FluidState fluid = state.getFluidState();
        return !fluid.isEmpty() && !fluid.isSource();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        double y = followHeight.isOn() ? mc.player.getY() : drawHeight.getValue();
        int limit = distance.getInt();
        int centerX = mc.player.chunkPosition().x();
        int centerZ = mc.player.chunkPosition().z();

        draw(event.getBatch(), newChunks, newColor.getColor(), y, limit, centerX, centerZ);
        if (showOld.isOn()) {
            draw(event.getBatch(), oldChunks, oldColor.getColor(), y, limit, centerX, centerZ);
        }
    }

    private void draw(DrawBatch batch, Set<Long> chunks, int color, double y,
                      int limit, int centerX, int centerZ) {
        for (long key : chunks) {
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            if (Math.abs(x - centerX) > limit || Math.abs(z - centerZ) > limit) {
                continue;
            }
            square(batch, x * 16, z * 16, y, color);
        }
    }

    // A flat box has no edges to draw.
    private void square(DrawBatch batch, double x1, double z1, double y, int color) {
        double x2 = x1 + 16;
        double z2 = z1 + 16;
        batch.line(new Vec3(x1, y, z1), new Vec3(x2, y, z1), color, true);
        batch.line(new Vec3(x2, y, z1), new Vec3(x2, y, z2), color, true);
        batch.line(new Vec3(x2, y, z2), new Vec3(x1, y, z2), color, true);
        batch.line(new Vec3(x1, y, z2), new Vec3(x1, y, z1), color, true);
        if (fill.isOn()) {
            batch.solidBox(new AABB(x1, y, z1, x2, y + 0.02, z2),
                ColorUtil.withAlpha(color, 40), true);
        }
    }
}
