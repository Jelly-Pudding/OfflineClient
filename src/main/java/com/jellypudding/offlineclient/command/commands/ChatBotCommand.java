package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.ServerStore;
import com.jellypudding.offlineclient.modules.misc.ChatBot;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public final class ChatBotCommand extends Command {

    private static final String KEY = "key";
    private static final String FORGET = "forget";
    private static final String KEYS = "keys";

    public ChatBotCommand() {
        super("chatbot", "Saves the key a chat model service asks for.", "chatbot <key|forget|keys> [key|host]");
    }

    @Override
    public void execute(String[] args) {
        ChatBot chatBot = Modules.get(ChatBot.class);
        if (args.length == 0 || chatBot == null) {
            usage();
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case KEY -> key(chatBot, args);
            case FORGET -> forgetKey(chatBot, args);
            case KEYS -> list();
            default -> usage();
        }
    }

    private void key(ChatBot chatBot, String[] args) {
        if (args.length != 2) {
            usage("chatbot key <key>");
            return;
        }
        String host = chatBot.modelHost();
        if (host == null) {
            ChatUtil.error("Type the model address in the ChatBot settings first.");
            return;
        }
        String key = args[1];
        // A web request carries plain letters and signs only.
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(key)) {
            ChatUtil.error("That key holds a letter a web request cannot carry.");
            return;
        }
        ServerStore.modelKeys().set(host, key);
        ChatUtil.message("§7Saved a key for §b" + host + "§7.");
        forgetTypedLine(key);
    }

    private void forgetKey(ChatBot chatBot, String[] args) {
        if (args.length > 2) {
            usage("chatbot forget [host]");
            return;
        }
        String host = args.length == 2 ? args[1].toLowerCase(Locale.ROOT) : chatBot.modelHost();
        if (host == null) {
            usage("chatbot forget <host>");
            return;
        }
        forget(ServerStore.modelKeys(), host, "key");
    }

    private static void list() {
        List<String> hosts = ServerStore.modelKeys().servers();
        if (hosts.isEmpty()) {
            ChatUtil.message("§7No keys are saved.");
            return;
        }
        ChatUtil.message("§3Keys saved for §b" + String.join("§7 §b", hosts));
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, List.of(KEY, FORGET, KEYS));
        }
        if (index == 2 && tokens[1].equalsIgnoreCase(FORGET)) {
            return CommandManager.filter(current, ServerStore.modelKeys().servers());
        }
        return List.of();
    }
}
