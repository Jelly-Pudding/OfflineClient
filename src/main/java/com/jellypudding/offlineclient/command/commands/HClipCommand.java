package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

public final class HClipCommand extends Command {

    public HClipCommand() {
        super("hclip", "Moves you the way you look without touching your height.",
            "hclip <blocks>", "h");
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
        Vec3 look = Vec3.directionFromRotation(0, player.getYRot()).scale(blocks);
        player.setPos(player.getX() + look.x, player.getY(), player.getZ() + look.z);
        ChatUtil.message("§7Moved §b" + blocks + " §7blocks.");
    }
}
