package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.List;

// The vanilla slider stops at a hundred and ten. This does not.
public final class FovCommand extends Command {

    private static final int DEFAULT_FOV = 70;

    public FovCommand() {
        super("fov", "Sets the field of view past the vanilla slider.", "fov <value|reset>");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            ChatUtil.message("§7Field of view is §b" + OfflineClient.MC.options.fov().get());
            return;
        }
        if (args.length != 1) {
            usage();
            return;
        }
        if (args[0].equalsIgnoreCase("reset")) {
            OfflineClient.MC.options.fov().set(DEFAULT_FOV);
            ChatUtil.message("§7Field of view back to §b" + DEFAULT_FOV);
            return;
        }
        int value;
        try {
            value = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            ChatUtil.error("That is not a whole number.");
            return;
        }
        OfflineClient.MC.options.fov().set(value);
        ChatUtil.message("§7Field of view is now §b" + value);
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 1 ? CommandManager.filter(current, List.of("reset")) : List.of();
    }
}
