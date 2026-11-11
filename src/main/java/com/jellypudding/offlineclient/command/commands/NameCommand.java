package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.NameStore;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.List;

// The name goes out with the next join. An online mode server checks it against your account and
// turns any other name away.
public final class NameCommand extends Command {

    private static final String RESET = "reset";
    private static final String ADD = "add";
    private static final String ALL = "all";

    public NameCommand() {
        super("name", "Sets the name you join offline servers with or saves names for later.",
            "name <name|random|reset|add <name|all>>");
    }

    @Override
    public void execute(String[] args) {
        NameStore store = NameStore.get();
        if (args.length == 0) {
            tell(store, "");
        } else if (args[0].equalsIgnoreCase(ADD)) {
            if (args.length == 2) {
                add(store, args[1]);
            } else {
                usage("name add <name|all>");
            }
        } else if (args.length != 1) {
            usage();
        } else if (args[0].equalsIgnoreCase(RESET)) {
            store.reset();
            tell(store, " from your next join");
        } else {
            use(store, args[0]);
        }
    }

    private static void use(NameStore store, String word) {
        String name = NameStore.named(word);
        if (name == null) {
            ChatUtil.error(NameStore.RULE);
            return;
        }
        store.use(name);
        tell(store, " from your next join");
    }

    // The word all saves every name the server lists. A player who is called all can only be saved
    // by joining as them.
    private static void add(NameStore store, String word) {
        List<String> names = word.equalsIgnoreCase(ALL) ? listed() : new ArrayList<>();
        if (!word.equalsIgnoreCase(ALL)) {
            String name = NameStore.named(word);
            if (name == null) {
                ChatUtil.error(NameStore.RULE);
                return;
            }
            names.add(name);
        }
        int saved = store.add(names);
        if (saved > 0) {
            ChatUtil.message("§7Saved §f" + Tally.counted(saved, "name") + "§7 to the Names tab."
                + (store.isFull() ? " The list is full now." : ""));
        } else if (store.isFull()) {
            ChatUtil.error("The list holds " + NameStore.MOST_SAVED + " names and is full. Forget some first.");
        } else {
            ChatUtil.message(names.isEmpty() ? "§7The server lists no player with a name to save."
                : "§7Nothing to save. The list has " + (names.size() == 1 ? "that name" : "those names") + " already.");
        }
    }

    // Every name in the player list of the server that a join could send.
    private static List<String> listed() {
        List<String> names = new ArrayList<>();
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        if (connection != null) {
            for (PlayerInfo info : connection.getOnlinePlayers()) {
                String name = info.getProfile().name();
                if (NameStore.valid(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static void tell(NameStore store, String when) {
        String line = "§7You join offline servers as §b" + store.joinName() + "§7" + when + ".";
        ChatUtil.message(store.inUse() == null ? line : line + " Online mode servers only take your account name.");
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            List<String> offered = new ArrayList<>(List.of(NameStore.RANDOM, RESET, ADD));
            offered.addAll(NameStore.get().saved());
            return CommandManager.filter(current, offered);
        }
        if (index != 2 || !tokens[1].equalsIgnoreCase(ADD)) {
            return List.of();
        }
        List<String> offered = new ArrayList<>(List.of(ALL));
        offered.addAll(listed());
        return CommandManager.filter(current, offered);
    }
}
