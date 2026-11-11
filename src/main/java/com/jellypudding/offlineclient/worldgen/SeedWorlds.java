package com.jellypudding.offlineclient.worldgen;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.ServerStore;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.util.Background;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

// The worlds the plain game makes from the seed of the place you play in. The game's own data pack
// is read on a worker the first time a world is asked for and each dimension is built from it when
// wanted. All of it is let go once the server or its seed changes. Ask on the game thread.
public final class SeedWorlds {

    // The plain game makes these dimensions and no other.
    private static final Map<ResourceKey<Level>, ResourceKey<LevelStem>> STEMS = Map.of(
        Level.OVERWORLD, LevelStem.OVERWORLD, Level.NETHER, LevelStem.NETHER, Level.END, LevelStem.END);

    // Reading the data pack takes most of a second.
    private static final ExecutorService WORKER = Background.worker("OfflineClient seed worlds");

    private static SeedWorlds instance;

    // The worldgen registries of the plain game and the dimensions of its default world.
    private record Plain(RegistryAccess.Frozen registries, WorldDimensions dimensions) {

        // The steps WorldLoader takes before it opens a world. The tags of the game's fixed
        // registries are only read and the registries themselves stay as they were.
        private static Plain load() {
            LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
            try (CloseableResourceManager data = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
                List<Registry.PendingTags<?>> tags = TagLoader.loadTagsForExistingRegistries(data,
                    layers.getLayer(RegistryLayer.STATIC));
                List<HolderLookup.RegistryLookup<?>> lookups = TagLoader.buildUpdatedLookups(
                    layers.getAccessForLoading(RegistryLayer.WORLD), tags);
                RegistryAccess.Frozen world = RegistryDataLoader.load(data, lookups,
                    RegistryDataLoader.WORLD_REGISTRIES, Runnable::run).join();
                RegistryAccess.Frozen all = layers.replaceFrom(RegistryLayer.WORLD, world).compositeAccess();
                return new Plain(all, WorldPresets.createNormalWorldDimensions(all));
            }
        }
    }

    private final Map<ResourceKey<Level>, CompletableFuture<SeedWorld>> worlds = new HashMap<>();
    private CompletableFuture<Plain> plain;
    // The place the kept worlds belong to. Null whilst nothing is kept.
    private String server;
    private long seed;

    private SeedWorlds() {
        OfflineClient.INSTANCE.getEventBus().register(this);
    }

    // A single player world gives its own seed. A server needs one saved with the seed command.
    public static OptionalLong seed() {
        IntegratedServer local = OfflineClient.MC.getSingleplayerServer();
        if (local != null) {
            ServerLevel overworld = local.overworld();
            return overworld == null ? OptionalLong.empty() : OptionalLong.of(overworld.getSeed());
        }
        Long saved = ServerStore.seeds().get(ServerInfo.key());
        return saved == null ? OptionalLong.empty() : OptionalLong.of(saved);
    }

    // Whether the plain game has this dimension.
    public static boolean makes(ResourceKey<Level> dimension) {
        return STEMS.containsKey(dimension);
    }

    // The thread the worlds are built on. A slow question to a world belongs there too.
    public static Executor worker() {
        return WORKER;
    }

    // The seeded world of a dimension once it is built. It holds null with no seed known and in a
    // dimension the plain game does not make and when the game's data could not be read. The game
    // log names the last of these.
    public static CompletableFuture<SeedWorld> of(ResourceKey<Level> dimension) {
        if (instance == null) {
            instance = new SeedWorlds();
        }
        return instance.world(dimension);
    }

    private CompletableFuture<SeedWorld> world(ResourceKey<Level> dimension) {
        OptionalLong known = seed();
        ResourceKey<LevelStem> stem = STEMS.get(dimension);
        if (known.isEmpty() || stem == null) {
            return CompletableFuture.completedFuture(null);
        }
        String place = ServerInfo.key();
        long wanted = known.getAsLong();
        if (!place.equals(server) || wanted != seed) {
            drop();
            server = place;
            seed = wanted;
            plain = logged(CompletableFuture.supplyAsync(Plain::load, WORKER), "read the world generation of the game");
        }
        return worlds.computeIfAbsent(dimension, key -> logged(plain.thenApplyAsync(
            loaded -> loaded == null ? null : build(loaded, stem, wanted), WORKER), "build a world from the seed"));
    }

    private static SeedWorld build(Plain plain, ResourceKey<LevelStem> stem, long seed) {
        return new SeedWorld(plain.registries(), plain.dimensions().get(stem).orElseThrow(), seed);
    }

    // Work that failed holds null and leaves the reason in the log.
    private static <T> CompletableFuture<T> logged(CompletableFuture<T> work, String what) {
        return work.exceptionally(problem -> {
            OfflineClient.LOG.error("Could not {}", what, problem);
            return null;
        });
    }

    private void drop() {
        worlds.clear();
        plain = null;
        server = null;
    }

    @Subscribe
    private void onTick(ClientTickEvent event) {
        if (server == null) {
            return;
        }
        OptionalLong known = seed();
        if (known.isEmpty() || known.getAsLong() != seed || !server.equals(ServerInfo.key())) {
            drop();
        }
    }
}
