package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.OreMarks;
import com.jellypudding.offlineclient.render.OreMarks.Spot;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Background;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ChunkWindow;
import com.jellypudding.offlineclient.util.ServerWatch;
import com.jellypudding.offlineclient.util.WorldWatch;
import com.jellypudding.offlineclient.worldgen.OreGround;
import com.jellypudding.offlineclient.worldgen.OreKind;
import com.jellypudding.offlineclient.worldgen.OreSimulation;
import com.jellypudding.offlineclient.worldgen.OreTables;
import com.jellypudding.offlineclient.worldgen.OreVeins;
import com.jellypudding.offlineclient.worldgen.SeedWorld;
import com.jellypudding.offlineclient.worldgen.SeedWorlds;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

// Anti xray can hide an ore from the client but not from the seed. A chunk is worked out once all
// eight of its neighbours have arrived because ore veins cross chunk edges. The large veins are
// laid before the ore step and come from the seeded world.
public final class OreSight extends Module {

    // Replaying a chunk takes a few milliseconds. One quiet thread keeps it off the game.
    private static final ExecutorService WORKER = Background.worker("OfflineClient OreSight");

    // A worked out chunk outlives Range. Replayed again it would meet ground the player has dug
    // and go out of step. This holds several times the chunks the widest Range covers.
    private static final int REMEMBERED_CHUNKS = 1024;

    private final OreMarks marks = new OreMarks(
        "The ores to show. The large iron and copper veins are picks of their own.",
        Arrays.asList(OreKind.values()));
    private final NumberSetting range = new NumberSetting("Range",
        "Chunk radius to work out around you.", 4, 1, 8, 1, " chunks").max(ChunkMap.MAX_VIEW_DISTANCE);

    private final WorldWatch world = new WorldWatch();
    // Kept in access order. The chunks around the player stay whilst far ones drop out.
    private final Map<Long, List<Spot>> worked = new BoundedMap<>(REMEMBERED_CHUNKS, true);
    private final Map<Long, Future<List<Spot>>> pending = new HashMap<>();
    private OptionalLong seedInUse = OptionalLong.empty();
    // Null in a dimension without ores or whilst no seed is known.
    private OreSimulation simulation;
    // The seeded world of a dimension with large veins. Null where there are none.
    private CompletableFuture<SeedWorld> seeded;
    // Null until the seeded world has been built. Only the worker reads the veins.
    private OreVeins veins;
    // The missing seed is pointed out once on each server.
    private final ServerWatch told = new ServerWatch();
    private boolean stale;
    private long shownCentre;
    private int shownRadius;
    private List<Spot> shown = List.of();

    public OreSight() {
        super("OreSight", "Shows the ores the world seed placed even where anti xray hides them.", Category.RENDER);
        addSettings(marks.picks(), range);
        addSettings(marks.look());
        searchTags("ore", "seed", "xray", "ore finder", "anti xray");
        marks.picks().onChange(() -> stale = true);
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

    private void forget() {
        cancelPending();
        worked.clear();
        shown = List.of();
        marks.clear();
        stale = false;
    }

    private void cancelPending() {
        for (Future<List<Spot>> job : pending.values()) {
            job.cancel(true);
        }
        pending.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        OptionalLong seed = SeedWorlds.seed();
        if (world.changed() || !seed.equals(seedInUse)) {
            forget();
            seedInUse = seed;
            OreTables.Table table = OreTables.of(mc.level.dimension());
            simulation = seed.isEmpty() || table == null ? null : new OreSimulation(table, seed.getAsLong());
            seeded = simulation == null || table.nether() ? null : SeedWorlds.of(mc.level.dimension());
            veins = null;
        }
        if (seed.isEmpty()) {
            tellNoSeed();
            return;
        }
        if (simulation == null || !veinsKnown()) {
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
        OreVeins veinSource = veins;
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
                    pending.put(key, WORKER.submit(() -> replay(replayer, veinSource, window, chunkX, chunkZ)));
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
        marks.update(shown);
    }

    // A chunk replayed without its veins would be kept that way. Replays wait until the seeded
    // world is built. One that cannot be built leaves the large veins out and the player is told.
    private boolean veinsKnown() {
        if (veins != null) {
            return true;
        }
        if (seeded != null && !seeded.isDone()) {
            return false;
        }
        SeedWorld built = seeded == null ? null : seeded.getNow(null);
        veins = built == null ? OreVeins.NONE : new OreVeins(built);
        if (seeded != null && built == null) {
            ChatUtil.error("OreSight could not read the world generation of the game and leaves the large veins"
                + " out. The game log says why.");
        }
        return true;
    }

    private void tellNoSeed() {
        if (told.changed()) {
            ChatUtil.error("OreSight needs the seed of this server. Type "
                + OfflineClient.INSTANCE.getCommandManager().getPrefix() + "seed set <seed>");
        }
    }

    private static List<Spot> replay(OreSimulation simulation, OreVeins veins, ChunkWindow window,
                                     int chunkX, int chunkZ) {
        List<Spot> spots = new ArrayList<>();
        OreGround ground = new OreGround(window::get, window::noiseBiome, simulation.table(), veins);
        simulation.run(chunkX, chunkZ, ground, (ore, x, y, z) -> {
            if (ore.kind() != null) {
                spots.add(new Spot(x, y, z, ore.kind()));
            }
        });
        veins.run(chunkX, chunkZ, ground, (kind, x, y, z) -> spots.add(new Spot(x, y, z, kind)));
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
                    if (marks.picked(spot.kind()) && taken.add(key)) {
                        chosen.add(spot);
                    }
                }
            }
        }
        return chosen;
    }

    private static boolean outOfRange(long key, int centreX, int centreZ, int radius) {
        return Mth.chessboardDistance(ChunkPos.getX(key), ChunkPos.getZ(key), centreX, centreZ) > radius;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        marks.draw(event.getBatch());
    }
}
