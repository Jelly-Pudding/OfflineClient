package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

// For testing movement. A tilde keeps the speed you already have on that axis.
public final class VelocityCommand extends Command {

    public VelocityCommand() {
        super("velocity", "Shows your speed or sets it in blocks per tick.", "velocity [x y z]", "vel");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (args.length == 0) {
            ChatUtil.message("§7Your velocity is §b" + text(player.getDeltaMovement()));
            return;
        }
        if (args.length != 3) {
            usage();
            return;
        }
        Vec3 velocity = coordinates(args, 0, player.getDeltaMovement());
        if (velocity == null) {
            return;
        }
        player.setDeltaMovement(velocity);
        ChatUtil.message("§7Velocity set to §b" + text(velocity));
    }

    private static String text(Vec3 velocity) {
        return String.format(Locale.ROOT, "%.3f %.3f %.3f", velocity.x, velocity.y, velocity.z);
    }
}
