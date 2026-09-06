package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

public final class DisconnectCommand extends Command {

    public DisconnectCommand() {
        super("disconnect", "Leaves the server with a reason of your own.",
            "disconnect [reason]", "leave");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        String reason = args.length == 0 ? "Disconnected" : String.join(" ", args);
        player.connection.getConnection().disconnect(Component.literal(reason));
    }
}
