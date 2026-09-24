package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.OptionalDouble;

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
        OptionalDouble x = coordinate(args[0], player.getX());
        OptionalDouble y = x.isPresent() ? coordinate(args[1], player.getY()) : OptionalDouble.empty();
        OptionalDouble z = y.isPresent() ? coordinate(args[2], player.getZ()) : OptionalDouble.empty();
        if (z.isPresent()) {
            hopTo(new Vec3(x.getAsDouble(), y.getAsDouble(), z.getAsDouble()), "§7Moved you there.");
        }
    }

    // A tilde means the spot you are already at on that axis.
    private static OptionalDouble coordinate(String text, double here) {
        if (!text.startsWith("~")) {
            return number(text);
        }
        if (text.length() == 1) {
            return OptionalDouble.of(here);
        }
        OptionalDouble offset = number(text.substring(1));
        return offset.isPresent() ? OptionalDouble.of(here + offset.getAsDouble()) : offset;
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
