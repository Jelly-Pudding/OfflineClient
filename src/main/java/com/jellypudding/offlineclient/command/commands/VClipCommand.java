package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.util.Hop;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

public final class VClipCommand extends Command {

    private static final List<String> FLOORS = List.of("up", "down");

    public VClipCommand() {
        super("vclip", "Moves you straight up or down through blocks. With up or down it finds the"
            + " next floor that way.", "vclip <blocks|up|down>", "v");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || args.length != 1) {
            usage();
            return;
        }
        if (FLOORS.contains(args[0].toLowerCase(Locale.ROOT))) {
            OfflineClient.INSTANCE.getCommandManager().find(args[0]).execute(new String[0]);
            return;
        }
        OptionalDouble blocks = number(args[0]);
        if (blocks.isPresent()) {
            hopTo(Hop.mover(player).position().add(0, blocks.getAsDouble(), 0),
                "§7Moved §b" + args[0] + " §7blocks.");
        }
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 1 ? CommandManager.filter(current, FLOORS) : List.of();
    }
}
