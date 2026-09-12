package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.LocalPlayer;

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
        double blocks;
        try {
            blocks = Double.parseDouble(args[0]);
        } catch (NumberFormatException e) {
            ChatUtil.error("That is not a number.");
            return;
        }
        player.setPos(player.getX(), player.getY() + blocks, player.getZ());
        ChatUtil.message("§7Moved §b" + blocks + " §7blocks.");
    }
}
