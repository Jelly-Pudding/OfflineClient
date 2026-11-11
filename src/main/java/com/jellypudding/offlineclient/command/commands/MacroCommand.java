package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.command.KeyedCommand;
import com.jellypudding.offlineclient.config.MacroLine;
import com.jellypudding.offlineclient.config.MacroStore;
import com.jellypudding.offlineclient.config.MacroStore.Macro;
import com.jellypudding.offlineclient.setting.Bind;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Tally;

import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

// Lines are split on a semicolon. Several can go under one name. A line can wait or repeat
// another and the word cycle in front makes a macro that runs one line a press.
public final class MacroCommand extends KeyedCommand<Macro> {

    // Where a macro runs on joining. Here is the server you are on.
    private static final String HERE = "here";
    private static final String EVERYWHERE = "everywhere";
    private static final String OFF = "off";

    private static final String JOIN_USAGE = "macro join <name> <here|everywhere|off> [seconds]";

    public MacroCommand() {
        super("macro", "Saves a list of lines under a name and a key or to run when you join. A line can wait"
                + " or repeat another.", "macro", List.of("add", "run", "stop", "join"), "[name] [lines]", "m");
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
            case "stop" -> stop(args);
            case "join" -> join(args);
            default -> usage();
        }
    }

    @Override
    protected String summary(Macro macro) {
        String join = macro.joinText() == null ? "" : " §8" + macro.joinText();
        return macro.typedText() + join + (store().running(macro.name()) ? " §arunning" : "");
    }

    @Override
    protected String keyedMessage(Macro macro, String keyName) {
        return "§b" + macro.name() + " §7runs on §b" + keyName;
    }

    // Saving over a macro keeps its key and its join.
    private void add(String[] args) {
        if (args.length < 3) {
            usage("macro add <name> <lines>");
            ChatUtil.message("§7Put a semicolon between lines. §f" + MacroLine.WAIT + " 2s§7 pauses and §f"
                + MacroLine.REPEAT + " 3 1s hello§7 says hello three times a second apart. Start with §f"
                + MacroStore.CYCLE + "§7 to run one line a press.");
            return;
        }
        MacroStore.Typed typed = MacroStore.typed(words(args, 2));
        if (typed.problem() != null) {
            ChatUtil.error(typed.problem());
            return;
        }
        Macro existing = store().find(args[1]);
        store().add(existing == null ? new Macro(args[1], Bind.NONE, typed) : existing.with(typed));
        String lines = "§b" + Tally.counted(typed.lines().size(), "line") + "§7";
        ChatUtil.message(typed.cycles() ? "§b" + args[1] + " §7saved. Each press runs the next of its " + lines + "."
            : "§b" + args[1] + " §7saved with " + lines + ".");
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

    // Without a name every macro still running ends.
    private void stop(String[] args) {
        if (args.length > 2) {
            usage("macro stop [name]");
            return;
        }
        int stopped = store().stop(args.length == 2 ? args[1] : null);
        if (stopped > 0) {
            ChatUtil.message("§7Stopped §b" + Tally.counted(stopped, "macro") + "§7.");
        } else {
            ChatUtil.message(args.length == 2 ? "§b" + args[1] + " §7is not running." : "§7No macro is running.");
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
        if (index == 3 && tokens[1].equalsIgnoreCase("add")) {
            return CommandManager.filter(current, List.of(MacroStore.CYCLE));
        }
        return super.complete(tokens, index, current);
    }
}
