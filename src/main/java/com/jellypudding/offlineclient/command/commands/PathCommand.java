package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.command.PlaceCommand;
import com.jellypudding.offlineclient.path.RouteView;
import com.jellypudding.offlineclient.path.Travel;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

public final class PathCommand extends PlaceCommand {

    private static final String CLEAR = "clear";

    public PathCommand() {
        super("path", "Draws the way to a spot or a player or a waypoint or the block you point at for you"
            + " to walk yourself.", "path " + PLACE + " or path clear", "route");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        // A player or a waypoint called clear is a place like any other.
        if (args.length == 1 && args[0].equalsIgnoreCase(CLEAR) && known(args[0]) == null) {
            if (RouteView.clear()) {
                ChatUtil.tell("Path", "cleared.");
            } else {
                ChatUtil.error("No path is drawn.");
            }
            return;
        }
        Travel.Stop stop = place(args, player);
        if (stop == null) {
            return;
        }
        if (RouteView.show(stop.goal())) {
            ChatUtil.tell("Path", "looking for the way to §f" + stop.name() + "§7.");
        } else {
            ChatUtil.error("Could not start the search.");
        }
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index < 1) {
            return List.of();
        }
        List<String> options = placeOptions(index - 1);
        if (index == 1) {
            options.add(CLEAR);
        }
        return CommandManager.filter(current, options);
    }
}
