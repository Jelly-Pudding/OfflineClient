package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.config.ConfigManager;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.List;

import java.util.Locale;
import com.jellypudding.offlineclient.command.CommandManager;

public final class ProfileCommand extends Command {

    public ProfileCommand() {
        super("profile", "Saves or loads named config profiles.",
            "profile <save|load|list> [name]", "p");
    }

    @Override
    public void execute(String[] args) {
        ConfigManager config = OfflineClient.INSTANCE.getConfigManager();

        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            List<String> profiles = config.listProfiles();
            if (profiles.isEmpty()) {
                ChatUtil.message("§7You have no saved profiles.");
            } else {
                ChatUtil.message("§3Profiles: §b" + String.join("§7 §b", profiles));
            }
            return;
        }

        if (args.length != 2) {
            usage();
            return;
        }

        String name = args[1];
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "save" -> {
                config.saveProfile(name);
                ChatUtil.message("§aSaved profile §b" + name + "§a.");
            }
            case "load" -> {
                if (config.loadProfile(name)) {
                    ChatUtil.message("§aLoaded profile §b" + name + "§a.");
                } else {
                    ChatUtil.error("No profile named " + name + ".");
                }
            }
            default -> usage();
        }
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, List.of("save", "load", "list"));
        }
        if (index == 2 && tokens[1].equalsIgnoreCase("load")) {
            return CommandManager.filter(current,
                OfflineClient.INSTANCE.getConfigManager().listProfiles());
        }
        return List.of();
    }
}
