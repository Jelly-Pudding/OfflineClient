package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.friend.FriendManager;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.Locale;
import com.jellypudding.offlineclient.command.CommandManager;
import net.minecraft.client.player.AbstractClientPlayer;
import java.util.ArrayList;
import java.util.List;

public final class FriendCommand extends Command {

    public FriendCommand() {
        super("friend", "Manages your friends list. Friends are never targeted by combat modules.",
            "friend <add|remove|list> [name]", "f");
    }

    @Override
    public void execute(String[] args) {
        FriendManager friends = OfflineClient.INSTANCE.getFriendManager();

        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            if (friends.getAll().isEmpty()) {
                ChatUtil.message("§7Your friends list is empty.");
            } else {
                ChatUtil.message("§3Friends: §b" + String.join("§7 §b", friends.getAll()));
            }
            return;
        }

        if (args.length != 2) {
            usage();
            return;
        }

        String name = args[1];
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (OfflineClient.MC.player != null
                    && name.equalsIgnoreCase(OfflineClient.MC.player.getGameProfile().name())) {
                    ChatUtil.error("You cannot add yourself as a friend.");
                    return;
                }
                if (friends.add(name)) {
                    ChatUtil.message("§aAdded friend: §b" + name);
                } else {
                    ChatUtil.error(name + " is already a friend.");
                }
            }
            case "remove" -> {
                if (friends.remove(name)) {
                    ChatUtil.message("§cRemoved friend: §b" + name);
                } else {
                    ChatUtil.error(name + " is not on your friends list.");
                }
            }
            default -> usage();
        }
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, List.of("add", "remove", "list"));
        }
        if (index != 2) {
            return List.of();
        }
        FriendManager friends = OfflineClient.INSTANCE.getFriendManager();
        if (tokens[1].equalsIgnoreCase("remove")) {
            return CommandManager.filter(current, new ArrayList<>(friends.getAll()));
        }
        if (!tokens[1].equalsIgnoreCase("add") || OfflineClient.MC.level == null) {
            return List.of();
        }
        String self = OfflineClient.MC.player == null
            ? "" : OfflineClient.MC.player.getGameProfile().name();
        List<String> names = new ArrayList<>();
        for (AbstractClientPlayer player : OfflineClient.MC.level.players()) {
            String name = player.getGameProfile().name();
            if (!name.equalsIgnoreCase(self) && !friends.isFriend(name)) {
                names.add(name);
            }
        }
        return CommandManager.filter(current, names);
    }
}
