package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;

public final class SimulationDistanceCommand extends Command {

    private static final int MIN_CHUNKS = 5;
    private static final int MAX_CHUNKS = 32;

    public SimulationDistanceCommand() {
        super("simulationdistance", "Sets how far the world keeps ticking around you.",
            "simulationdistance [chunks]", "sv");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            ChatUtil.message("§7Simulation distance is §b"
                + OfflineClient.MC.options.simulationDistance().get() + " §7chunks.");
            return;
        }
        if (args.length != 1) {
            usage();
            return;
        }
        int chunks;
        try {
            chunks = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            ChatUtil.error("That is not a whole number.");
            return;
        }
        if (chunks < MIN_CHUNKS || chunks > MAX_CHUNKS) {
            ChatUtil.error("Pick between " + MIN_CHUNKS + " and " + MAX_CHUNKS + " chunks.");
            return;
        }
        OfflineClient.MC.options.simulationDistance().set(chunks);
        ChatUtil.message("§7Simulation distance is now §b" + chunks + " §7chunks.");
    }
}
