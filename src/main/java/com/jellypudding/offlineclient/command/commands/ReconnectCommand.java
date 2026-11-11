package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.modules.misc.AutoReconnect;
import com.jellypudding.offlineclient.util.ChatUtil;

public final class ReconnectCommand extends Command {

    public ReconnectCommand() {
        super("reconnect", "Leaves the server you are on and joins it again.", "reconnect", "rejoin");
    }

    // The chat screen closes whatever screen is open once the command returns. The join starts
    // after that.
    @Override
    public void execute(String[] args) {
        if (AutoReconnect.canRejoin()) {
            OfflineClient.MC.schedule(AutoReconnect::rejoin);
        } else {
            ChatUtil.error("There is no server to join again.");
        }
    }
}
