package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;

public final class RenderDistanceCommand extends Command {

    private static final int MIN_CHUNKS = 2;
    private static final int MAX_CHUNKS = 64;

    public RenderDistanceCommand() {
        super("renderdistance", "Sets the render distance past the vanilla slider.",
            "renderdistance [chunks]", "rd");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            ChatUtil.message("§7Render distance is §b"
                + OfflineClient.MC.options.renderDistance().get() + " §7chunks.");
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
        OfflineClient.MC.options.renderDistance().set(chunks);
        ChatUtil.message("§7Render distance is now §b" + chunks + " §7chunks.");
    }
}
