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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Marks the chunks the server generated whilst the client watched. World
 * generation lays liquid down as source blocks and the spreading only happens
 * once a chunk is live. A chunk that arrives already holding flowing liquid was
 * therefore read back off the disk. A chunk that starts flowing after it lands
 * was made just now.
 */
public final class NewChunks extends Module {

    private static final int MAX_CHUNKS = 16384;

    private final BoolSetting showOld = new BoolSetting("Show old chunks",
        "Also draws the chunks judged old since you switched this on.", false);
    private final BoolSetting showUnjudged = new BoolSetting("Show unjudged",
        "Also draws the chunks that arrived without enough liquid to judge.", false);
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
        "How far away a chunk may be and still be drawn.", 16, 4, 64, 1, " chunks")
        .min(1).max(256);
    private final NumberSetting settle = new NumberSetting("Settle time",
        "How long after a chunk lands a liquid flow still counts as fresh.", 60, 5, 300, 5, "s")
        .min(1).max(3600);
    private final BoolSetting showReasons = new BoolSetting("Show reasons",
        "Marks the liquid block that decided each verdict.", false);
    private final BoolSetting notice = new BoolSetting("Notice",
        "Explains the limits in chat when you switch this on.", true);

    private final Set<Long> newChunks = new HashSet<>();
    private final Set<Long> oldChunks = new HashSet<>();

    // Insertion ordered. Every chunk that landed whilst this ran mapped to its tick.
    private final Map<Long, Integer> watched = new LinkedHashMap<>();

    // Filled from the network thread and drained on the next tick.
    private final Queue<BlockPos> flowing = new ConcurrentLinkedQueue<>();

    // The block that decided each verdict. Drawn by the Show reasons setting.
    private final Map<Long, BlockPos> reasons = new HashMap<>();

    // The chunks in range of the last rebuild kept apart by verdict. Colours are
    // resolved at draw time because a rainbow setting moves every frame.
    private final List<Long> visibleNew = new ArrayList<>();
    private final List<Long> visibleOld = new ArrayList<>();
    private final List<Long> visibleUnjudged = new ArrayList<>();

    private long visibleAt = Long.MIN_VALUE;
    private int revision;
    private int drawnRevision = -1;
    private int drawnLayout = -1;

    private WeakReference<Level> world;
    private int ticks;

    public NewChunks() {
        super("NewChunks", "Marks fresh and old chunks that load whilst this is on.",
            Category.RENDER);
        addSettings(showOld, showUnjudged, newColor, oldColor, unjudgedColor, fill,
            followHeight, drawHeight, distance, settle, showReasons, notice);
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
            ChatUtil.message("§bNewChunks §7only judges chunks that load whilst it is on. "
                + "Switch it on before you explore.");
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
        reasons.clear();
        flowing.clear();
        clearVisible();
        visibleAt = Long.MIN_VALUE;
        revision++;
        ticks = 0;
    }

    // The oldest chunk drops once the store is full.
    private void remember(long key) {
        watched.put(key, ticks);
        revision++;
        if (watched.size() <= MAX_CHUNKS) {
            return;
        }
        Iterator<Long> iterator = watched.keySet().iterator();
        while (watched.size() > MAX_CHUNKS && iterator.hasNext()) {
            long eldest = iterator.next();
            iterator.remove();
            newChunks.remove(eldest);
            oldChunks.remove(eldest);
            reasons.remove(eldest);
        }
    }

    /**
     * Called from ClientPacketListenerMixin the moment a chunk has been put into
     * the world. Every later packet in the stream is still waiting. The chunk
     * therefore still holds exactly what the server sent and no flow update has
     * been written into it yet.
     */
    public void onChunkLoaded(int x, int z) {
        if (!isEnabled() || mc.level == null) {
            return;
        }
        long key = ChunkPos.pack(x, z);
        if (watched.containsKey(key)) {
            // A second arrival keeps the first verdict.
            return;
        }
        remember(key);

        LevelChunk chunk = mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk == null) {
            return;
        }
        BlockPos found = findFlowingLiquid(chunk);
        if (found != null) {
            oldChunks.add(key);
            reasons.put(key, found);
        }
    }

    // Fired on the netty thread.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundBlockUpdatePacket update) {
            noteFlow(update.getPos(), update.getBlockState());
        } else if (event.getPacket() instanceof ClientboundSectionBlocksUpdatePacket update) {
            update.runUpdates(this::noteFlow);
        }
    }

    private void noteFlow(BlockPos pos, BlockState state) {
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty() && !fluid.isSource()) {
            flowing.add(pos.immutable());
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

        // A flow that turns up after the chunk landed means it was made just now.
        int window = settle.getInt() * 20;
        BlockPos pos;
        while ((pos = flowing.poll()) != null) {
            long key = ChunkPos.pack(pos);
            Integer seen = watched.get(key);
            if (seen == null || oldChunks.contains(key)) {
                continue;
            }
            if (ticks - seen <= window && newChunks.add(key)) {
                reasons.put(key, pos);
                revision++;
            }
        }
    }

    /**
     * The first flowing liquid in the chunk or null. The palette test is only a
     * prefilter. It reads the palette which can still list a state the section
     * stopped holding. Every hit is confirmed against the real blocks.
     */
    private static BlockPos findFlowingLiquid(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int minY = chunk.getMinY();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir() || !section.hasFluid()
                || !section.maybeHas(NewChunks::isFlowing)) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (isFlowing(section.getBlockState(x, y, z))) {
                            return new BlockPos(minX + x, minY + i * 16 + y, minZ + z);
                        }
                    }
                }
            }
        }
        return null;
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
        int centerX = mc.player.chunkPosition().x();
        int centerZ = mc.player.chunkPosition().z();
        long here = ChunkPos.pack(centerX, centerZ);
        int layout = layoutKey();
        if (here != visibleAt || revision != drawnRevision || layout != drawnLayout) {
            rebuildVisible(centerX, centerZ);
            visibleAt = here;
            drawnRevision = revision;
            drawnLayout = layout;
        }

        double y = followHeight.isOn() ? mc.player.getY() : drawHeight.getValue();
        drawAll(event.getBatch(), visibleNew, newColor.getColor(), y);
        drawAll(event.getBatch(), visibleOld, oldColor.getColor(), y);
        drawAll(event.getBatch(), visibleUnjudged, unjudgedColor.getColor(), y);

        if (showReasons.isOn()) {
            markReasons(event.getBatch(), visibleNew, newColor.getColor());
            markReasons(event.getBatch(), visibleOld, oldColor.getColor());
        }
    }

    // Draws the block that proved the verdict. Lets you check the call yourself.
    private void markReasons(DrawBatch batch, List<Long> chunks, int color) {
        for (long key : chunks) {
            BlockPos pos = reasons.get(key);
            if (pos != null) {
                batch.outlineBlock(pos, color, true);
            }
        }
    }

    private void drawAll(DrawBatch batch, List<Long> chunks, int color, double y) {
        for (long key : chunks) {
            square(batch, ChunkPos.getX(key) * 16, ChunkPos.getZ(key) * 16, y, color);
        }
    }

    private void clearVisible() {
        visibleNew.clear();
        visibleOld.clear();
        visibleUnjudged.clear();
    }

    // The settings that decide which chunks land in the visible lists.
    private int layoutKey() {
        return (showOld.isOn() ? 1 : 0) | (showUnjudged.isOn() ? 2 : 0) | distance.getInt() << 2;
    }

    /**
     * Walks the square of chunks around the player instead of the whole store.
     * The work per frame is then tied to the draw distance and never to how far
     * the player has travelled.
     */
    private void rebuildVisible(int centerX, int centerZ) {
        clearVisible();
        int limit = distance.getInt();
        boolean drawOld = showOld.isOn();
        boolean drawUnjudged = showUnjudged.isOn();

        for (int x = centerX - limit; x <= centerX + limit; x++) {
            for (int z = centerZ - limit; z <= centerZ + limit; z++) {
                long key = ChunkPos.pack(x, z);
                if (newChunks.contains(key)) {
                    visibleNew.add(key);
                } else if (oldChunks.contains(key)) {
                    if (drawOld) {
                        visibleOld.add(key);
                    }
                } else if (drawUnjudged && watched.containsKey(key)) {
                    visibleUnjudged.add(key);
                }
            }
        }
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
