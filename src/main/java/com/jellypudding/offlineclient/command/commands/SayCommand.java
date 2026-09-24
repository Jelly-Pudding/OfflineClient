package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;

// Sends text the client would otherwise read as one of our own commands.
public final class SayCommand extends Command {

    public SayCommand() {
        super("say", "Sends chat straight to the server.", "say <message>");
    }

    @Override
    public void execute(String[] args) {
        if (OfflineClient.MC.player == null || args.length == 0) {
            usage();
            return;
        }
        ChatUtil.say(String.join(" ", args));
    }
}
