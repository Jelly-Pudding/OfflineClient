package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.friend.FriendManager;
import com.jellypudding.offlineclient.util.ChatUtil;

public final class FriendCommand extends Command {

    public FriendCommand() {
        super("friend", "Manages your friends list (never targeted by combat modules).",
            "friend <add|remove|list> [name]", "f");
    }

    @Override
    public void execute(String[] args) {
        FriendManager friends = OfflineClient.INSTANCE.getFriendManager();

        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            if (friends.getAll().isEmpty()) {
                ChatUtil.message("§7Your friends list is empty.");
            } else {
                ChatUtil.message("§3Friends: §b" + String.join("§7, §b", friends.getAll()));
            }
            return;
        }

        if (args.length != 2) {
            ChatUtil.error("Usage: " + getUsage());
            return;
        }

        String name = args[1];
        switch (args[0].toLowerCase()) {
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
            default -> ChatUtil.error("Usage: " + getUsage());
        }
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
    }
}
