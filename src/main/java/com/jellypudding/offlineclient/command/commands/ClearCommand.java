package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;

public final class ClearCommand extends Command {

    public ClearCommand() {
        super("clear", "Wipes the chat history.", "clear");
    }

    @Override
    public void execute(String[] args) {
        OfflineClient.MC.gui.hud.getChat().clearMessages(true);
    }
}
