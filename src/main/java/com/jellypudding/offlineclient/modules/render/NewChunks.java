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
import com.jellypudding.offlineclient.util.ChatUtil;
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

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

// Marks the chunks the server generated for the first time whilst the client watched.
// Only a chunk whose data packet lands whilst this runs can ever reach a verdict.
public final class NewChunks extends Module {

    // Room for the burst of arrivals that follows a join or a teleport.
    private static final int SCANS_PER_TICK = 256;

    private static final int MAX_CHUNKS = 32768;

    private final BoolSetting showOld = new BoolSetting("Show old chunks",
        "Also draws the chunks judged old since you switched this on.", false);
    private final BoolSetting showUnjudged = new BoolSetting("Show unjudged",
        "Also draws the chunks this watched arrive without reaching a verdict.", false);
    private final ColorSetting newColor = new ColorSetting("New color",
        "Colour of the fresh chunks.", 0, false);
    private final ColorSetting oldColor = new ColorSetting("Old color",
        "Colour of the chunks that were already on disk.", 220, false)
        .visibleWhen(showOld::isOn);
    private final ColorSetting unjudgedColor = new ColorSetting("Unjudged color",
        "Colour of the chunks with no verdict.", 60, false)
        .visibleWhen(showUnjudged::isOn);
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
    private final NumberSetting settle = new NumberSetting("Settle time",
        "How long after a chunk loads a liquid flow still counts as fresh.", 60, 5, 300, 5, "s")
        .min(1).max(3600);
    private final BoolSetting notice = new BoolSetting("Notice",
        "Explains the limits in chat when you switch this on.", true);

    private final Set<Long> newChunks = new HashSet<>();
    private final Set<Long> oldChunks = new HashSet<>();

    // Insertion ordered. Holds every chunk whose data packet landed whilst this ran.
    // The value is the tick it landed on.
    private final Map<Long, Integer> watched = new LinkedHashMap<>();

    // Filled from the network thread and drained on the next tick.
    private final Queue<Long> flowing = new ConcurrentLinkedQueue<>();
    private final Queue<Long> arrived = new ConcurrentLinkedQueue<>();

    // Chunks whose data still needs the palette test. Client thread only.
    private final Queue<Long> pending = new ArrayDeque<>();
    private final Queue<Long> retry = new ArrayDeque<>();

    private WeakReference<Level> world;
    private int ticks;

    public NewChunks() {
        super("NewChunks", "Marks fresh and old chunks that load whilst this is on.",
            Category.RENDER);
        addSettings(showOld, showUnjudged, newColor, oldColor, unjudgedColor, fill,
            followHeight, drawHeight, distance, settle, notice);
        searchTags("new chunks", "fresh terrain", "exploit");
    }

    @Override
    public String getSuffix() {
        int unjudged = Math.max(0, watched.size() - newChunks.size() - oldChunks.size());
        return newChunks.size() + " new " + oldChunks.size() + " old " + unjudged + " unjudged";
    }

    @Override
    protected void onEnable() {
        reset();
        if (notice.isOn()) {
            ChatUtil.message("§bNewChunks §7judges only the chunks that load whilst it is on. "
                + "Switch it on before you head into fresh land. A verdict needs liquid in the "
                + "chunk. Everything else stays unjudged.");
        }
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        newChunks.clear();
        oldChunks.clear();
        watched.clear();
        flowing.clear();
        arrived.clear();
        pending.clear();
        retry.clear();
        ticks = 0;
    }

    // The oldest chunk drops once the store is full.
    private void remember(long key) {
        watched.put(key, ticks);
        if (watched.size() <= MAX_CHUNKS) {
            return;
        }
        Iterator<Long> iterator = watched.keySet().iterator();
        while (watched.size() > MAX_CHUNKS && iterator.hasNext()) {
            long eldest = iterator.next();
            iterator.remove();
            newChunks.remove(eldest);
            oldChunks.remove(eldest);
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

    // Generation writes liquid as source blocks and the spreading only starts once the
    // chunk is live. A flow update is the giveaway.
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
        ticks++;

        // Arrivals are taken first. A flow update from the same batch then finds its chunk.
        // A second arrival of the same chunk keeps the first verdict. Liquid spreads whilst
        // a chunk sits loaded.
        Long key;
        while ((key = arrived.poll()) != null) {
            if (watched.containsKey(key)) {
                continue;
            }
            remember(key);
            pending.add(key);
        }

        // A flow marks the chunk fresh before the palette test can call it old.
        int window = settle.getInt() * 20;
        while ((key = flowing.poll()) != null) {
            Integer seen = watched.get(key);
            if (seen == null || oldChunks.contains(key)) {
                continue;
            }
            if (ticks - seen <= window) {
                newChunks.add(key);
            }
        }

        int budget = SCANS_PER_TICK;
        while (budget > 0 && (key = retry.poll()) != null) {
            budget--;
            check(key, false);
        }
        while (budget > 0 && (key = pending.poll()) != null) {
            budget--;
            check(key, true);
        }
    }

    // Liquid that had already spread before the save travels inside the chunk data.
    private void check(long key, boolean mayRetry) {
        // The store may have dropped this chunk whilst the scan sat queued.
        if (!watched.containsKey(key)) {
            return;
        }
        if (newChunks.contains(key) || oldChunks.contains(key)) {
            return;
        }
        LevelChunk chunk = mc.level.getChunkSource()
            .getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false);
        if (chunk == null) {
            // The packet landed after the client had run its jobs for this tick.
            if (mayRetry) {
                retry.add(key);
            }
            return;
        }
        if (hasFlowingLiquid(chunk)) {
            oldChunks.add(key);
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

        if (showUnjudged.isOn()) {
            int color = unjudgedColor.getColor();
            for (long key : watched.keySet()) {
                if (newChunks.contains(key) || oldChunks.contains(key)) {
                    continue;
                }
                plot(event.getBatch(), key, color, y, limit, centerX, centerZ);
            }
        }
        draw(event.getBatch(), newChunks, newColor.getColor(), y, limit, centerX, centerZ);
        if (showOld.isOn()) {
            draw(event.getBatch(), oldChunks, oldColor.getColor(), y, limit, centerX, centerZ);
        }
    }

    private void draw(DrawBatch batch, Set<Long> chunks, int color, double y,
                      int limit, int centerX, int centerZ) {
        for (long key : chunks) {
            plot(batch, key, color, y, limit, centerX, centerZ);
        }
    }

    private void plot(DrawBatch batch, long key, int color, double y,
                      int limit, int centerX, int centerZ) {
        int x = ChunkPos.getX(key);
        int z = ChunkPos.getZ(key);
        if (Math.abs(x - centerX) > limit || Math.abs(z - centerZ) > limit) {
            return;
        }
        square(batch, x * 16, z * 16, y, color);
    }

    // A flat box has no edges to draw.
    private void square(DrawBatch batch, double x1, double z1, double y, int color) {
        double x2 = x1 + 16;
        double z2 = z1 + 16;
        batch.flatRect(x1, z1, x2, z2, y, color, true);
        if (fill.isOn()) {
            batch.solidBox(new AABB(x1, y, z1, x2, y + 0.02, z2),
                ColorUtil.withAlpha(color, 40), true);
        }
    }
}
