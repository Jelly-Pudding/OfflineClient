package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.MacroStore;
import com.jellypudding.offlineclient.config.MacroStore.Macro;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

// Lines are split on a semicolon so several can go under one name.
public final class MacroCommand extends Command {

    private static final String SPLIT = ";";

    public MacroCommand() {
        super("macro", "Saves a list of lines under a name and a key.",
            "macro <add|remove|list|run|key> [name] [lines]", "m");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        MacroStore store = MacroStore.get();
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(store);
            case "add" -> add(store, args);
            case "remove" -> remove(store, args);
            case "run" -> run(store, args);
            case "key" -> key(store, args);
            default -> usage();
        }
    }

    private static void list(MacroStore store) {
        List<Macro> macros = store.all();
        if (macros.isEmpty()) {
            ChatUtil.message("§7You have no macros.");
            return;
        }
        for (Macro macro : macros) {
            String key = macro.key() == KeybindSetting.UNBOUND
                ? "no key" : KeybindSetting.nameOfKey(macro.key());
            ChatUtil.message("§b" + macro.name() + " §8(" + key + ")§7 "
                + String.join("§8 " + SPLIT + " §7", macro.lines()));
        }
    }

    private static void add(MacroStore store, String[] args) {
        if (args.length < 3) {
            ChatUtil.error("Usage: macro add <name> <lines>");
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
        Macro existing = store.find(args[1]);
        int key = existing == null ? KeybindSetting.UNBOUND : existing.key();
        store.add(new Macro(args[1], key, lines));
        ChatUtil.message("§b" + args[1] + " §7saved with §b" + lines.size() + " §7lines.");
    }

    private static void remove(MacroStore store, String[] args) {
        if (args.length != 2) {
            ChatUtil.error("Usage: macro remove <name>");
            return;
        }
        if (store.remove(args[1])) {
            ChatUtil.message("§b" + args[1] + " §7removed.");
        } else {
            ChatUtil.error("There is no macro called " + args[1]);
        }
    }

    private static void run(MacroStore store, String[] args) {
        if (args.length != 2) {
            ChatUtil.error("Usage: macro run <name>");
            return;
        }
        Macro macro = store.find(args[1]);
        if (macro == null) {
            ChatUtil.error("There is no macro called " + args[1]);
            return;
        }
        store.run(macro);
    }

    private static void key(MacroStore store, String[] args) {
        if (args.length != 3) {
            ChatUtil.error("Usage: macro key <name> <key|none>");
            return;
        }
        Macro macro = store.find(args[1]);
        if (macro == null) {
            ChatUtil.error("There is no macro called " + args[1]);
            return;
        }
        int key = KeybindSetting.keyFromName(args[2]);
        if (key == KeybindSetting.UNKNOWN) {
            ChatUtil.error("That key means nothing to me.");
            return;
        }
        store.add(macro.withKey(key));
        ChatUtil.message("§b" + macro.name() + " §7runs on §b"
            + (key == KeybindSetting.UNBOUND ? "no key" : KeybindSetting.nameOfKey(key)));
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, List.of("add", "remove", "list", "run", "key"));
        }
        if (index != 2 || tokens[1].equalsIgnoreCase("add")
            || tokens[1].equalsIgnoreCase("list")) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Macro macro : MacroStore.get().all()) {
            names.add(macro.name());
        }
        return CommandManager.filter(current, names);
    }
}
