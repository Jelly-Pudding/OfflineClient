package com.jellypudding.offlineclient.rig.core;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

// A single player world. Its server runs inside the game and a case may ask it anything.
public final class LocalWorld extends World {

    private static final int CHUNK_BITS = 4;
    // The land counts as loaded once every chunk this far round the player is with the client.
    private static final int LOADED_CHUNKS = 7;
    private static final int LOAD_TICKS = 1200;
    private static final double LOADED_NEAR = 3;
    private static final int ARRIVE_TICKS = 60;
    private static final int NORMAL_PACE = 20;
    // Frames a second the game may draw. A tick needs a frame of its own.
    private static final int NORMAL_FRAMES = 60;
    private static final int SPARE_FRAMES = 20;

    private final TestSingleplayerContext single;
    private int toldAt;

    LocalWorld(Rig rig, TestSingleplayerContext single) {
        super(rig, rig.ask(mc -> mc.getUser().getName()));
        this.single = single;
    }

    void settle() {
        single.getConnection().waitForChunksDownload();
        command("time set noon");
        rig.ticks(SYNC_TICKS);
    }

    @Override
    protected void close() {
        single.close();
    }

    public <T> T server(Function<MinecraftServer, T> question) {
        return single.getServer().computeOnServer(question::apply);
    }

    @Override
    protected void told() {
        super.told();
        toldAt = server(MinecraftServer::getTickCount);
    }

    // The server sends what it changed with its next tick.
    @Override
    protected void catchUp() {
        if (server(MinecraftServer::getTickCount) == toldAt) {
            rig.ticks(1);
        }
        single.getConnection().waitForClientboundPackets();
    }

    @Override
    protected int perform(String command, List<String> said) {
        int[] result = {-1};
        single.getServer().runOnServer(server -> {
            CommandSourceStack source = server.createCommandSourceStack().withSource(listener(said))
                .withCallback((success, value) -> {
                    if (success) {
                        result[0] = Math.max(result[0], Math.max(value, 0));
                    }
                });
            server.getCommands().performPrefixedCommand(source, command);
        });
        return result[0];
    }

    private static CommandSource listener(List<String> said) {
        return new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                said.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
    }

    // Makes the game tick this many times as fast as its own pace. The game and its server tick
    // in turns and the slower of the two sets the pace. Every tick still needs a frame of its own
    // and the frame limit is raised to match.
    public void hurry(float times) {
        RigClock.set(times);
        single.getServer().runOnServer(server -> server.tickRateManager().setTickRate(NORMAL_PACE * times));
        rig.act(mc -> mc.options.framerateLimit().set(
            Math.max(NORMAL_FRAMES, Math.round(NORMAL_PACE * times) + SPARE_FRAMES)));
        rig.ticks(SYNC_TICKS);
    }

    // Puts the player on the ground at a place on the map and waits for the land round it to load.
    // Treetops do not count as ground. Null when the place lies under water.
    public Vec3 ground(int x, int z) {
        BlockPos top = server(server -> {
            ServerLevel level = server.overworld();
            level.getChunk(x >> CHUNK_BITS, z >> CHUNK_BITS);
            BlockPos floor = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            return level.getFluidState(floor.below()).isEmpty() && level.getFluidState(floor).isEmpty()
                ? floor : null;
        });
        if (top == null) {
            return null;
        }
        Vec3 spot = Vec3.atBottomCenterOf(top);
        command(String.format(Locale.ROOT, "tp %s %.3f %.3f %.3f 0 0", player(), spot.x, spot.y, spot.z));
        rig.until("the player to stand at " + spot, ARRIVE_TICKS,
            mc -> mc.player != null && mc.player.position().distanceTo(spot) < LOADED_NEAR);
        String missing = "";
        for (int waited = 0; waited <= LOAD_TICKS; waited += SYNC_TICKS) {
            missing = rig.ask(mc -> {
                int absent = 0;
                String first = "";
                for (int dx = -LOADED_CHUNKS; dx <= LOADED_CHUNKS; dx++) {
                    for (int dz = -LOADED_CHUNKS; dz <= LOADED_CHUNKS; dz++) {
                        int chunkX = (x >> CHUNK_BITS) + dx;
                        int chunkZ = (z >> CHUNK_BITS) + dz;
                        // The server sends a circle of chunks and never the corners of the square.
                        if (dx * dx + dz * dz <= LOADED_CHUNKS * LOADED_CHUNKS
                            && !mc.level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                            absent++;
                            first = chunkX + " " + chunkZ;
                        }
                    }
                }
                return absent == 0 ? "" : absent + " chunks are not with the client such as " + first
                    + " with a sight of " + mc.options.renderDistance().get();
            });
            if (missing.isEmpty()) {
                break;
            }
            rig.ticks(SYNC_TICKS);
        }
        if (!missing.isEmpty()) {
            throw new RigFailure("The land round " + x + " " + z + " did not load. " + missing);
        }
        rig.ticks(SYNC_TICKS);
        return rig.ask(mc -> mc.player.position());
    }

    public ServerPlayer serverPlayer() {
        return server(server -> {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            return players.isEmpty() ? null : players.getFirst();
        });
    }

    @Override
    public Map<String, String> facts() {
        return server(server -> {
            ServerPlayer held = server.getPlayerList().getPlayers().getFirst();
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("x", Double.toString(held.getX()));
            facts.put("y", Double.toString(held.getY()));
            facts.put("z", Double.toString(held.getZ()));
            facts.put("health", Float.toString(held.getHealth()));
            facts.put("food", Integer.toString(held.getFoodData().getFoodLevel()));
            facts.put("fall", Double.toString(held.fallDistance));
            facts.put("ground", Boolean.toString(held.onGround()));
            facts.put("sneaking", Boolean.toString(held.isShiftKeyDown()));
            facts.put("sprinting", Boolean.toString(held.isSprinting()));
            facts.put("flying", Boolean.toString(held.getAbilities().flying));
            facts.put("gliding", Boolean.toString(held.isFallFlying()));
            facts.put("swimming", Boolean.toString(held.isSwimming()));
            facts.put("yaw", Float.toString(held.getYRot()));
            facts.put("pitch", Float.toString(held.getXRot()));
            facts.put("slot", Integer.toString(held.getInventory().getSelectedSlot()));
            return facts;
        });
    }

    @Override
    public void foodLevel(int points) {
        told();
        single.getServer().runOnServer(server -> {
            FoodData food = server.getPlayerList().getPlayers().getFirst().getFoodData();
            food.setFoodLevel(points);
            food.setSaturation(0);
        });
    }

    @Override
    public String blockAt(BlockPos pos) {
        return server(server -> BuiltInRegistries.BLOCK.getKey(server.overworld().getBlockState(pos).getBlock())
            .toString());
    }

    public List<LivingEntity> living(Predicate<LivingEntity> wanted) {
        return server(server -> {
            List<LivingEntity> found = new ArrayList<>();
            for (Entity entity : server.overworld().getAllEntities()) {
                if (entity instanceof LivingEntity living && wanted.test(living)) {
                    found.add(living);
                }
            }
            return found;
        });
    }
}
