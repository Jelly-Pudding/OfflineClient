package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.PlayerLedger;
import com.jellypudding.offlineclient.config.PlayerRecord;
import com.jellypudding.offlineclient.config.PlayerRecord.Fact;
import com.jellypudding.offlineclient.config.PlayerRecord.Place;
import com.jellypudding.offlineclient.gui.DossierScreen;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

// The records Dossier keeps of every player on this server. The bare name toggles the module
// like any other.
public final class DossierCommand extends Command {

    private static final String OPEN = "open";
    // How many of the newest lines of the log a record in chat shows.
    private static final int LOG_LINES = 5;

    public DossierCommand() {
        super("dossier", "Opens the player records or shows one player in chat.", "dossier <name|open>");
    }

    @Override
    public void execute(String[] args) {
        if (args.length != 1) {
            usage();
            return;
        }
        if (args[0].equalsIgnoreCase(OPEN)) {
            DossierScreen.open();
            return;
        }
        PlayerLedger ledger = PlayerLedger.INSTANCE;
        if (!ledger.ready()) {
            ChatUtil.error("The player records are still being read.");
            return;
        }
        PlayerRecord record = ledger.named(args[0]);
        if (record == null) {
            ChatUtil.error("Nobody called " + args[0] + " is on record on this server.");
            return;
        }
        long now = System.currentTimeMillis();
        ChatUtil.message("§3Dossier §b" + record.name());
        for (Fact fact : record.facts(PlayerLedger.live(record.id()), now)) {
            line(fact, "§f");
        }
        List<Fact> log = record.history(now);
        for (Fact fact : log.subList(0, Math.min(LOG_LINES, log.size()))) {
            line(fact, "§7");
        }
    }

    private static void line(Fact fact, String color) {
        MutableComponent text = Component.literal(color + fact.text());
        Place place = fact.place();
        if (place != null) {
            text.append(" ").append(ChatUtil.walkLink(place.pos(), place.dimension()));
        }
        if (!fact.detail().isEmpty()) {
            text.append("§8 · §7" + fact.detail());
        }
        ChatUtil.component(text);
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>(List.of(OPEN));
        for (PlayerRecord record : PlayerLedger.INSTANCE.all()) {
            options.add(record.name());
        }
        return CommandManager.filter(current, options);
    }
}
