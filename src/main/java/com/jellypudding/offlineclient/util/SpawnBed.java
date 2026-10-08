package com.jellypudding.offlineclient.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.DataFiles;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

// The bed you last slept in or set your spawn at in each dimension of each server. It is
// kept in offlineclient/spawn_beds.json for the next visit.
public enum SpawnBed {
    INSTANCE;

    // What the server says whenever it moves your spawn to a bed or an anchor.
    private static final String SPAWN_SET = "block.minecraft.set_spawn";

    // By server and dimension. Read on the game thread only.
    private static final Map<String, BlockPos> BEDS = new HashMap<>();
    private static boolean loaded;

    // The bed or anchor you clicked last. The server names no spot when it sets your spawn.
    private static BlockPos clicked;
    // Set on the network thread when the server says your spawn moved.
    private static volatile boolean spawnSet;

    // True for either half of the bed that holds your spawn in this dimension.
    public static boolean isSpawnBed(BlockPos pos) {
        BlockPos bed = beds().get(ServerInfo.worldKey());
        if (bed == null || OfflineClient.MC.level == null) {
            return false;
        }
        if (bed.equals(pos)) {
            return true;
        }
        BlockState state = OfflineClient.MC.level.getBlockState(bed);
        return state.getBlock() instanceof AbstractBedBlock
            && bed.relative(AbstractBedBlock.getConnectedDirection(state)).equals(pos);
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!(event.getPacket() instanceof ServerboundUseItemOnPacket packet) || OfflineClient.MC.level == null) {
            return;
        }
        BlockPos pos = packet.hitResult().getBlockPos();
        BlockState state = OfflineClient.MC.level.getBlockState(pos);
        if (state.getBlock() instanceof AbstractBedBlock || state.is(Blocks.RESPAWN_ANCHOR)) {
            clicked = pos.immutable();
        }
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundSystemChatPacket packet && !packet.overlay()
            && packet.content().getContents() instanceof TranslatableContents text && SPAWN_SET.equals(text.getKey())) {
            spawnSet = true;
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (spawnSet) {
            spawnSet = false;
            if (clicked != null) {
                remember(clicked);
            }
        }
        player.getSleepingPos().ifPresent(SpawnBed::remember);
    }

    // An anchor takes the spawn away from every bed here.
    private static void remember(BlockPos pos) {
        String key = ServerInfo.worldKey();
        if (key.isEmpty() || pos.equals(beds().get(key))) {
            return;
        }
        BEDS.put(key, pos.immutable());
        save();
    }

    private static Map<String, BlockPos> beds() {
        if (!loaded) {
            loaded = true;
            DataFiles.readJson(file(), SpawnBed::decode).ifPresent(BEDS::putAll);
        }
        return BEDS;
    }

    private static Path file() {
        return DataFiles.path("spawn_beds.json");
    }

    private static Map<String, BlockPos> decode(JsonElement root) {
        Map<String, BlockPos> decoded = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
            BlockPos pos = BlockUtil.parse(entry.getValue().getAsString());
            if (pos != null) {
                decoded.put(entry.getKey(), pos);
            }
        }
        return decoded;
    }

    private static void save() {
        JsonObject root = new JsonObject();
        BEDS.forEach((key, pos) -> root.addProperty(key, BlockUtil.text(pos)));
        DataFiles.writeJson(file(), root);
    }
}
