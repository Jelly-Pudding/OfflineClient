package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatPace;
import com.jellypudding.offlineclient.util.LiveText;
import com.jellypudding.offlineclient.util.SentLine;

// Sends text the client would otherwise read as one of its own commands.
public final class SayCommand extends Command {

    public SayCommand() {
        super("say", "Sends chat straight to the server. Words in braces such as {x} are filled in. It knows "
            + LiveText.WORDS + ".", "say <message>");
    }

    @Override
    public void execute(String[] args) {
        if (OfflineClient.MC.player == null || args.length == 0) {
            usage();
            return;
        }
        ChatPace.PLAYER.say(words(args, 0), SentLine::live);
    }
}
