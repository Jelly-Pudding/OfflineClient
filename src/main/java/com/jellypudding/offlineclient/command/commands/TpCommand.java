package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

// The server pulls you back unless the distance is small. Use it in short hops.
public final class TpCommand extends Command {

    public TpCommand() {
        super("tp", "Moves you to a spot or to another player.", "tp <x> <y> <z|player>");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (args.length == 1) {
            toPlayer(player, args[0]);
            return;
        }
        if (args.length != 3) {
            usage();
            return;
        }
        try {
            player.setPos(coordinate(args[0], player.getX()),
                coordinate(args[1], player.getY()),
                coordinate(args[2], player.getZ()));
        } catch (NumberFormatException e) {
            ChatUtil.error("Those are not numbers.");
            return;
        }
        ChatUtil.message("§7Moved you there.");
    }

    // A tilde means the spot you are already at on that axis.
    private static double coordinate(String text, double here) {
        if (text.startsWith("~")) {
            return text.length() == 1 ? here : here + Double.parseDouble(text.substring(1));
        }
        return Double.parseDouble(text);
    }

    private static void toPlayer(LocalPlayer self, String name) {
        for (Player other : OfflineClient.MC.level.players()) {
            if (other != self && other.getGameProfile().name().equalsIgnoreCase(name)) {
                Vec3 spot = other.position();
                self.setPos(spot.x, spot.y, spot.z);
                ChatUtil.message("§7Moved you to §b" + name);
                return;
            }
        }
        ChatUtil.error("Nobody nearby is called " + name);
    }
}
