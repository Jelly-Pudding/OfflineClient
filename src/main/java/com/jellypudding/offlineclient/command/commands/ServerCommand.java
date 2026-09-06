package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.multiplayer.ServerData;

public final class ServerCommand extends Command {

    public ServerCommand() {
        super("server", "Prints the address of the server you are on.", "server", "ip");
    }

    @Override
    public void execute(String[] args) {
        ServerData data = OfflineClient.MC.getCurrentServer();
        if (data == null) {
            ChatUtil.error("You are not on a server.");
            return;
        }
        ChatUtil.message("§7Address §b" + data.ip);
        if (data.name != null && !data.name.isBlank()) {
            ChatUtil.message("§7Name §b" + data.name);
        }
    }
}
