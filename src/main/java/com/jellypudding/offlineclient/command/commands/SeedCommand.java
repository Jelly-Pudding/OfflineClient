package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.ServerStore;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.world.level.levelgen.WorldOptions;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

// Keeps the world seed of each server. OreSight works out where the ores are from it.
public final class SeedCommand extends Command {

    public SeedCommand() {
        super("seed", "Saves the world seed of the server you are on. OreSight works out the ores from it.",
            "seed <set|remove|list> [seed|server]");
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
        OptionalLong seed = WorldOptions.parseSeed(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
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
        if (ServerStore.seeds().remove(server)) {
            ChatUtil.message("§7Forgot the seed of §b" + server + "§7.");
        } else {
            ChatUtil.error("No seed is saved for " + server + ".");
        }
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

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, List.of("set", "remove", "list"));
        }
        if (index == 2 && tokens[1].equalsIgnoreCase("remove")) {
            return CommandManager.filter(current, ServerStore.seeds().servers());
        }
        return List.of();
    }
}
