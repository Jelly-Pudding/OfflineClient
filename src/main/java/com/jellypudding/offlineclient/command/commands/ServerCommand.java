package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.util.AntiCheatGuess;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ClientLines;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.ServerPlugins;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class ServerCommand extends Command {

    public ServerCommand() {
        super("server",
            "Shows the address and details of the server you are on. Plugins lists the plugins it gives away.",
            "server [plugins]", "ip");
    }

    @Override
    public void execute(String[] args) {
        String address = ServerInfo.address();
        if (address == null) {
            ChatUtil.error("You are not on a server.");
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("plugins")) {
            plugins();
            return;
        }
        if (args.length != 0) {
            usage();
            return;
        }
        ChatUtil.row("Address", address);
        Component motd = ServerInfo.motd();
        if (motd != null) {
            ChatUtil.row("MOTD", motd);
        }
        String brand = ServerInfo.brand();
        if (brand != null) {
            ChatUtil.row("Software", brand);
        }
        ChatUtil.row("Login", offlineMode() ? "offline mode" : "online mode");
        ChatUtil.row("Players", ServerInfo.online() + " online");
        ChatUtil.row("Ping", ServerInfo.ping() + " ms");
        ChatUtil.row("TPS", ServerInfo.tps());
        ChatUtil.row("Anti cheat", AntiCheatGuess.INSTANCE.summary());
        String saved = ServerInfo.savedName();
        if (saved != null) {
            ChatUtil.row("Saved as", saved);
        }
    }

    // The names the version command offers come first. They carry the capitals a plugin gives itself.
    private static void plugins() {
        List<String> commands = ServerPlugins.roots();
        Set<String> fromCommands = ServerPlugins.fromCommands();
        ServerPlugins.ask().thenAccept(offered -> ClientLines.inChat(() -> {
            Set<String> plugins = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            plugins.addAll(offered);
            plugins.addAll(fromCommands);
            ChatUtil.row("Plugins", plugins.isEmpty() ? "none seen" : String.join("§7 §b", plugins));
            List<String> words = new ArrayList<>(plugins);
            words.addAll(commands);
            List<String> antiCheats = AntiCheatGuess.among(words);
            ChatUtil.row("Anti cheat", antiCheats.isEmpty() ? AntiCheatGuess.INSTANCE.summary()
                : "§c" + String.join("§7 and §c", antiCheats));
        }));
    }

    // An offline server works your id out from your name. An online one hands you your account id.
    private static boolean offlineMode() {
        LocalPlayer player = OfflineClient.MC.player;
        return player != null
            && UUIDUtil.createOfflinePlayerUUID(player.getGameProfile().name()).equals(player.getUUID());
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 1 ? CommandManager.filter(current, List.of("plugins")) : List.of();
    }
}
