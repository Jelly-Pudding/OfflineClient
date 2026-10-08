package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.NameStore;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;

// The name goes out with the next join. An online mode server checks it against your account and
// turns any other name away.
public final class NameCommand extends Command {

    private static final String RESET = "reset";

    public NameCommand() {
        super("name", "Sets the name you join offline servers with.", "name <name|random|reset>");
    }

    @Override
    public void execute(String[] args) {
        if (args.length > 1) {
            usage();
            return;
        }
        NameStore store = NameStore.get();
        if (args.length == 0) {
            tell(store, "");
            return;
        }
        if (args[0].equalsIgnoreCase(RESET)) {
            store.reset();
        } else {
            String name = NameStore.named(args[0]);
            if (name == null) {
                ChatUtil.error(NameStore.RULE);
                return;
            }
            store.use(name);
        }
        tell(store, " from your next join");
    }

    private static void tell(NameStore store, String when) {
        String line = "§7You join offline servers as §b" + store.joinName() + "§7" + when + ".";
        ChatUtil.message(store.inUse() == null ? line : line + " Online mode servers only take your account name.");
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index != 1) {
            return List.of();
        }
        List<String> offered = new ArrayList<>(List.of(NameStore.RANDOM, RESET));
        offered.addAll(NameStore.get().saved());
        return CommandManager.filter(current, offered);
    }
}
