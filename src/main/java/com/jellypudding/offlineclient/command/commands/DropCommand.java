package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

public final class DropCommand extends Command {

    public DropCommand() {
        super("drop", "Throws away what you hold or the whole stack.", "drop [all]");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        boolean whole = args.length == 1 && args[0].equalsIgnoreCase("all");
        if (args.length > 1 || (args.length == 1 && !whole)) {
            usage();
            return;
        }
        if (player.getMainHandItem().isEmpty()) {
            ChatUtil.error("Your hand is empty.");
            return;
        }
        player.drop(whole);
        ChatUtil.message(whole ? "§7Dropped the stack." : "§7Dropped one.");
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 1 ? CommandManager.filter(current, List.of("all")) : List.of();
    }
}
