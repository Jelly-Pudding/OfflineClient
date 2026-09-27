package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.ServerStore;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ChunkWindow;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.NearestCut;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.ServerWatch;
import com.jellypudding.offlineclient.util.WorldWatch;
import com.jellypudding.offlineclient.worldgen.OreGround;
import com.jellypudding.offlineclient.worldgen.OreKind;
import com.jellypudding.offlineclient.worldgen.OreSimulation;
import com.jellypudding.offlineclient.worldgen.OreTables;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

// Replays the ore step of world generation from the seed for every chunk that arrives.
// Anti xray can hide an ore from the client but not from the seed. A chunk is worked out
// once all eight of its neighbours have arrived because ore veins cross chunk edges.
public final class OreSight extends Module {

    // One worked out ore block. Built on the worker thread.
    private record Spot(int x, int y, int z, OreKind kind) {
    }

    // Replaying a chunk takes a few milliseconds. One quiet thread keeps it off the game.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "OfflineClient OreSight");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    // A worked out chunk outlives Range. Replayed again it would meet ground the player has dug
    // and go out of step. This holds several times the chunks the widest Range covers.
    private static final int REMEMBERED_CHUNKS = 1024;

    private static final Direction[] SIDES = Direction.values();

    private final ChoiceListSetting ores = new ChoiceListSetting("Ores",
        "The ores to show. The large iron and copper veins are left out.",
        () -> List.of(labels()), List.of(OreKind.DIAMOND.label(), OreKind.ANCIENT_DEBRIS.label()));
    private final NumberSetting range = new NumberSetting("Range",
        "Chunk radius to work out around you.", 4, 1, 8, 1, " chunks").max(ChunkMap.MAX_VIEW_DISTANCE);
    private final NumberSetting limit = new NumberSetting("Limit",
        "Most ore blocks drawn at once with the nearest first.", 2000, 100, 5000, 100).min(1);
    private final BoxStyle style = BoxStyle.shapeOnly(BoxStyle.Shape.LINES);
    private final Map<OreKind, ColorSetting> colours = new EnumMap<>(OreKind.class);

    private final WorldWatch world = new WorldWatch();
    // Kept in access order. The chunks around the player stay whilst far ones drop out.
    private final Map<Long, List<Spot>> worked = new BoundedMap<>(REMEMBERED_CHUNKS, true);
    private final Map<Long, Future<List<Spot>>> pending = new HashMap<>();
    // The seed the worked chunks came from. Empty whilst none is known.
    private OptionalLong seedInUse = OptionalLong.empty();
    // Null in a dimension without ores or whilst no seed is known.
    private OreSimulation simulation;
    // The missing seed is pointed out once on each server.
    private final ServerWatch told = new ServerWatch();
    // Set whenever a chunk result or the ore list changes.
    private boolean stale;
    // The chunk and radius the shown spots were picked around.
    private long shownCentre;
    private int shownRadius;
    private List<Spot> shown = List.of();
    private final NearestCut<Spot> drawn = new NearestCut<>(
        (spot, eye) -> eye.distanceToSqr(spot.x() + 0.5, spot.y() + 0.5, spot.z() + 0.5));
    // The blocks drawn this frame. Touching boxes of one ore are joined.
    private final List<Spot> visible = new ArrayList<>();
    private final Long2ObjectOpenHashMap<OreKind> visibleAt = new Long2ObjectOpenHashMap<>();

    public OreSight() {
        super("OreSight", "Shows the ores the world seed placed even where anti xray hides them.", Category.RENDER);
        addSettings(ores, range, limit);
        addSettings(style.settings());
        for (OreKind kind : OreKind.values()) {
            float[] shade = ColorUtil.hsvOf(kind.colour());
            ColorSetting colour = new ColorSetting(kind.label() + " colour",
                "The colour " + kind.label().toLowerCase(Locale.ROOT) + " is drawn in.",
                shade[0], shade[1], shade[2], false).visibleWhen(() -> ores.contains(kind.label()));
            colours.put(kind, colour);
            addSettings(colour);
        }
        searchTags("ore", "seed", "xray", "ore finder", "anti xray");
        ores.onChange(() -> stale = true);
    }

    private static String[] labels() {
        OreKind[] kinds = OreKind.values();
        String[] labels = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            labels[i] = kinds[i].label();
        }
        return labels;
    }

    @Override
    public String getSuffix() {
        return count(shown.size());
    }

    @Override
    protected void onEnable() {
        told.forget();
    }

    @Override
    protected void onDisable() {
        cancelPending();
    }

    // Worked out chunks are kept until the world or the seed changes.
    private void forget() {
        cancelPending();
        worked.clear();
        shown = List.of();
        drawn.clear();
        stale = false;
    }

    private void cancelPending() {
        for (Future<List<Spot>> job : pending.values()) {
            job.cancel(true);
        }
        pending.clear();
    }

    // A single player world gives its own seed. A server needs one saved with the seed command.
    private static OptionalLong currentSeed() {
        if (mc.hasSingleplayerServer()) {
            return OptionalLong.of(mc.getSingleplayerServer().overworld().getSeed());
        }
        Long saved = ServerStore.seeds().get(ServerInfo.key());
        return saved == null ? OptionalLong.empty() : OptionalLong.of(saved);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        OptionalLong seed = currentSeed();
        if (world.changed() || !seed.equals(seedInUse)) {
            forget();
            seedInUse = seed;
            OreTables.Table table = OreTables.of(mc.level.dimension());
            simulation = seed.isEmpty() || table == null ? null : new OreSimulation(table, seed.getAsLong());
        }
        if (seed.isEmpty()) {
            tellNoSeed();
            return;
        }
        if (simulation == null) {
            return;
        }
        collectFinished();
        int centreX = mc.player.chunkPosition().x();
        int centreZ = mc.player.chunkPosition().z();
        int radius = range.getInt();
        pending.entrySet().removeIf(entry -> {
            boolean out = outOfRange(entry.getKey(), centreX, centreZ, radius);
            if (out) {
                entry.getValue().cancel(true);
            }
            return out;
        });
        OreSimulation replayer = simulation;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int chunkX = centreX + dx;
                int chunkZ = centreZ + dz;
                long key = ChunkPos.pack(chunkX, chunkZ);
                if (worked.containsKey(key) || pending.containsKey(key)) {
                    continue;
                }
                ChunkWindow window = ChunkWindow.capture(chunkX, chunkZ, 1);
                if (window.complete()) {
                    pending.put(key, WORKER.submit(() -> replay(replayer, window, chunkX, chunkZ)));
                }
            }
        }
        long centre = ChunkPos.pack(centreX, centreZ);
        if (stale || centre != shownCentre || radius != shownRadius) {
            stale = false;
            shownCentre = centre;
            shownRadius = radius;
            shown = chosenSpots(centreX, centreZ, radius);
        }
        drawn.update(shown, limit.getInt());
    }

    private void tellNoSeed() {
        if (told.changed()) {
            ChatUtil.error("OreSight needs the seed of this server. Type "
                + OfflineClient.INSTANCE.getCommandManager().getPrefix() + "seed set <seed>");
        }
    }

    private static List<Spot> replay(OreSimulation simulation, ChunkWindow window, int chunkX, int chunkZ) {
        List<Spot> spots = new ArrayList<>();
        OreGround ground = new OreGround(window::get, window::noiseBiome, simulation.table());
        simulation.run(chunkX, chunkZ, ground, (ore, x, y, z) -> {
            if (ore.kind() != null) {
                spots.add(new Spot(x, y, z, ore.kind()));
            }
        });
        return spots;
    }

    private void collectFinished() {
        for (Iterator<Map.Entry<Long, Future<List<Spot>>>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Future<List<Spot>>> entry = it.next();
            Future<List<Spot>> job = entry.getValue();
            if (!job.isDone()) {
                continue;
            }
            it.remove();
            if (job.isCancelled()) {
                continue;
            }
            try {
                worked.put(entry.getKey(), job.get());
                stale = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                // A chunk that changed under the worker is replayed on a later tick.
                OfflineClient.LOG.debug("OreSight could not replay a chunk", e.getCause());
            }
        }
    }

    // Neighbouring chunks may both claim one block. The first claim stands.
    private List<Spot> chosenSpots(int centreX, int centreZ, int radius) {
        LongOpenHashSet taken = new LongOpenHashSet();
        List<Spot> chosen = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                List<Spot> spots = worked.get(ChunkPos.pack(centreX + dx, centreZ + dz));
                if (spots == null) {
                    continue;
                }
                for (Spot spot : spots) {
                    long key = BlockPos.asLong(spot.x(), spot.y(), spot.z());
                    if (ores.contains(spot.kind().label()) && taken.add(key)) {
                        chosen.add(spot);
                    }
                }
            }
        }
        return chosen;
    }

    private static boolean outOfRange(long key, int centreX, int centreZ, int radius) {
        return Math.abs(ChunkPos.getX(key) - centreX) > radius || Math.abs(ChunkPos.getZ(key) - centreZ) > radius;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        visible.clear();
        visibleAt.clear();
        for (Spot spot : drawn.result()) {
            pos.set(spot.x(), spot.y(), spot.z());
            if (couldStillHold(pos, next, spot.kind())) {
                visible.add(spot);
                visibleAt.put(pos.asLong(), spot.kind());
            }
        }
        DrawBatch batch = event.getBatch();
        for (Spot spot : visible) {
            int colour = colours.get(spot.kind()).getColor();
            AABB box = new AABB(spot.x(), spot.y(), spot.z(), spot.x() + 1, spot.y() + 1, spot.z() + 1);
            long key = BlockPos.asLong(spot.x(), spot.y(), spot.z());
            style.drawJoined(batch, box, DrawBatch.sharedSides(visibleAt, key, spot.kind()), colour, colour, true);
        }
    }

    // Dug out ground shows as air or fluid. Anti xray sends a block that touches open space
    // as it really is and an exposed block of any other kind proves the replay wrong there.
    private static boolean couldStillHold(BlockPos pos, BlockPos.MutableBlockPos next, OreKind kind) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (OreTables.kindOf(state.getBlock()) == kind) {
            return true;
        }
        for (Direction side : SIDES) {
            next.setWithOffset(pos, side);
            if (!mc.level.getBlockState(next).isRedstoneConductor(mc.level, next)) {
                return false;
            }
        }
        return true;
    }
}
