package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.mixinterface.ISimpleOption;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.List;
import java.util.OptionalInt;

// The vanilla slider stops at a hundred and ten. This does not.
public final class FovCommand extends Command {

    private static final int DEFAULT_FOV = 70;
    // A view as wide as a straight line or wider cannot be drawn.
    private static final int MIN_FOV = 1;
    private static final int MAX_FOV = 179;

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
            ISimpleOption.force(OfflineClient.MC.options.fov(), DEFAULT_FOV);
            ChatUtil.message("§7Field of view back to §b" + DEFAULT_FOV);
            return;
        }
        OptionalInt value = wholeNumber(args[0]);
        if (value.isEmpty()) {
            return;
        }
        if (value.getAsInt() < MIN_FOV || value.getAsInt() > MAX_FOV) {
            ChatUtil.error("Pick between " + MIN_FOV + " and " + MAX_FOV + ".");
            return;
        }
        ISimpleOption.force(OfflineClient.MC.options.fov(), value.getAsInt());
        ChatUtil.message("§7Field of view is now §b" + value.getAsInt());
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 1 ? CommandManager.filter(current, List.of("reset")) : List.of();
    }
}
