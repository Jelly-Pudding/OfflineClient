package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;

public final class PrefixCommand extends Command {

    public PrefixCommand() {
        super("prefix", "Changes the command prefix.", "prefix <newprefix>");
    }

    @Override
    public void execute(String[] args) {
        if (args.length != 1) {
            usage();
            return;
        }
        if (!OfflineClient.INSTANCE.getCommandManager().setPrefix(args[0])) {
            ChatUtil.error("A prefix cannot be empty and cannot start with a slash.");
            return;
        }
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
        ChatUtil.message("§7Prefix set to §b" + OfflineClient.INSTANCE.getCommandManager().getPrefix());
    }
}
