package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

public final class RotationCommand extends Command {

    public RotationCommand() {
        super("rotation", "Prints where you look or points you somewhere.",
            "rotation [yaw] [pitch]", "rot");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (args.length == 0) {
            ChatUtil.message("§7Yaw §b" + round(player.getYRot())
                + " §7pitch §b" + round(player.getXRot()));
            return;
        }
        if (args.length != 2) {
            usage();
            return;
        }
        float yaw;
        float pitch;
        try {
            yaw = Float.parseFloat(args[0]);
            pitch = Float.parseFloat(args[1]);
        } catch (NumberFormatException e) {
            ChatUtil.error("Those are not numbers.");
            return;
        }
        player.setYRot(Mth.wrapDegrees(yaw));
        player.setXRot(Mth.clamp(pitch, -90f, 90f));
        ChatUtil.message("§7Looking at yaw §b" + round(player.getYRot())
            + " §7pitch §b" + round(player.getXRot()));
    }

    private static String round(float value) {
        return String.format("%.1f", value);
    }
}
