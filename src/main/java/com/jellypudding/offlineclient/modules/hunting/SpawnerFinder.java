package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ChunkDataEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// Finds the spawners a player has been near. A spawner only counts down whilst a player is in
// range and the server sends its count with the chunk. The client counts its own copy down once
// you come near which is why each spawner is read as its chunk arrives and never again.
public final class SpawnerFinder extends Module {

    // A flagged spawner and the words the chat line uses for it.
    private record Find(BlockPos pos, String what, boolean trial) {
    }

    // The finds of one dimension and every spawner already read there. Reading one again
    // after a visit of your own would count that visit.
    private record Store(Map<BlockPos, Find> finds, Set<BlockPos> read) {
    }

    // Spawners placed by code hold this delay until a player comes near.
    private static final int BUILT_DELAY = 20;
    // The bastion and woodland mansion templates save their spawners at zero. A spawner that
    // counted down but found nowhere to spawn waits at zero as well.
    private static final int TEMPLATE_DELAY = 0;
    // Trial spawners leave waiting for players only once players arrive.
    private static final Set<TrialSpawnerState> FOUGHT = EnumSet.of(TrialSpawnerState.ACTIVE,
        TrialSpawnerState.WAITING_FOR_REWARD_EJECTION, TrialSpawnerState.EJECTING_REWARD,
        TrialSpawnerState.COOLDOWN);
    // Far more spawners than a session meets. It only bounds the memory.
    private static final int MAX_READ = 65536;
    // How near a player a spawner starts its count by default. One this close to you as its
    // chunk arrives may be counting for you.
    private static final double OWN_RANGE = 16;

    private final BoolSetting trialSpawners = new BoolSetting("Trial spawners",
        "Also flags trial spawners that players have fought or turned ominous.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 300);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draws a line to every spawner found.", false);
    private final BoolSetting chat = new BoolSetting("Chat",
        "Posts the coordinates of each new find in chat.", true);

    // Whilst on the finds stay for every dimension of the current server.
    private final Map<ResourceKey<Level>, Store> stores = new HashMap<>();
    private String server;

    public SpawnerFinder() {
        super("SpawnerFinder", "Finds spawners players have been near in chunks that load whilst this is on.",
            Category.HUNTING);
        addSettings(trialSpawners);
        addSettings(style.settings());
        addSettings(tracers, chat);
        searchTags("spawner", "activated spawner", "dungeon", "trial spawner", "stash");
    }

    @Override
    public String getSuffix() {
        Store store = current();
        if (store == null) {
            return null;
        }
        int shown = 0;
        for (Find find : store.finds().values()) {
            shown += shows(find) ? 1 : 0;
        }
        return count(shown);
    }

    @Override
    protected void onDisable() {
        stores.clear();
        server = null;
    }

    // Null until a chunk of this dimension has arrived on this server.
    private Store current() {
        if (mc.level == null || !ServerInfo.key().equals(server)) {
            return null;
        }
        return stores.get(mc.level.dimension());
    }

    private Store storeFor(ResourceKey<Level> dimension) {
        String here = ServerInfo.key();
        if (!here.equals(server)) {
            // Two servers share their dimension names.
            stores.clear();
            server = here;
        }
        return stores.computeIfAbsent(dimension, key -> new Store(new LinkedHashMap<>(),
            Collections.newSetFromMap(new BoundedMap<>(MAX_READ))));
    }

    @Subscribe
    private void onChunkData(ChunkDataEvent event) {
        LevelChunk chunk = event.getChunk();
        Store store = storeFor(chunk.getLevel().dimension());
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (!(blockEntity instanceof SpawnerBlockEntity) && !(blockEntity instanceof TrialSpawnerBlockEntity)) {
                continue;
            }
            BlockPos pos = blockEntity.getBlockPos().immutable();
            if (!store.read().add(pos) || nearYou(pos)) {
                continue;
            }
            Find find = giveaway(blockEntity, chunk, pos);
            if (find != null) {
                store.finds().put(pos, find);
                report(find);
            }
        }
    }

    // A chunk seldom arrives this close. A join or a teleport can do it.
    private boolean nearYou(BlockPos pos) {
        return mc.player != null && mc.player.distanceToSqr(Vec3.atCenterOf(pos)) <= OWN_RANGE * OWN_RANGE;
    }

    // Null for a spawner that looks untouched.
    private static Find giveaway(BlockEntity blockEntity, LevelChunk chunk, BlockPos pos) {
        if (blockEntity instanceof SpawnerBlockEntity spawner) {
            BaseSpawner base = spawner.getSpawner();
            EntityType<?> type = spawnedType(base);
            if (!approached(base.spawnDelay, type, chunk, pos)) {
                return null;
            }
            String mob = mobName(type);
            return new Find(pos, article(mob) + mob + " spawner someone has been near", false);
        }
        BlockState state = chunk.getBlockState(pos);
        if (!state.hasProperty(TrialSpawnerBlock.STATE)) {
            return null;
        }
        if (state.getValue(TrialSpawnerBlock.OMINOUS)) {
            return new Find(pos, "a trial spawner a player turned ominous", true);
        }
        if (FOUGHT.contains(state.getValue(TrialSpawnerBlock.STATE))) {
            return new Find(pos, "a trial spawner players have fought", true);
        }
        return null;
    }

    private static boolean approached(int delay, EntityType<?> type, LevelChunk chunk, BlockPos pos) {
        if (delay == BUILT_DELAY) {
            return false;
        }
        if (delay != TEMPLATE_DELAY) {
            return true;
        }
        // Only the bastion treasure room holds a magma cube spawner.
        if (type == EntityTypes.MAGMA_CUBE) {
            return false;
        }
        // The mansion spider spawner stands on planks. A dungeon floor is cobblestone.
        return type != EntityTypes.SPIDER || !chunk.getBlockState(pos.below()).is(BlockTags.PLANKS);
    }

    private static EntityType<?> spawnedType(BaseSpawner spawner) {
        SpawnData data = spawner.nextSpawnData;
        if (data == null) {
            return null;
        }
        Identifier id = Identifier.tryParse(data.entityToSpawn().getStringOr("id", ""));
        return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    // The name the game shows for the mob. A spawner with no mob set reads as mob.
    private static String mobName(EntityType<?> type) {
        String name = type == null ? "" : type.getDescription().getString();
        return name.isEmpty() ? "mob" : name;
    }

    private static String article(String word) {
        return "AEIOU".indexOf(Character.toUpperCase(word.charAt(0))) >= 0 ? "an " : "a ";
    }

    private boolean shows(Find find) {
        return !find.trial() || trialSpawners.isOn();
    }

    private void report(Find find) {
        if (!chat.isOn() || !shows(find)) {
            return;
        }
        BlockPos pos = find.pos();
        ChatUtil.message("§bSpawnerFinder §7found " + find.what() + " at §f" + pos.getX() + " "
            + pos.getY() + " " + pos.getZ() + "§7.");
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        Store store = current();
        if (store == null || mc.player == null) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (Find find : store.finds().values()) {
            if (!shows(find)) {
                continue;
            }
            style.draw(batch, find.pos(), true);
            if (tracers.isOn()) {
                batch.tracer(Vec3.atCenterOf(find.pos()), style.lineColor(), true);
            }
        }
    }
}
