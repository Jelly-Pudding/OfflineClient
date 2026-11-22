package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.modules.misc.FakePlayer;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.util.StringUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FakePlayerCommand extends Command {

    private static final String ADD = "add";
    private static final String REMOVE = "remove";
    private static final String LIST = "list";
    private static final String CLEAR = "clear";
    private static final List<String> VERBS = List.of(ADD, REMOVE, LIST, CLEAR);

    public FakePlayerCommand() {
        super("fakeplayer", "Stands a fake player of any name where you are or takes one away again.",
            "fakeplayer <add|remove|list|clear> [name]", "fp");
    }

    @Override
    public void execute(String[] args) {
        FakePlayer module = Modules.get(FakePlayer.class);
        if (OfflineClient.MC.player == null || module == null) {
            return;
        }
        String verb = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (args.length > 2 || !VERBS.contains(verb)) {
            usage();
            return;
        }
        switch (verb) {
            case ADD -> add(module, args.length == 2 ? args[1] : "");
            case REMOVE -> remove(module, args);
            case LIST -> list(module, args);
            default -> clear(module, args);
        }
    }

    private static void add(FakePlayer module, String name) {
        if (!StringUtil.isValidPlayerName(name)) {
            ChatUtil.error("A player name is at most 16 characters with no spaces.");
            return;
        }
        FakePlayer.Body body = module.add(name);
        if (body != null) {
            ChatUtil.message("§b" + EntityUtil.nameOf(body) + " §7stands at §f" + BlockUtil.text(body.blockPosition()) + "§7.");
        }
    }

    private void remove(FakePlayer module, String[] args) {
        if (args.length != 2) {
            usage("fakeplayer remove <name>");
            return;
        }
        int removed = module.remove(args[1]);
        if (removed == 0) {
            ChatUtil.error("There is no fake player called " + args[1] + ".");
        } else {
            ChatUtil.message("§7Removed §b" + Tally.counted(removed, "fake player") + "§7.");
        }
    }

    private void list(FakePlayer module, String[] args) {
        if (args.length != 1) {
            usage("fakeplayer list");
            return;
        }
        List<FakePlayer.Body> bodies = module.bodies();
        if (bodies.isEmpty()) {
            ChatUtil.message("§7There are no fake players.");
            return;
        }
        for (FakePlayer.Body body : bodies) {
            ChatUtil.message("§b" + EntityUtil.nameOf(body) + " §7at §f" + BlockUtil.text(body.blockPosition())
                + " §7with §f" + EntityUtil.hearts(EntityUtil.totalHealth(body)));
        }
    }

    private void clear(FakePlayer module, String[] args) {
        if (args.length != 1) {
            usage("fakeplayer clear");
            return;
        }
        int standing = module.bodies().size();
        module.setEnabled(false);
        ChatUtil.message(standing == 0 ? "§7There are no fake players."
            : "§7Removed §b" + Tally.counted(standing, "fake player") + "§7.");
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, VERBS);
        }
        FakePlayer module = Modules.get(FakePlayer.class);
        if (index != 2 || module == null || !tokens[1].equalsIgnoreCase(REMOVE)) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        module.bodies().forEach(body -> names.add(EntityUtil.nameOf(body)));
        return CommandManager.filter(current, new ArrayList<>(names));
    }
}
