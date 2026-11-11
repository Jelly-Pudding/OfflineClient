package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.Blueprint;
import com.jellypudding.offlineclient.config.BuildFiles;
import com.jellypudding.offlineclient.config.BuildFrame;
import com.jellypudding.offlineclient.config.BuildPlacements;
import com.jellypudding.offlineclient.config.BuildPlacements.Placement;
import com.jellypudding.offlineclient.modules.world.AutoBuild;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ClientLines;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Puts build files down in the world and moves and turns and removes them. AutoBuild draws
// each one and builds it whilst it is on.
public final class BuildCommand extends Command {

    private static final List<String> VERBS = List.of("place", "move", "turn", "mirror", "hide", "show", "remove",
        "list", "files");
    // Other words for a verb.
    private static final Map<String, String> ALIASES = Map.of("add", "place", "rotate", "turn", "flip", "mirror",
        "delete", "remove", "del", "remove");

    private static final String PLACE_USAGE = "build place <file> [x y z]";
    private static final String MOVE_USAGE = "build move <name> <x y z or here>";
    private static final String TURN_USAGE = "build turn <name> [left]";

    private static final String HERE = "here";
    private static final String LEFT = "left";

    public BuildCommand() {
        super("build", "Puts a build file down in the world for AutoBuild and moves or turns or removes it.",
            "build <place|move|turn|mirror|hide|show|remove|list|files> [file or name] [x y z]", "printer");
    }

    @Override
    public void execute(String[] args) {
        if (OfflineClient.MC.player == null) {
            return;
        }
        if (args.length == 0) {
            usage();
            ChatUtil.message("§7Files live in offlineclient/schematics. A file goes down where you stand the"
                + " way it was saved. In move a tilde counts from where the build is.");
            return;
        }
        switch (verb(args[0])) {
            case "place" -> place(args);
            case "move" -> move(args);
            case "turn" -> turn(args);
            case "mirror" -> mirror(args);
            case "hide" -> hidden(args, true);
            case "show" -> hidden(args, false);
            case "remove" -> remove(args);
            case "list" -> list();
            case "files" -> files();
            default -> usage();
        }
    }

    // The verb a typed word stands for.
    private static String verb(String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(lower, lower);
    }

    // A file name may hold spaces. Three coordinates after it are told apart by what they are.
    private void place(String[] args) {
        if (args.length < 2) {
            usage(PLACE_USAGE);
            return;
        }
        LocalPlayer player = OfflineClient.MC.player;
        boolean spot = args.length >= 5 && Arrays.stream(args, args.length - 3, args.length)
            .allMatch(BuildCommand::isCoordinate);
        BlockPos feet = player.blockPosition();
        Vec3 where = spot ? coordinates(args, args.length - 3, Vec3.atLowerCornerOf(feet)) : null;
        if (spot && where == null) {
            return;
        }
        String typed = String.join(" ", Arrays.copyOfRange(args, 1, spot ? args.length - 3 : args.length));
        String file = fileNamed(typed);
        if (file == null) {
            ChatUtil.error("There is no file called " + typed + " in offlineclient/schematics.");
            return;
        }
        BlockPos anchor = spot ? inWorld(where) : feet;
        if (anchor == null) {
            return;
        }
        String world = ServerInfo.worldKey();
        BuildFiles.read(file, result -> ClientLines.inChat(() -> placed(file, anchor, world, result)));
    }

    private static boolean isCoordinate(String text) {
        if (text.startsWith("~")) {
            return true;
        }
        try {
            Double.parseDouble(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // The file a player means. The ending may be left off whilst only one file has that name.
    private static String fileNamed(String typed) {
        List<String> names = BuildFiles.names();
        for (String name : names) {
            if (name.equalsIgnoreCase(typed)) {
                return name;
            }
        }
        List<String> stems = names.stream().filter(name -> BuildFiles.stem(name).equalsIgnoreCase(typed)).toList();
        return stems.size() == 1 ? stems.getFirst() : null;
    }

    // The block a typed spot lies in. Null after telling the player it lies outside the world.
    private static BlockPos inWorld(Vec3 typed) {
        BlockPos spot = BlockPos.containing(typed);
        if (Level.isInSpawnableBounds(spot)) {
            return spot;
        }
        ChatUtil.error("That spot lies outside the world.");
        return null;
    }

    // A big file is read off the game thread and the answer can come a moment after the command.
    private static void placed(String file, BlockPos anchor, String world, BuildFiles.Result result) {
        if (result.problem() != null) {
            ChatUtil.error(result.problem());
        } else if (!world.equals(ServerInfo.worldKey())) {
            ChatUtil.error("You left that world before " + file + " was read.");
        } else {
            Modules.get(AutoBuild.class).putDown(file, result.loaded(), BuildFrame.at(anchor));
        }
    }

    private void move(String[] args) {
        boolean here = args.length == 3 && args[2].equalsIgnoreCase(HERE);
        if (!here && args.length != 5) {
            usage(MOVE_USAGE);
            return;
        }
        Placement placement = named(args[1]);
        if (placement == null) {
            return;
        }
        BlockPos spot = OfflineClient.MC.player.blockPosition();
        if (!here) {
            Vec3 typed = coordinates(args, 2, Vec3.atLowerCornerOf(placement.origin()));
            spot = typed == null ? null : inWorld(typed);
            if (spot == null) {
                return;
            }
        }
        BuildPlacements.get().update(placement.with(placement.frame().movedTo(spot)));
        ChatUtil.message("§7Moved §b" + placement.name() + " §7to §f" + BlockUtil.text(spot) + "§7.");
    }

    private void turn(String[] args) {
        boolean left = args.length == 3 && args[2].equalsIgnoreCase(LEFT);
        if (args.length != 2 && !left) {
            usage(TURN_USAGE);
            return;
        }
        Placement placement = named(args[1]);
        if (placement == null) {
            return;
        }
        Placement turned = placement.with(placement.frame().turned(!left));
        BuildPlacements.get().update(turned);
        ChatUtil.message("§7Turned §b" + placement.name() + " §7a quarter to the " + (left ? "left" : "right")
            + ". It is " + turnWords(turned.rotation()) + ".");
    }

    private static String turnWords(Rotation rotation) {
        return switch (rotation) {
            case NONE -> "the way it was saved";
            case CLOCKWISE_90 -> "a quarter turn to the right of how it was saved";
            case CLOCKWISE_180 -> "the other way round from how it was saved";
            case COUNTERCLOCKWISE_90 -> "a quarter turn to the left of how it was saved";
        };
    }

    private void mirror(String[] args) {
        if (args.length != 2) {
            usage("build mirror <name>");
            return;
        }
        Placement placement = named(args[1]);
        if (placement == null) {
            return;
        }
        Placement flipped = placement.with(placement.frame().mirrored());
        BuildPlacements.get().update(flipped);
        ChatUtil.message("§b" + placement.name() + " §7is "
            + (flipped.mirror() == Mirror.NONE ? "the right way round again." : "mirrored."));
    }

    // A hidden placement stays saved but is neither drawn nor built.
    private void hidden(String[] args, boolean hide) {
        if (args.length != 2) {
            usage("build " + (hide ? "hide" : "show") + " <name>");
            return;
        }
        Placement placement = named(args[1]);
        if (placement == null) {
            return;
        }
        BuildPlacements.get().update(placement.withHidden(hide));
        ChatUtil.message("§b" + placement.name() + " §7is " + (hide ? "hidden. It is not built until you show it"
            + " again." : "shown."));
    }

    private void remove(String[] args) {
        if (args.length != 2) {
            usage("build remove <name>");
            return;
        }
        Placement placement = named(args[1]);
        if (placement == null) {
            return;
        }
        BuildPlacements.get().remove(placement);
        ChatUtil.message("§cRemoved §b" + placement.name() + "§7. The blocks that stand stay.");
    }

    // Null after telling the player nothing here has that name.
    private static Placement named(String name) {
        Placement placement = BuildPlacements.get().find(name);
        if (placement == null) {
            ChatUtil.error("No build called " + name + " is put down in this world.");
        }
        return placement;
    }

    private static void list() {
        List<Placement> placements = BuildPlacements.get().here();
        if (placements.isEmpty()) {
            ChatUtil.message("§7No build is put down in this world yet.");
            return;
        }
        for (Placement placement : placements) {
            Blueprint blueprint = BuildFiles.ready(placement.file());
            String where = blueprint == null ? "§7at §f" + BlockUtil.text(placement.origin())
                : AutoBuild.reach(placement.frame(), blueprint);
            ChatUtil.message("§b" + placement.name() + " " + where + (placement.hidden() ? " §8hidden" : ""));
        }
    }

    // Clicking a name types the command that puts it down.
    private static void files() {
        List<String> names = BuildFiles.names();
        if (names.isEmpty()) {
            ChatUtil.component(Component.literal("§7There are no build files in ")
                .append(ChatUtil.folderLink(BuildFiles.folder())).append("§7 yet."));
            return;
        }
        for (String name : names) {
            ChatUtil.component(ChatUtil.typeLink(Component.literal("§b" + name), "build place " + name));
        }
    }

    private static List<String> placementNames() {
        return BuildPlacements.get().here().stream().map(Placement::name).toList();
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, VERBS);
        }
        String verb = verb(tokens[1]);
        if (index == 2) {
            return switch (verb) {
                case "place" -> CommandManager.filter(current, BuildFiles.names());
                case "list", "files" -> List.of();
                default -> CommandManager.filter(current, placementNames());
            };
        }
        if (index == 3 && verb.equals("turn")) {
            return CommandManager.filter(current, List.of(LEFT));
        }
        return index == 3 && verb.equals("move") ? CommandManager.filter(current, List.of(HERE)) : List.of();
    }
}
