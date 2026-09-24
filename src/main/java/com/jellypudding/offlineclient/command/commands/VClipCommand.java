package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import net.minecraft.client.player.LocalPlayer;

import java.util.OptionalDouble;

public final class VClipCommand extends Command {

    public VClipCommand() {
        super("vclip", "Moves you straight up or down through blocks.", "vclip <blocks>", "v");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || args.length != 1) {
            usage();
            return;
        }
        OptionalDouble blocks = number(args[0]);
        if (blocks.isPresent()) {
            hopTo(player.position().add(0, blocks.getAsDouble(), 0), "§7Moved §b" + args[0] + " §7blocks.");
        }
    }
}
