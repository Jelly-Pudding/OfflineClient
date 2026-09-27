package com.jellypudding.offlineclient.command;

import com.jellypudding.offlineclient.config.KeyedStore;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// A command over a store of named entries that can each go on a key. Listing and removing
// entries and choosing a key work the same for every store. Each command adds its own verbs.
public abstract class KeyedCommand<T extends KeyedStore.Keyed<T>> extends Command {

    private static final List<String> SHARED_VERBS = List.of("remove", "list", "key");

    // What chat calls one entry.
    private final String noun;
    private final List<String> verbs;

    protected KeyedCommand(String name, String description, String noun, List<String> ownVerbs,
                           String arguments, String... aliases) {
        super(name, description, name + " <" + String.join("|", withShared(ownVerbs)) + "> " + arguments,
            aliases);
        this.noun = noun;
        this.verbs = withShared(ownVerbs);
    }

    protected abstract KeyedStore<T> store();

    // Runs one of the command's own verbs. Anything else shows the usage.
    protected abstract void runOwn(String verb, String[] args);

    // What the list shows after the name and key of an entry.
    protected abstract String summary(T entry);

    // What chat says once an entry has its key.
    protected abstract String keyedMessage(T entry, String keyName);

    @Override
    public void execute(String[] args) {
        String verb = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (verb) {
            case "list" -> list();
            case "remove" -> remove(args);
            case "key" -> key(args);
            default -> runOwn(verb, args);
        }
    }

    // Null after telling the player nothing answers to that name.
    protected T find(String name) {
        T entry = store().find(name);
        if (entry == null) {
            ChatUtil.error("There is no " + noun + " called " + name);
        }
        return entry;
    }

    private void list() {
        List<T> entries = store().all();
        if (entries.isEmpty()) {
            ChatUtil.message("§7You have no " + noun + "s.");
            return;
        }
        for (T entry : entries) {
            ChatUtil.message("§b" + entry.name() + " §8(" + keyName(entry.key()) + ")§7 " + summary(entry));
        }
    }

    private void remove(String[] args) {
        if (args.length != 2) {
            usage(getName() + " remove <name>");
            return;
        }
        if (store().remove(args[1])) {
            ChatUtil.message("§b" + args[1] + " §7removed.");
        } else {
            ChatUtil.error("There is no " + noun + " called " + args[1]);
        }
    }

    private void key(String[] args) {
        if (args.length != 3) {
            usage(getName() + " key <name> <key|none>");
            return;
        }
        T entry = find(args[1]);
        if (entry == null) {
            return;
        }
        int key = KeybindSetting.keyFromName(args[2]);
        if (key == KeybindSetting.UNKNOWN) {
            ChatUtil.error("That key means nothing to me.");
            return;
        }
        store().add(entry.withKey(key));
        ChatUtil.message(keyedMessage(entry, keyName(key)));
    }

    private static String keyName(int key) {
        return key == KeybindSetting.UNBOUND ? "no key" : KeybindSetting.nameOfKey(key);
    }

    private static List<String> withShared(List<String> ownVerbs) {
        List<String> verbs = new ArrayList<>(ownVerbs);
        verbs.addAll(SHARED_VERBS);
        return List.copyOf(verbs);
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, verbs);
        }
        if (index != 2 || tokens[1].equalsIgnoreCase("list")) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (T entry : store().all()) {
            names.add(entry.name());
        }
        return CommandManager.filter(current, names);
    }
}
