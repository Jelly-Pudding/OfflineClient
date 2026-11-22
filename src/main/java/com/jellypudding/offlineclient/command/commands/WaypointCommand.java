package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.config.WaypointStore.Action;
import com.jellypudding.offlineclient.config.WaypointStore.Colour;
import com.jellypudding.offlineclient.config.WaypointStore.Icon;
import com.jellypudding.offlineclient.config.WaypointStore.Near;
import com.jellypudding.offlineclient.config.WaypointStore.Options;
import com.jellypudding.offlineclient.config.WaypointStore.Waypoint;
import com.jellypudding.offlineclient.gui.WaypointsScreen;
import com.jellypudding.offlineclient.modules.render.Waypoints;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Dimension;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.function.UnaryOperator;

public final class WaypointCommand extends Command {

    private static final String AUTO = "auto";
    private static final String RAINBOW = "rainbow";
    private static final String RESET = "reset";
    private static final String ON = "on";
    private static final String OFF = "off";
    // Saturation and brightness are typed from nought to a hundred.
    private static final float PERCENT = 100;
    private static final int PLACES = 4;

    private static final String DIMENSION_WORD = " [overworld|nether|end]";

    private static final String ADD_USAGE = "waypoint add <name> [x y z" + DIMENSION_WORD + "]";
    private static final String MOVE_USAGE = "waypoint move <name> <x y z>" + DIMENSION_WORD;
    private static final String COLOUR_USAGE = "waypoint colour <name> <hue 0 to 360|" + AUTO + "|" + RAINBOW
        + "> [saturation brightness from 0 to 100]" + DIMENSION_WORD;

    private static final List<String> VERBS = List.of("add", "remove", "rename", "move", "colour", "icon", "scale",
        "range", "across", "near", "hide", "show", "edit", "list", "clear");
    private static final List<String> DIMENSION_WORDS = Arrays.stream(Dimension.values())
        .map(Dimension::commandWord).toList();

    private static final String NONE_YET = "§7No waypoints in this world yet.";

    private final ActionSetting clearServer = new ActionSetting("Clear every waypoint on this server",
        "Removes every waypoint saved on this server whichever dimension it is in.",
        () -> "Removed §f" + Tally.counted(WaypointStore.get().clearServer(), "waypoint") + "§7 on this server")
        .confirm();

    public WaypointCommand() {
        super("waypoint", "Saves named coordinates and marks them in the world. On its own it opens the list.",
            "waypoint [" + String.join("|", VERBS) + "] [name] [value]" + DIMENSION_WORD, "wp");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            WaypointsScreen.open();
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add", "set" -> add(args);
            case "remove", "delete", "del" -> remove(args);
            case "rename" -> rename(args);
            case "move" -> move(args);
            case "colour", "color" -> colour(args);
            case "icon" -> icon(args);
            case "scale" -> scale(args);
            case "range" -> range(args);
            case "across" -> across(args);
            case "near" -> near(args);
            case "hide" -> hidden(args, true);
            case "show" -> hidden(args, false);
            case "edit" -> edit(args);
            case "list" -> list();
            case "clear" -> clear();
            default -> usage();
        }
    }

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
        String name = WaypointStore.cleanName(args[1]);
        if (dimension == null || name == null) {
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
        WaypointStore.get().mark(name, pos, dimension, Modules.nextWaypointColour());
        ChatUtil.message("§aSaved §b" + name + "§7" + elsewhere(dimension) + " at §f" + BlockUtil.text(pos));
    }

    private void remove(String[] args) {
        Waypoint waypoint = target(args, 2, "waypoint remove <name>" + DIMENSION_WORD);
        if (waypoint != null && WaypointStore.get().remove(waypoint)) {
            ChatUtil.message("§cRemoved waypoint §b" + waypoint.name());
        }
    }

    private void rename(String[] args) {
        Waypoint waypoint = target(args, 3, "waypoint rename <name> <new name>" + DIMENSION_WORD);
        if (waypoint == null) {
            return;
        }
        String name = WaypointStore.cleanName(args[2]);
        if (name == null || !WaypointStore.get().replace(waypoint, waypoint.withName(name))) {
            ChatUtil.error("Another waypoint" + elsewhere(waypoint.dimension()) + " is already called " + args[2]
                + ".");
            return;
        }
        ChatUtil.message("§b" + waypoint.name() + " §7is now called §b" + name + "§7.");
    }

    private void move(String[] args) {
        if (args.length != 5 && args.length != 6) {
            usage(MOVE_USAGE);
            return;
        }
        if (OfflineClient.MC.player == null) {
            ChatUtil.error("You need to be in a world to move a waypoint.");
            return;
        }
        String dimension = dimension(args, 5);
        if (dimension == null) {
            usage(MOVE_USAGE);
            return;
        }
        WaypointStore store = WaypointStore.get();
        Waypoint waypoint = store.find(args[1], dimension);
        if (waypoint == null) {
            waypoint = store.find(args[1]);
        }
        if (waypoint == null) {
            ChatUtil.error("There is no waypoint called " + args[1]
                + (dimension.equals(ServerInfo.dimension()) ? "" : " here or" + elsewhere(dimension)) + ".");
            return;
        }
        Vec3 spot = coordinates(args, 2, Vec3.atLowerCornerOf(OfflineClient.MC.player.blockPosition()));
        if (spot == null) {
            return;
        }
        BlockPos pos = BlockPos.containing(spot);
        if (!store.replace(waypoint, waypoint.at(pos).in(dimension))) {
            ChatUtil.error("Another waypoint" + elsewhere(dimension) + " is already called " + waypoint.name() + ".");
            return;
        }
        ChatUtil.message("§b" + waypoint.name() + " §7is now at §f" + BlockUtil.text(pos) + "§7" + elsewhere(dimension)
            + ".");
    }

    private void colour(String[] args) {
        boolean shadeTyped = args.length > 3 && Dimension.named(args[3]) == null;
        Waypoint waypoint = target(args, shadeTyped ? 5 : 3, COLOUR_USAGE);
        if (waypoint == null) {
            return;
        }
        String typed = args[2].toLowerCase(Locale.ROOT);
        if (typed.equals(AUTO) || typed.equals(RAINBOW)) {
            if (shadeTyped) {
                usage(COLOUR_USAGE);
            } else if (typed.equals(AUTO)) {
                save(waypoint, waypoint.withColour(Colour.AUTO), "now uses the colour of its name.");
            } else {
                save(waypoint, waypoint.withColour(waypoint.colour().turning(waypoint.name())),
                    "now turns through every colour.");
            }
            return;
        }
        Colour colour;
        try {
            colour = waypoint.colour().withHue(Integer.parseInt(args[2]));
        } catch (NumberFormatException e) {
            ChatUtil.error("§f" + args[2] + "§c is not a hue. Use a number from 0 to 360 or §f" + AUTO + "§c or §f"
                + RAINBOW + "§c.");
            return;
        }
        if (!shadeTyped) {
            save(waypoint, waypoint.withColour(colour), "now uses hue §f" + colour.hue() + "§7.");
            return;
        }
        OptionalDouble saturation = number(args[3]);
        OptionalDouble brightness = saturation.isPresent() ? number(args[4]) : OptionalDouble.empty();
        if (brightness.isEmpty()) {
            return;
        }
        Colour shaded = colour.withShade((float) (saturation.getAsDouble() / PERCENT),
            (float) (brightness.getAsDouble() / PERCENT));
        save(waypoint, waypoint.withColour(shaded), "now uses hue §f" + shaded.hue() + "§7 at §f"
            + percent(shaded.saturation()) + "§7 saturation and §f" + percent(shaded.brightness()) + "§7 brightness.");
    }

    private static String percent(float share) {
        return Math.round(share * PERCENT) + "%";
    }

    private void icon(String[] args) {
        Waypoint waypoint = target(args, 3, "waypoint icon <name> <" + String.join("|", words(Icon.class))
            + "|" + RESET + ">" + DIMENSION_WORD);
        if (waypoint == null) {
            return;
        }
        if (args[2].equalsIgnoreCase(RESET)) {
            change(waypoint, options -> options.withIcon(null), "now takes the icon the module gives.");
            return;
        }
        Icon icon = constant(Icon.class, args[2], "an icon");
        if (icon != null) {
            change(waypoint, options -> options.withIcon(icon), "now has the icon §f" + word(icon) + "§7.");
        }
    }

    private void scale(String[] args) {
        Waypoint waypoint = target(args, 3, "waypoint scale <name> <size or " + RESET + ">" + DIMENSION_WORD);
        if (waypoint == null) {
            return;
        }
        if (args[2].equalsIgnoreCase(RESET)) {
            change(waypoint, options -> options.withScale(null), "now takes the scale the module gives.");
            return;
        }
        OptionalDouble typed = number(args[2]);
        if (typed.isPresent()) {
            double size = Math.max(Options.SMALLEST_SCALE, typed.getAsDouble());
            change(waypoint, options -> options.withScale(size), "is now drawn at scale §f"
                + ChatUtil.decimal(size, PLACES) + "§7.");
        }
    }

    private void range(String[] args) {
        Waypoint waypoint = target(args, 3, "waypoint range <name> <blocks or " + RESET + ">" + DIMENSION_WORD);
        if (waypoint == null) {
            return;
        }
        if (args[2].equalsIgnoreCase(RESET)) {
            change(waypoint, options -> options.withRange(null), "now takes the range the module gives.");
            return;
        }
        OptionalDouble typed = number(args[2]);
        if (typed.isPresent()) {
            double distance = Math.max(0, typed.getAsDouble());
            change(waypoint, options -> options.withRange(distance), distance == 0 ? "is now drawn from any distance."
                : "is now drawn from up to §f" + blocks(distance) + "§7 away.");
        }
    }

    private void across(String[] args) {
        Waypoint waypoint = target(args, 3, "waypoint across <name> <" + ON + "|" + OFF + "|" + RESET + ">"
            + DIMENSION_WORD);
        if (waypoint == null) {
            return;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case ON -> change(waypoint, options -> options.withMirrored(true),
                "now shows on the other side of a nether portal.");
            case OFF -> change(waypoint, options -> options.withMirrored(false),
                "now only shows in its own dimension.");
            case RESET -> change(waypoint, options -> options.withMirrored(null),
                "now follows the Across dimensions switch of the module.");
            default -> ChatUtil.error("Use §f" + ON + "§c or §f" + OFF + "§c or §f" + RESET + "§c.");
        }
    }

    private void near(String[] args) {
        boolean distanceTyped = args.length > 3 && Dimension.named(args[3]) == null;
        Waypoint waypoint = target(args, distanceTyped ? 4 : 3, "waypoint near <name> <"
            + String.join("|", words(Action.class)) + "|" + RESET + "> [blocks]" + DIMENSION_WORD);
        if (waypoint == null) {
            return;
        }
        if (args[2].equalsIgnoreCase(RESET)) {
            change(waypoint, options -> options.withNear(null), "now does what the module says when you come near.");
            return;
        }
        Action action = constant(Action.class, args[2], "something a waypoint can do");
        OptionalDouble typed = distanceTyped ? number(args[3]) : OptionalDouble.of(Near.USUAL_DISTANCE);
        if (action == null || typed.isEmpty()) {
            return;
        }
        Near rule = Near.of(action, typed.getAsDouble());
        change(waypoint, options -> options.withNear(rule), switch (action) {
            case NOTHING -> "now stays when you come near.";
            case HIDE -> "now hides itself within §f" + blocks(rule.distance()) + "§7 of you.";
            case DELETE -> "now removes itself within §f" + blocks(rule.distance()) + "§7 of you.";
        });
        if (rule.acts() && !Modules.enabled(Waypoints.class)) {
            ChatUtil.message("§7It only does that whilst the §bWaypoints §7module is on.");
        }
    }

    private void hidden(String[] args, boolean hide) {
        Waypoint waypoint = target(args, 2, "waypoint " + (hide ? "hide" : "show") + " <name>" + DIMENSION_WORD);
        if (waypoint != null) {
            save(waypoint, waypoint.withHidden(hide), "is now " + (hide ? "hidden." : "shown."));
        }
    }

    private void edit(String[] args) {
        Waypoint waypoint = target(args, 2, "waypoint edit <name>" + DIMENSION_WORD);
        if (waypoint != null) {
            WaypointsScreen.edit(waypoint);
        }
    }

    private static void change(Waypoint waypoint, UnaryOperator<Options> edit, String said) {
        save(waypoint, waypoint.withOptions(edit.apply(waypoint.options())), said);
    }

    private static void save(Waypoint from, Waypoint changed, String said) {
        if (WaypointStore.get().replace(from, changed)) {
            ChatUtil.message("§b" + changed.name() + " §7" + said);
        }
    }

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

    private static String dimension(String[] args, int index) {
        if (args.length <= index) {
            return ServerInfo.dimension();
        }
        Dimension named = Dimension.named(args[index]);
        return named == null ? null : named.id();
    }

    private static String elsewhere(String dimension) {
        return dimension.equals(ServerInfo.dimension()) ? "" : " in the " + ServerInfo.dimensionName(dimension);
    }

    private static <E extends Enum<E>> E constant(Class<E> type, String typed, String what) {
        for (E constant : type.getEnumConstants()) {
            if (word(constant).equalsIgnoreCase(typed)) {
                return constant;
            }
        }
        ChatUtil.error("§f" + typed + "§c is not " + what + ". Use one of §f" + String.join(" ", words(type))
            + "§c.");
        return null;
    }

    private static <E extends Enum<E>> List<String> words(Class<E> type) {
        return Arrays.stream(type.getEnumConstants()).map(WaypointCommand::word).toList();
    }

    private static String word(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }

    // A count of whole blocks would round the fraction away.
    private static String blocks(double distance) {
        return ChatUtil.decimal(distance, PLACES) + (distance == 1 ? " block" : " blocks");
    }

    private void list() {
        List<String> dimensions = WaypointStore.get().dimensions();
        if (dimensions.isEmpty()) {
            ChatUtil.message(NONE_YET);
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
        if (WaypointStore.get().onServer().isEmpty()) {
            ChatUtil.message(NONE_YET);
        } else {
            press(clearServer);
        }
    }

    private static List<String> names() {
        return WaypointStore.get().here().stream().map(Waypoint::name).toList();
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, VERBS);
        }
        String verb = tokens[1].toLowerCase(Locale.ROOT);
        if (index == 2) {
            return List.of("add", "list", "clear").contains(verb) ? List.of() : CommandManager.filter(current, names());
        }
        List<String> options = new ArrayList<>();
        if (index == 3) {
            options.addAll(switch (verb) {
                case "colour" -> List.of(AUTO, RAINBOW);
                case "icon" -> words(Icon.class);
                case "across" -> List.of(ON, OFF);
                case "near" -> words(Action.class);
                default -> List.of();
            });
            if (List.of("icon", "scale", "range", "across", "near").contains(verb)) {
                options.add(RESET);
            }
        }
        boolean last = switch (verb) {
            case "add", "move" -> index == 6;
            case "colour" -> index == 4 || index == 6;
            case "near" -> index == 4 || index == 5;
            case "rename", "icon", "scale", "range", "across" -> index == 4;
            case "remove", "hide", "show", "edit" -> index == 3;
            default -> false;
        };
        if (last) {
            options.addAll(DIMENSION_WORDS);
        }
        return CommandManager.filter(current, options);
    }
}
