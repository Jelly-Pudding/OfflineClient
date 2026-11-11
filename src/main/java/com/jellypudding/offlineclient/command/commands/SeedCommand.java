package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.DataFiles;
import com.jellypudding.offlineclient.config.ServerStore;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.LoadedChunks;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

// Keeps the world seed of each server. OreSight and locate and the slime chunk marks work from
// it. A server that keeps its seed secret still shows its bedrock and a seed cracker can work
// the seed out from that.
public final class SeedCommand extends Command {

    private static final String BEDROCK = "bedrock";
    private static final String FLOOR = "floor";
    private static final String ROOF = "roof";
    private static final String BEDROCK_USAGE = "seed bedrock [floor|roof]";

    // The Nether lays bedrock at random from height 1 to 4 and from 123 to 126 and solid past
    // them. One block in five is bedrock at these two heights. They say the most about the seed
    // and are the ones the seed crackers ask for.
    private static final int FLOOR_HEIGHT = 4;
    private static final int ROOF_HEIGHT = 123;

    public SeedCommand() {
        super("seed",
            "Saves the world seed of the server you are on or writes the Nether bedrock a seed cracker finds it from.",
            "seed <set|remove|list|bedrock> [seed|server|floor|roof]");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "set" -> set(args);
            case "remove" -> remove(args);
            case "list" -> list();
            case BEDROCK -> bedrock(args);
            default -> usage();
        }
    }

    // Text that is not a number becomes a seed the way the server reads its seed setting.
    private void set(String[] args) {
        if (args.length < 2) {
            usage("seed set <seed>");
            return;
        }
        if (ServerInfo.address() == null) {
            ChatUtil.error("A single player world already knows its seed.");
            return;
        }
        OptionalLong seed = WorldOptions.parseSeed(words(args, 1));
        if (seed.isEmpty()) {
            usage("seed set <seed>");
            return;
        }
        ServerStore.seeds().set(ServerInfo.key(), seed.getAsLong());
        ChatUtil.message("§7Saved seed §b" + seed.getAsLong() + "§7 for §b" + ServerInfo.address() + "§7.");
    }

    private void remove(String[] args) {
        if (args.length > 2) {
            usage("seed remove [server]");
            return;
        }
        String server = args.length == 2 ? args[1].toLowerCase(Locale.ROOT)
            : ServerInfo.address() == null ? null : ServerInfo.key();
        if (server == null) {
            usage("seed remove <server>");
            return;
        }
        forget(ServerStore.seeds(), server, "seed");
    }

    private static void list() {
        List<String> servers = ServerStore.seeds().servers();
        if (servers.isEmpty()) {
            ChatUtil.message("§7No seeds are saved.");
            return;
        }
        for (String server : servers) {
            ChatUtil.message("§b" + server + " §7" + ServerStore.seeds().get(server));
        }
    }

    // Writes every bedrock block of one height in the chunks around you as a line of x y z and
    // the word Bedrock. That is the form the seed crackers take.
    private void bedrock(String[] args) {
        String side = args.length == 1 ? FLOOR : args[1].toLowerCase(Locale.ROOT);
        if (args.length > 2 || !side.equals(FLOOR) && !side.equals(ROOF)) {
            usage(BEDROCK_USAGE);
            return;
        }
        ClientLevel level = OfflineClient.MC.level;
        if (level == null || level.dimension() != Level.NETHER) {
            ChatUtil.error("The bedrock is read in the Nether. Go there and send this again.");
            return;
        }
        int height = side.equals(ROOF) ? ROOF_HEIGHT : FLOOR_HEIGHT;
        List<String> lines = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LoadedChunks.forEach(chunk -> {
            ChunkPos corner = chunk.getPos();
            for (int x = corner.getMinBlockX(); x <= corner.getMaxBlockX(); x++) {
                for (int z = corner.getMinBlockZ(); z <= corner.getMaxBlockZ(); z++) {
                    if (chunk.getBlockState(pos.set(x, height, z)).is(Blocks.BEDROCK)) {
                        lines.add(x + " " + height + " " + z + " Bedrock");
                    }
                }
            }
        });
        if (lines.isEmpty()) {
            ChatUtil.error("No bedrock lies at height " + height + " in the chunks around you. This server may lay"
                + " its bedrock flat.");
            return;
        }
        Path file = DataFiles.path(BEDROCK, DataFiles.keyName(ServerInfo.key()) + " " + side + ".txt");
        if (!DataFiles.writeSafely(file, temp -> Files.write(temp, lines))) {
            ChatUtil.error("The bedrock could not be saved. The game log says why.");
            return;
        }
        String prefix = OfflineClient.INSTANCE.getCommandManager().getPrefix();
        ChatUtil.component(Component.literal("§7Wrote §f" + lines.size() + "§7 bedrock blocks of height §f" + height
            + "§7 to ").append(ChatUtil.fileLink(file)).append("§7. Give the file to a Nether bedrock seed cracker"
            + " and save the seed it finds with §f" + prefix + "seed set <seed>§7."));
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, List.of("set", "remove", "list", BEDROCK));
        }
        if (index == 2 && tokens[1].equalsIgnoreCase("remove")) {
            return CommandManager.filter(current, ServerStore.seeds().servers());
        }
        if (index == 2 && tokens[1].equalsIgnoreCase(BEDROCK)) {
            return CommandManager.filter(current, List.of(FLOOR, ROOF));
        }
        return List.of();
    }
}
