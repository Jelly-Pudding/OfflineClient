package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.gui.FindsScreen;
import com.jellypudding.offlineclient.modules.hunting.BookReader;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;

import java.util.List;
import java.util.Locale;

public final class BookCommand extends Command {

    private static final String READ = "read";
    private static final String COPY = "copy";

    public BookCommand() {
        super("book", "Reads or copies a book BookReader found or lists them all.", "book [read|copy <id>]");
    }

    @Override
    public void execute(String[] args) {
        BookReader reader = Modules.get(BookReader.class);
        if (OfflineClient.MC.player == null || reader == null) {
            return;
        }
        if (args.length == 0) {
            FindsScreen.open(reader.findLog());
            return;
        }
        if (args.length != 2) {
            usage();
            return;
        }
        String said = switch (args[0].toLowerCase(Locale.ROOT)) {
            case READ -> reader.read(args[1]);
            case COPY -> reader.copy(args[1]);
            default -> {
                usage();
                yield null;
            }
        };
        if (said != null) {
            ChatUtil.message("§7" + said);
        }
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 1 ? CommandManager.filter(current, List.of(READ, COPY)) : List.of();
    }
}
