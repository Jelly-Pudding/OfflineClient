package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import net.minecraft.client.player.LocalPlayer;

// Sends text the client would otherwise read as one of our own commands.
public final class SayCommand extends Command {

    public SayCommand() {
        super("say", "Sends chat straight to the server.", "say <message>");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || args.length == 0) {
            usage();
            return;
        }
        String text = String.join(" ", args);
        if (text.startsWith("/")) {
            player.connection.sendCommand(text.substring(1));
        } else {
            player.connection.sendChat(text);
        }
    }
}
