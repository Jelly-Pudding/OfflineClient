package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.KeyedCommand;
import com.jellypudding.offlineclient.config.LoadoutStore;
import com.jellypudding.offlineclient.config.LoadoutStore.Loadout;
import com.jellypudding.offlineclient.modules.player.Loadouts;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;

import java.util.List;
import java.util.Map;

// The layouts Loadouts puts back. A key only works whilst Loadouts is on.
public final class LoadoutCommand extends KeyedCommand<Loadout> {

    public LoadoutCommand() {
        super("loadout", "Saves your inventory layout under a name and puts it back later.", "loadout",
            List.of("save", "load"), "[name] [key]", "kit");
    }

    @Override
    protected LoadoutStore store() {
        return LoadoutStore.get();
    }

    @Override
    protected void runOwn(String verb, String[] args) {
        switch (verb) {
            case "save" -> save(args);
            case "load" -> load(args);
            default -> usage();
        }
    }

    @Override
    protected String summary(Loadout loadout) {
        return loadout.slots().size() + " slots";
    }

    @Override
    protected String keyedMessage(Loadout loadout, String keyName) {
        return "§b" + loadout.name() + " §7goes back on §b" + keyName + "§7 whilst Loadouts is on.";
    }

    private void save(String[] args) {
        if (args.length != 2) {
            usage("loadout save <name>");
            return;
        }
        Loadouts module = Modules.get(Loadouts.class);
        if (module == null || OfflineClient.MC.player == null) {
            ChatUtil.error("Join a world first.");
            return;
        }
        Map<Integer, String> layout = module.currentLayout();
        if (layout.isEmpty()) {
            ChatUtil.error("Your inventory is empty.");
            return;
        }
        store().add(new Loadout(args[1], store().keyOf(args[1]), layout));
        ChatUtil.message("§b" + args[1] + " §7saved with §b" + layout.size() + " §7slots.");
    }

    private void load(String[] args) {
        if (args.length != 2) {
            usage("loadout load <name>");
            return;
        }
        Loadout loadout = find(args[1]);
        if (loadout == null) {
            return;
        }
        Loadouts module = Modules.get(Loadouts.class);
        if (module == null || OfflineClient.MC.player == null) {
            ChatUtil.error("Join a world first.");
            return;
        }
        module.restore(loadout);
    }
}
