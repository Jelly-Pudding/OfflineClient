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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

// The single player world of a run seen from its server. A case builds its stage with server
// commands and reads here what really happened to the player.
public final class World {

    private static final int CHUNK_BITS = 4;
    // The land counts as loaded once every chunk this far round the player is with the client.
    private static final int LOADED_CHUNKS = 7;
    private static final int LOAD_TICKS = 1200;
    private static final double LOADED_NEAR = 3;

    // Ticks the client gets to hear what the server changed.
    private static final int SYNC_TICKS = 3;
    private static final int ARRIVE_TICKS = 60;
    private static final double ARRIVED = 0.3;
    private static final int NORMAL_PACE = 20;
    // Frames a second the game may draw. A tick needs a frame of its own.
    private static final int NORMAL_FRAMES = 60;
    private static final int SPARE_FRAMES = 20;

    private final Rig rig;
    private final TestSingleplayerContext single;

    World(Rig rig, TestSingleplayerContext single) {
        this.rig = rig;
        this.single = single;
    }

    void settle() {
        single.getConnection().waitForChunksDownload();
        command("time set noon");
        rig.ticks(SYNC_TICKS);
    }

    void close() {
        single.close();
    }

    public <T> T server(Function<MinecraftServer, T> question) {
        return single.getServer().computeOnServer(question::apply);
    }

    // Runs a server command with full rights and hands back its result number. A command the
    // server refuses fails the case with what the server said.
    public int command(String command) {
        List<String> said = new ArrayList<>();
        int result = perform(command, said);
        if (result < 0) {
            throw new RigFailure("The server refused the command " + command + " and said " + said);
        }
        return result;
    }

    // The same for a command that may find nothing to do. False when the server refused it.
    public boolean attempt(String command) {
        return perform(command, new ArrayList<>()) >= 0;
    }

    // Below nought when the command did not succeed.
    private int perform(String command, List<String> said) {
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

    // Makes the game tick this many times as fast as its own pace. Every tick still needs a frame
    // of its own and the frame limit is raised to match.
    public void hurry(float times) {
        RigClock.set(times);
        rig.act(mc -> mc.options.framerateLimit().set(
            Math.max(NORMAL_FRAMES, Math.round(NORMAL_PACE * times) + SPARE_FRAMES)));
        rig.ticks(SYNC_TICKS);
    }

    // Several commands and then the ticks the client needs to see what they did.
    public void commands(String... commands) {
        for (String command : commands) {
            command(command);
        }
        rig.ticks(SYNC_TICKS);
    }

    public void setBlock(BlockPos pos, String block) {
        command("setblock " + at(pos) + " " + block);
    }

    public void fill(BlockPos from, BlockPos to, String block) {
        command("fill " + at(from) + " " + at(to) + " " + block);
    }

    // The same for blocks that may be there already. The server calls a fill that changes nothing a failure.
    public void refill(BlockPos from, BlockPos to, String block) {
        attempt("fill " + at(from) + " " + at(to) + " " + block);
    }

    private static String at(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    // Puts the player there and waits until the client stands there too.
    public void teleport(double x, double y, double z, float yaw, float pitch) {
        command(String.format(Locale.ROOT, "tp @p %.3f %.3f %.3f %.2f %.2f", x, y, z, yaw, pitch));
        Vec3 spot = new Vec3(x, y, z);
        rig.until("the player to arrive at " + spot, ARRIVE_TICKS,
            mc -> mc.player != null && mc.player.position().distanceTo(spot) < ARRIVED);
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
        command(String.format(Locale.ROOT, "tp @p %.3f %.3f %.3f 0 0", spot.x, spot.y, spot.z));
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

    public void gameMode(String mode) {
        commands("gamemode " + mode + " @p");
    }

    public void clearInventory() {
        commands("clear @p");
    }

    // Puts an item in one slot of the hotbar counted from nought.
    public void hotbar(int slot, String item) {
        command("item replace entity @p hotbar." + slot + " with " + item);
    }

    public ServerPlayer serverPlayer() {
        return server(server -> {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            return players.isEmpty() ? null : players.getFirst();
        });
    }

    public float health() {
        return server(server -> server.getPlayerList().getPlayers().getFirst().getHealth());
    }

    // The id of the block the server holds there with its namespace in front.
    public String blockAt(BlockPos pos) {
        return server(server -> BuiltInRegistries.BLOCK.getKey(server.overworld().getBlockState(pos).getBlock())
            .toString());
    }

    public boolean isAir(BlockPos pos) {
        return server(server -> server.overworld().getBlockState(pos).isAir());
    }

    // Every living thing of the server's overworld the test accepts.
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
