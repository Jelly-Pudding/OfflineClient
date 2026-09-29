package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.FindLog.Find;
import com.jellypudding.offlineclient.config.FindSource;
import com.jellypudding.offlineclient.gui.FindsScreen;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// What the hunting modules found on this server. On its own it opens the finds list.
public final class FindsCommand extends Command {

    // One find and how far it lies from you.
    private record Near(FindLog log, Find find, double distance) {
    }

    private static final int NEAR_COUNT = 5;
    private static final String NEAR = "near";
    private static final String CLEAR = "clear";
    private static final String EXPORT = "export";
    private static final String ALL = "all";

    public FindsCommand() {
        super("finds", "Opens the list of finds or names the nearest in chat.",
            "finds [module] or finds near [module] or finds clear <module> [all] or finds export <module>");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            FindsScreen.open(null);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case NEAR -> near(args);
            case CLEAR -> clear(args);
            case EXPORT -> export(args);
            default -> {
                if (args.length > 1) {
                    usage();
                    return;
                }
                FindLog log = log(args[0]);
                if (log != null) {
                    FindsScreen.open(log);
                }
            }
        }
    }

    // The nearest finds in the dimension you are in. A click on the coordinates walks there.
    private void near(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (args.length > 2) {
            usage("finds near [module]");
            return;
        }
        FindLog only = args.length == 2 ? log(args[1]) : null;
        if (args.length == 2 && only == null) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        List<Near> near = new ArrayList<>();
        for (FindLog log : only == null ? FindSource.logs() : List.of(only)) {
            for (Find find : log.here()) {
                near.add(new Near(log, find, Math.sqrt(log.distanceSqr(find, eye))));
            }
        }
        if (near.isEmpty()) {
            ChatUtil.message("§7Nothing has been found in this dimension yet.");
            return;
        }
        near.sort(Comparator.comparingDouble(Near::distance));
        ChatUtil.message("§3Nearest finds");
        for (Near one : near.subList(0, Math.min(NEAR_COUNT, near.size()))) {
            Vec3 at = Vec3.atCenterOf(one.find().pos());
            ChatUtil.component(Component.literal("§b" + one.log().name() + " §f" + one.find().detail() + "§7 "
                    + Notice.away(one.distance(), eye, at) + " at ")
                .append(ChatUtil.walkLink(one.find().pos())));
        }
    }

    // The dimension you are in or with all every dimension of this server. Like the rows
    // that do the same it waits for the command a second time.
    private void clear(String[] args) {
        boolean everywhere = args.length == 3 && args[2].equalsIgnoreCase(ALL);
        if (args.length != 2 && !everywhere) {
            usage("finds clear <module> [all]");
            return;
        }
        FindLog log = log(args[1]);
        if (log != null) {
            press(everywhere ? log.clearServerSetting() : log.clearDimensionSetting());
        }
    }

    // A spreadsheet file of every find on this server beside the saved one.
    private void export(String[] args) {
        if (args.length != 2) {
            usage("finds export <module>");
            return;
        }
        FindLog log = log(args[1]);
        if (log == null) {
            return;
        }
        int count = log.all().size();
        log.export(file -> {
            if (file == null) {
                ChatUtil.error("Could not write the finds of " + log.name() + ".");
                return;
            }
            ChatUtil.component(Component.literal("§7Wrote " + Tally.counted(count, "find") + " to ")
                .append(ChatUtil.fileLink(file)).append("§7."));
        });
    }

    // Null after telling the player no module of that name keeps finds.
    private static FindLog log(String name) {
        FindLog log = FindSource.named(name);
        if (log == null) {
            ChatUtil.error("§f" + name + "§c keeps no finds. Pick one of §f" + String.join("§c/§f", names())
                + "§c.");
        }
        return log;
    }

    private static List<String> names() {
        return FindSource.logs().stream().map(log -> Setting.idFor(log.name())).toList();
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            List<String> options = new ArrayList<>(List.of(NEAR, CLEAR, EXPORT));
            options.addAll(names());
            return CommandManager.filter(current, options);
        }
        String first = tokens[1].toLowerCase(Locale.ROOT);
        boolean takesModule = first.equals(NEAR) || first.equals(CLEAR) || first.equals(EXPORT);
        if (index == 2 && takesModule) {
            return CommandManager.filter(current, names());
        }
        if (index == 3 && first.equals(CLEAR)) {
            return CommandManager.filter(current, List.of(ALL));
        }
        return List.of();
    }
}
