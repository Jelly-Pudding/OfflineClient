package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.LocalPlayer;

public final class DismountCommand extends Command {

    public DismountCommand() {
        super("dismount", "Gets you off whatever you ride.", "dismount");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (!player.isPassenger()) {
            ChatUtil.error("You are not riding anything.");
            return;
        }
        player.stopRiding();
        ChatUtil.message("§7Off you get.");
    }
}
