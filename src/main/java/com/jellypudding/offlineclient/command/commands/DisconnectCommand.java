package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Leave;

import java.util.List;

public final class DisconnectCommand extends Command {

    private static final String NO_REASON = "Disconnected";

    public DisconnectCommand() {
        super("disconnect",
            "Leaves the server with a reason of your own. Chat and hurt and move get you kicked instead and you"
                + " leave by yourself if the server lets them pass.",
            "disconnect [quit|chat|hurt|move] [reason]", "leave");
    }

    @Override
    public void execute(String[] args) {
        if (OfflineClient.MC.getConnection() == null) {
            ChatUtil.error("You are not on a server.");
            return;
        }
        Leave way = args.length == 0 ? null : Leave.named(args[0]);
        String reason = words(args, way == null ? 0 : 1);
        (way == null ? Leave.QUIT : way).go(reason.isBlank() ? NO_REASON : reason);
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 1 ? CommandManager.filter(current, Leave.words()) : List.of();
    }
}
