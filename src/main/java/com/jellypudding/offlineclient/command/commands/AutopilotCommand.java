package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.modules.movement.Autopilot;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class AutopilotCommand extends Command {

    public AutopilotCommand() {
        super("autopilot", "Flies you to a spot or a waypoint or stops the flight.",
            "autopilot <x z|waypoint|stop>");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        Autopilot autopilot = Modules.get(Autopilot.class);
        if (player == null || autopilot == null) {
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("stop")) {
            if (!autopilot.isEnabled()) {
                ChatUtil.error("The autopilot is not flying.");
                return;
            }
            autopilot.setEnabled(false);
            ChatUtil.message("§bAutopilot §7stopped.");
            return;
        }
        if (args.length == 1) {
            WaypointStore.Waypoint waypoint = WaypointStore.get().find(args[0]);
            if (waypoint == null) {
                ChatUtil.error("No waypoint here is called " + args[0] + ".");
                return;
            }
            Vec3 centre = waypoint.centre();
            autopilot.flyTo(centre.x, centre.z, waypoint.name());
            return;
        }
        if (args.length != 2) {
            usage();
            return;
        }
        Vec3 spot = flatCoordinates(args, 0, player.position());
        if (spot != null) {
            autopilot.flyTo(spot.x, spot.z, null);
        }
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || index < 1 || index > 2) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        if (index == 1) {
            options.add("stop");
            WaypointStore.get().here().forEach(waypoint -> options.add(waypoint.name()));
        }
        options.add("~");
        options.add(String.valueOf(index == 1 ? player.getBlockX() : player.getBlockZ()));
        return CommandManager.filter(current, options);
    }
}
