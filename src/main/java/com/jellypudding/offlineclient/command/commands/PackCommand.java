package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.modules.misc.ServerSpoof;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.Tally;

import java.util.UUID;

// The chat line about a held back pack runs this with the id of that pack when its link is clicked.
public final class PackCommand extends Command {

    public PackCommand() {
        super("pack", "Loads a server pack ServerSpoof held back or every one without an id. The server is told"
            + " nothing more.", "pack [id]");
    }

    @Override
    public void execute(String[] args) {
        if (args.length > 1) {
            usage();
            return;
        }
        UUID only = null;
        if (args.length == 1) {
            try {
                only = UUID.fromString(args[0]);
            } catch (IllegalArgumentException e) {
                usage();
                return;
            }
        }
        ServerSpoof spoof = Modules.active(ServerSpoof.class);
        int packs = spoof == null ? 0 : spoof.loadHeld(only);
        if (packs == 0) {
            ChatUtil.error(only == null ? "ServerSpoof holds no server pack back."
                : "ServerSpoof does not hold that pack back.");
        } else {
            ChatUtil.message("§7Loading " + Tally.counted(packs, "server pack")
                + " on your side. The server is told nothing more.");
        }
    }
}
