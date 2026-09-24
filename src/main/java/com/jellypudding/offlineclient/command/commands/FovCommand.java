package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.List;
import java.util.OptionalInt;

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
        OptionalInt value = wholeNumber(args[0]);
        if (value.isEmpty()) {
            return;
        }
        OfflineClient.MC.options.fov().set(value.getAsInt());
        ChatUtil.message("§7Field of view is now §b" + value.getAsInt());
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 1 ? CommandManager.filter(current, List.of("reset")) : List.of();
    }
}
