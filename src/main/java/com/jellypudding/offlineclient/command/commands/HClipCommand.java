package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.OptionalDouble;

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
        OptionalDouble blocks = number(args[0]);
        if (blocks.isPresent()) {
            Vec3 look = Vec3.directionFromRotation(0, player.getYRot()).scale(blocks.getAsDouble());
            hopTo(player.position().add(look.x, 0, look.z), "§7Moved §b" + args[0] + " §7blocks.");
        }
    }
}
