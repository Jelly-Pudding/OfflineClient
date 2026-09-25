package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.config.WaypointStore.Waypoint;
import com.jellypudding.offlineclient.modules.render.Waypoints;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.util.ServerInfo;

public final class WaypointCommand extends Command {

    private static final int FULL_CIRCLE = 360;

    public WaypointCommand() {
        super("waypoint", "Saves named coordinates and marks them in the world.",
            "waypoint <add|remove|colour|hide|show|list|clear> [name] [x y z or hue]", "wp");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add", "set" -> add(args);
            case "remove", "delete", "del" -> remove(args);
            case "colour", "color" -> colour(args);
            case "hide" -> hidden(args, true);
            case "show" -> hidden(args, false);
            case "list" -> list();
            case "clear" -> clear();
            default -> usage();
        }
    }

    private void add(String[] args) {
        if (args.length < 2) {
            usage("waypoint add <name> [x y z]");
            return;
        }
        if (OfflineClient.MC.player == null) {
            ChatUtil.error("You need to be in a world to mark a waypoint.");
            return;
        }
        BlockPos pos = OfflineClient.MC.player.blockPosition();
        if (args.length >= 5) {
            Vec3 spot = coordinates(args, 2, Vec3.atLowerCornerOf(pos));
            if (spot == null) {
                return;
            }
            pos = BlockPos.containing(spot);
        }
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        String name = args[1];
        Waypoints module = Modules.get(Waypoints.class);
        int hue = module == null ? Waypoint.AUTO_HUE : module.nextHue();
        WaypointStore.get().add(new Waypoint(name, x, y, z,
            WaypointStore.currentDimension(), ServerInfo.key(), hue));
        ChatUtil.message("§aSaved §b" + name + " §7at §f" + x + " " + y + " " + z);
    }

    private void remove(String[] args) {
        if (args.length < 2) {
            usage("waypoint remove <name>");
            return;
        }
        if (WaypointStore.get().remove(args[1])) {
            ChatUtil.message("§cRemoved waypoint §b" + args[1]);
        } else {
            ChatUtil.error("There is no waypoint called " + args[1] + ".");
        }
    }

    // A hue from 0 to 360 or the word auto to go back to the colour of the name.
    private void colour(String[] args) {
        if (args.length < 3) {
            usage("waypoint colour <name> <hue 0 to 360 or auto>");
            return;
        }
        Waypoint waypoint = WaypointStore.get().find(args[1]);
        if (waypoint == null) {
            ChatUtil.error("There is no waypoint called " + args[1] + ".");
            return;
        }
        int hue;
        if (args[2].equalsIgnoreCase("auto")) {
            hue = Waypoint.AUTO_HUE;
        } else {
            try {
                hue = Math.floorMod(Integer.parseInt(args[2]), FULL_CIRCLE);
            } catch (NumberFormatException e) {
                ChatUtil.error("§f" + args[2] + "§c is not a hue. Use a number from 0 to 360 or §fauto§c.");
                return;
            }
        }
        WaypointStore.get().add(waypoint.withHue(hue));
        ChatUtil.message("§b" + waypoint.name() + " §7now uses "
            + (hue < 0 ? "the colour of its name." : "hue §f" + hue + "§7."));
    }

    // A hidden waypoint stays saved but is not drawn.
    private void hidden(String[] args, boolean hide) {
        if (args.length < 2) {
            usage("waypoint " + (hide ? "hide" : "show") + " <name>");
            return;
        }
        Waypoint waypoint = WaypointStore.get().find(args[1]);
        if (waypoint == null) {
            ChatUtil.error("There is no waypoint called " + args[1] + ".");
            return;
        }
        WaypointStore.get().add(waypoint.withHidden(hide));
        ChatUtil.message("§b" + waypoint.name() + " §7is now " + (hide ? "hidden." : "shown."));
    }

    private void list() {
        List<Waypoint> waypoints = WaypointStore.get().here();
        if (waypoints.isEmpty()) {
            ChatUtil.message("§7No waypoints in this world yet.");
            return;
        }
        ChatUtil.message("§3Waypoints in this world");
        for (Waypoint waypoint : waypoints) {
            ChatUtil.message("§b" + waypoint.name() + " §7at §f"
                + waypoint.x() + " " + waypoint.y() + " " + waypoint.z()
                + (waypoint.hidden() ? " §8hidden" : ""));
        }
    }

    private void clear() {
        WaypointStore.get().clear();
        ChatUtil.message("§cRemoved every waypoint.");
    }

    private static List<String> names() {
        return WaypointStore.get().here().stream().map(Waypoint::name).toList();
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current,
                List.of("add", "remove", "colour", "hide", "show", "list", "clear"));
        }
        if (index == 2 && !tokens[1].equalsIgnoreCase("add")) {
            return CommandManager.filter(current, names());
        }
        if (index == 3 && tokens[1].equalsIgnoreCase("colour")) {
            return CommandManager.filter(current, List.of("auto"));
        }
        return List.of();
    }
}
