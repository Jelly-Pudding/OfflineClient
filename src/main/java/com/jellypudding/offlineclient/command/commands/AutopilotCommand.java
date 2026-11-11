package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.modules.movement.Autopilot;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class AutopilotCommand extends Command {

    private static final String AREA = "area";
    private static final String RESUME = "resume";
    private static final String STOP = "stop";
    // The word and the two corners of an area.
    private static final int AREA_WORDS = 5;

    public AutopilotCommand() {
        super("autopilot", "Flies you to a spot or a waypoint or sweeps an area or carries on with the saved sweep"
                + " or stops the flight.",
            "autopilot <x z|waypoint|area x z x z|resume|stop>");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        Autopilot autopilot = Modules.get(Autopilot.class);
        if (player == null || autopilot == null) {
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase(STOP)) {
            if (!autopilot.isEnabled()) {
                ChatUtil.error("The autopilot is not flying.");
                return;
            }
            autopilot.setEnabled(false);
            ChatUtil.tell(autopilot.getName(), "stopped.");
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase(RESUME)) {
            String why = autopilot.resume();
            if (why != null) {
                ChatUtil.error(why + ".");
            }
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase(AREA)) {
            sweepArea(autopilot, player, args);
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

    private void sweepArea(Autopilot autopilot, LocalPlayer player, String[] args) {
        if (args.length != AREA_WORDS) {
            usage("autopilot area <x z> <x z>");
            return;
        }
        Vec3 one = flatCoordinates(args, 1, player.position());
        Vec3 two = one == null ? null : flatCoordinates(args, 3, player.position());
        if (two != null) {
            autopilot.sweepArea(Mth.floor(one.x), Mth.floor(one.z), Mth.floor(two.x), Mth.floor(two.z));
        }
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || index < 1) {
            return List.of();
        }
        boolean area = tokens.length > 1 && tokens[1].equalsIgnoreCase(AREA);
        // After the word area come two corners of an x and a z each.
        int axis = area ? index - 2 : index - 1;
        if (area ? index > AREA_WORDS : index > 2) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        if (index == 1) {
            options.add(STOP);
            options.add(RESUME);
            options.add(AREA);
            WaypointStore.get().here().forEach(waypoint -> options.add(waypoint.name()));
        }
        if (axis >= 0) {
            options.add("~");
            options.add(String.valueOf(axis % 2 == 0 ? player.getBlockX() : player.getBlockZ()));
        }
        return CommandManager.filter(current, options);
    }
}
