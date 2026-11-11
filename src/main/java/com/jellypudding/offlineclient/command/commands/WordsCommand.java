package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.LiveText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// The help for the live words. Every text that takes them points here.
public final class WordsCommand extends Command {

    public WordsCommand() {
        super("words", "Lists the live words such as {fps} that a custom text or a sign or a sent line can hold.",
            "words [word]", "livewords");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            list();
        } else if (args.length == 1) {
            describe(args[0]);
        } else {
            usage();
        }
    }

    // One line for each group with its words as they are typed.
    private static void list() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (LiveText.Help word : LiveText.help()) {
            groups.computeIfAbsent(word.group(), group -> new ArrayList<>()).add(word.pattern());
        }
        groups.forEach((group, patterns) -> ChatUtil.message("§3" + group + " §f" + String.join(" ", patterns)));
        ChatUtil.message("§7Type §f" + OfflineClient.INSTANCE.getCommandManager().getPrefix()
            + "words health§7 to read what a word shows. A number takes its decimals such as §f{speed 2}§7.");
    }

    // The braces may be typed or left off.
    private static void describe(String typed) {
        String name = typed.replace("{", "").replace("}", "");
        for (LiveText.Help word : LiveText.help()) {
            if (word.name().equalsIgnoreCase(name)) {
                ChatUtil.message("§b" + word.pattern() + " §7" + word.says() + (word.figure()
                    ? " A number after it sets its decimals such as {" + word.name() + " 2}." : ""));
                return;
            }
        }
        ChatUtil.error("There is no live word called " + name + ".");
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index != 1) {
            return List.of();
        }
        return CommandManager.filter(current, LiveText.help().stream().map(LiveText.Help::name).toList());
    }
}
