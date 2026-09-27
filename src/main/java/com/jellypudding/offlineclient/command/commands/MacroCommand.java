package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.command.KeyedCommand;
import com.jellypudding.offlineclient.config.MacroStore;
import com.jellypudding.offlineclient.config.MacroStore.Macro;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ServerInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

// Lines are split on a semicolon. Several can go under one name.
public final class MacroCommand extends KeyedCommand<Macro> {

    private static final String SPLIT = ";";

    // Where a macro runs on joining. Here is the server you are on.
    private static final String HERE = "here";
    private static final String EVERYWHERE = "everywhere";
    private static final String OFF = "off";

    private static final String JOIN_USAGE = "macro join <name> <here|everywhere|off> [seconds]";

    public MacroCommand() {
        super("macro", "Saves a list of lines under a name and a key or to run when you join.", "macro",
            List.of("add", "run", "join"), "[name] [lines]", "m");
    }

    @Override
    protected MacroStore store() {
        return MacroStore.get();
    }

    @Override
    protected void runOwn(String verb, String[] args) {
        switch (verb) {
            case "add" -> add(args);
            case "run" -> run(args);
            case "join" -> join(args);
            default -> usage();
        }
    }

    @Override
    protected String summary(Macro macro) {
        String join = macro.joinText() == null ? "" : " §8" + macro.joinText();
        return String.join("§8 " + SPLIT + " §7", macro.lines()) + join;
    }

    @Override
    protected String keyedMessage(Macro macro, String keyName) {
        return "§b" + macro.name() + " §7runs on §b" + keyName;
    }

    // Saving over a macro keeps its key and its join.
    private void add(String[] args) {
        if (args.length < 3) {
            usage("macro add <name> <lines>");
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        List<String> lines = new ArrayList<>();
        for (String line : text.split(SPLIT)) {
            if (!line.isBlank()) {
                lines.add(line.trim());
            }
        }
        if (lines.isEmpty()) {
            ChatUtil.error("Give it something to run.");
            return;
        }
        Macro existing = store().find(args[1]);
        store().add(existing == null
            ? new Macro(args[1], KeybindSetting.UNBOUND, lines) : existing.withLines(lines));
        ChatUtil.message("§b" + args[1] + " §7saved with §b" + lines.size() + " §7lines.");
    }

    private void run(String[] args) {
        if (args.length != 2) {
            usage("macro run <name>");
            return;
        }
        Macro macro = find(args[1]);
        if (macro != null) {
            store().run(macro);
        }
    }

    private void join(String[] args) {
        if (args.length < 3 || args.length > 4) {
            usage(JOIN_USAGE);
            return;
        }
        Macro macro = find(args[1]);
        if (macro == null) {
            return;
        }
        int delay = 0;
        if (args.length == 4) {
            OptionalInt seconds = wholeNumber(args[3]);
            if (seconds.isEmpty()) {
                return;
            }
            if (seconds.getAsInt() < 0) {
                ChatUtil.error("The wait cannot be below nought.");
                return;
            }
            delay = seconds.getAsInt();
        }
        String server;
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case HERE -> server = ServerInfo.key();
            case EVERYWHERE -> server = MacroStore.ANY_SERVER;
            case OFF -> server = null;
            default -> {
                usage(JOIN_USAGE);
                return;
            }
        }
        Macro changed = macro.withJoin(server, delay);
        store().add(changed);
        if (server == null) {
            ChatUtil.message("§b" + macro.name() + " §7will not run when you join.");
        } else {
            ChatUtil.message("§b" + macro.name() + " §7runs " + changed.joinText() + ".");
        }
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 3 && tokens[1].equalsIgnoreCase("join")) {
            return CommandManager.filter(current, List.of(HERE, EVERYWHERE, OFF));
        }
        return super.complete(tokens, index, current);
    }
}
