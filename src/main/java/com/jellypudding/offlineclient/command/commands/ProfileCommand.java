package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.ConfigManager.Part;
import com.jellypudding.offlineclient.config.Profiles;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ServerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ProfileCommand extends Command {

    private static final String SAVE = "save";
    private static final String LOAD = "load";
    private static final String LIST = "list";
    private static final String DELETE = "delete";
    private static final String JOIN = "join";
    private static final String OFF = "off";

    private static final String JOIN_USAGE = "profile join <name|off>";

    public ProfileCommand() {
        super("profile", "Saves or loads a named setup or only parts of it such as binds or settings. Macros and"
            + " waypoints only go in when named. Join loads a profile every time you join the server you are on.",
            "profile <save|load|delete|join|list> [name] [parts]", "p");
    }

    @Override
    public void execute(String[] args) {
        Profiles profiles = OfflineClient.INSTANCE.getConfigManager().profiles();
        String verb = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (verb.equals(LIST) && args.length == 1) {
            list(profiles);
        } else if (verb.equals(JOIN)) {
            join(profiles, args);
        } else if (verb.equals(DELETE) && args.length == 2) {
            delete(profiles, args[1]);
        } else if ((verb.equals(SAVE) || verb.equals(LOAD)) && args.length >= 2) {
            Set<Part> parts = Profiles.partsNamed(args, 2);
            if (parts == null) {
                return;
            }
            if (verb.equals(SAVE)) {
                profiles.save(args[1], parts);
            } else {
                profiles.load(args[1], parts);
            }
        } else {
            usage();
        }
    }

    private static void list(Profiles profiles) {
        List<String> names = profiles.list();
        if (names.isEmpty()) {
            ChatUtil.message("§7You have no saved profiles.");
            return;
        }
        ChatUtil.message("§3Profiles §b" + String.join("§7 §b", names));
        String here = profiles.joinProfile(ServerInfo.key());
        if (here != null) {
            ChatUtil.message("§7Joining this server loads §b" + here + "§7.");
        }
    }

    private static void delete(Profiles profiles, String name) {
        if (profiles.delete(name)) {
            ChatUtil.message("§7Deleted the profile §b" + name + "§7.");
        } else {
            ChatUtil.error("There is no profile called " + name + ".");
        }
    }

    private void join(Profiles profiles, String[] args) {
        if (args.length != 2) {
            usage(JOIN_USAGE);
            return;
        }
        if (OfflineClient.MC.player == null) {
            ChatUtil.error("Join a server first. The profile is tied to the one you are on.");
            return;
        }
        String server = ServerInfo.key();
        if (args[1].equalsIgnoreCase(OFF)) {
            profiles.setJoinProfile(server, null);
            ChatUtil.message("§7Joining this server loads no profile.");
            return;
        }
        if (!profiles.exists(args[1])) {
            ChatUtil.error("There is no profile called " + args[1] + ".");
            return;
        }
        profiles.setJoinProfile(server, args[1]);
        ChatUtil.message("§7Joining this server loads the profile §b" + args[1] + "§7 from your next join.");
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, List.of(SAVE, LOAD, LIST, DELETE, JOIN));
        }
        String verb = tokens[1].toLowerCase(Locale.ROOT);
        Profiles profiles = OfflineClient.INSTANCE.getConfigManager().profiles();
        if (index == 2 && !verb.equals(LIST)) {
            List<String> names = new ArrayList<>(profiles.list());
            if (verb.equals(JOIN)) {
                names.add(OFF);
            }
            return CommandManager.filter(current, names);
        }
        if (index > 2 && (verb.equals(SAVE) || verb.equals(LOAD))) {
            List<String> left = new ArrayList<>(Profiles.allPartWords());
            for (int i = 3; i < index; i++) {
                left.remove(tokens[i].toLowerCase(Locale.ROOT));
            }
            return CommandManager.filter(current, left);
        }
        return List.of();
    }
}
