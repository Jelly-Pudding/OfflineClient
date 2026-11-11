package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InputHolds;
import net.minecraft.client.player.LocalPlayer;

// The server only lets a rider off when it hears that sneak is held. A short hold of the sneak key
// sends the press and the release the way the key itself does.
public final class DismountCommand extends Command {

    // The server reads the sneak flag once a tick and a shorter press can slip between two reads.
    private static final int SNEAK_TICKS = 2;

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
        InputHolds.hold(InputHolds.Key.SNEAK, SNEAK_TICKS);
        ChatUtil.message("§7Getting off.");
    }
}
