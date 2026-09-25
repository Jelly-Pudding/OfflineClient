package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;


public final class TpCommand extends Command {

    public TpCommand() {
        super("tp", "Moves you to a spot or to another player.", "tp <x y z> or tp <player>");
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
        Vec3 spot = coordinates(args, 0, player.position());
        if (spot != null) {
            hopTo(spot, "§7Moved you there.");
        }
    }

    private static void toPlayer(LocalPlayer self, String name) {
        for (Player other : OfflineClient.MC.level.players()) {
            if (other != self && other.getGameProfile().name().equalsIgnoreCase(name)) {
                hopTo(other.position(), "§7Moved you to §b" + name);
                return;
            }
        }
        ChatUtil.error("Nobody nearby is called " + name);
    }
}
