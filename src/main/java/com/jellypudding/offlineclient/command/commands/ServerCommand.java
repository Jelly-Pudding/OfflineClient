package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.network.chat.Component;

public final class ServerCommand extends Command {

    public ServerCommand() {
        super("server", "Shows the address and details of the server you are on.", "server", "ip");
    }

    @Override
    public void execute(String[] args) {
        String address = ServerInfo.address();
        if (address == null) {
            ChatUtil.error("You are not on a server.");
            return;
        }
        ChatUtil.row("Address", address);
        Component motd = ServerInfo.motd();
        if (motd != null) {
            ChatUtil.row("MOTD", motd);
        }
        String brand = ServerInfo.brand();
        if (brand != null) {
            ChatUtil.row("Software", brand);
        }
        ChatUtil.row("Players", ServerInfo.online() + " online");
        ChatUtil.row("Ping", ServerInfo.ping() + " ms");
        ChatUtil.row("TPS", ServerInfo.tps());
        String saved = ServerInfo.savedName();
        if (saved != null) {
            ChatUtil.row("Saved as", saved);
        }
    }
}
