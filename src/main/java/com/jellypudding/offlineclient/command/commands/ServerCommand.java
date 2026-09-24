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
        row("Address", address);
        Component motd = ServerInfo.motd();
        if (motd != null) {
            ChatUtil.component(Component.literal("§7MOTD §r").append(motd));
        }
        String brand = ServerInfo.brand();
        if (brand != null) {
            row("Software", brand);
        }
        row("Players", ServerInfo.online() + " online");
        row("Ping", ServerInfo.ping() + " ms");
        row("TPS", ServerInfo.tps());
        String saved = ServerInfo.savedName();
        if (saved != null) {
            row("Saved as", saved);
        }
    }

    private static void row(String label, String value) {
        ChatUtil.message("§7" + label + " §b" + value);
    }
}
