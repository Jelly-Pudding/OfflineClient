package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.config.WaypointStore.Waypoint;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WaypointCommand extends Command {

    private static final int FULL_CIRCLE = 360;

    // Ends each form that may name another dimension.
    private static final String DIMENSION_WORD = " [overworld|nether|end]";

    private static final String ADD_USAGE = "waypoint add <name> [x y z" + DIMENSION_WORD + "]";

    // The word typed for each dimension a waypoint can be marked in.
    private static final Map<String, ResourceKey<Level>> DIMENSIONS = Map.of(
        "overworld", Level.OVERWORLD, "nether", Level.NETHER, "end", Level.END);

    public WaypointCommand() {
        super("waypoint", "Saves named coordinates and marks them in the world.",
            "waypoint <add|remove|colour|hide|show|list|clear> [name] [x y z or hue]" + DIMENSION_WORD, "wp");
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

    // Typed coordinates may name another dimension. The waypoint then shows across a portal.
    private void add(String[] args) {
        if (args.length != 2 && args.length != 5 && args.length != 6) {
            usage(ADD_USAGE);
            return;
        }
        if (OfflineClient.MC.player == null) {
            ChatUtil.error("You need to be in a world to mark a waypoint.");
            return;
        }
        String dimension = dimension(args, 5);
        if (dimension == null) {
            usage(ADD_USAGE);
            return;
        }
        BlockPos pos = OfflineClient.MC.player.blockPosition();
        if (args.length > 2) {
            Vec3 spot = coordinates(args, 2, Vec3.atLowerCornerOf(pos));
            if (spot == null) {
                return;
            }
            pos = BlockPos.containing(spot);
        }
        String name = args[1];
        WaypointStore.get().mark(name, pos, dimension, Modules.nextWaypointHue());
        ChatUtil.message("§aSaved §b" + name + "§7" + elsewhere(dimension) + " at §f" + BlockUtil.text(pos));
    }

    private void remove(String[] args) {
        Waypoint waypoint = target(args, 2, "waypoint remove <name>" + DIMENSION_WORD);
        if (waypoint != null && WaypointStore.get().remove(waypoint.name(), waypoint.dimension())) {
            ChatUtil.message("§cRemoved waypoint §b" + waypoint.name());
        }
    }

    // A hue from 0 to 360 or the word auto to go back to the colour of the name.
    private void colour(String[] args) {
        Waypoint waypoint = target(args, 3, "waypoint colour <name> <hue 0 to 360 or auto>" + DIMENSION_WORD);
        if (waypoint == null) {
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
        Waypoint waypoint = target(args, 2, "waypoint " + (hide ? "hide" : "show") + " <name>" + DIMENSION_WORD);
        if (waypoint == null) {
            return;
        }
        WaypointStore.get().add(waypoint.withHidden(hide));
        ChatUtil.message("§b" + waypoint.name() + " §7is now " + (hide ? "hidden." : "shown."));
    }

    // The waypoint the word after the verb names. A dimension word after the given number of words
    // looks in that dimension instead of this one. Null after telling the player what was wrong.
    private Waypoint target(String[] args, int words, String form) {
        String dimension = args.length == words || args.length == words + 1 ? dimension(args, words) : null;
        if (dimension == null) {
            usage(form);
            return null;
        }
        Waypoint waypoint = WaypointStore.get().find(args[1], dimension);
        if (waypoint == null) {
            ChatUtil.error("There is no waypoint called " + args[1] + elsewhere(dimension) + ".");
        }
        return waypoint;
    }

    // The dimension the word at that index names or the one you are in when the line stops short
    // of it. Null for a word that names no dimension.
    private static String dimension(String[] args, int index) {
        if (args.length <= index) {
            return ServerInfo.dimension();
        }
        ResourceKey<Level> named = DIMENSIONS.get(args[index].toLowerCase(Locale.ROOT));
        return named == null ? null : named.identifier().toString();
    }

    // Words that name the dimension when it is not the one you are in.
    private static String elsewhere(String dimension) {
        return dimension.equals(ServerInfo.dimension()) ? "" : " in the " + ServerInfo.dimensionName(dimension);
    }

    // The dimension you are in comes first and then every other one of this server that holds a waypoint.
    private void list() {
        List<String> dimensions = WaypointStore.get().dimensions();
        if (dimensions.isEmpty()) {
            ChatUtil.message("§7No waypoints in this world yet.");
            return;
        }
        String here = ServerInfo.dimension();
        if (dimensions.remove(here)) {
            dimensions.addFirst(here);
        }
        for (String dimension : dimensions) {
            ChatUtil.message("§3Waypoints in the " + ServerInfo.dimensionName(dimension));
            for (Waypoint waypoint : WaypointStore.get().in(dimension)) {
                ChatUtil.message("§b" + waypoint.name() + " §7at §f" + BlockUtil.text(waypoint.pos())
                    + (waypoint.hidden() ? " §8hidden" : ""));
            }
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
        String verb = tokens[1].toLowerCase(Locale.ROOT);
        if (index == 2 && !verb.equals("add")) {
            return CommandManager.filter(current, names());
        }
        if (index == 3 && verb.equals("colour")) {
            return CommandManager.filter(current, List.of("auto"));
        }
        // The dimension word comes last in each form that takes one.
        boolean last = switch (verb) {
            case "add" -> index == 6;
            case "colour" -> index == 4;
            case "remove", "hide", "show" -> index == 3;
            default -> false;
        };
        return last ? CommandManager.filter(current, List.copyOf(DIMENSIONS.keySet())) : List.of();
    }
}
