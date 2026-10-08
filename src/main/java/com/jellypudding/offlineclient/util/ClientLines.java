package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.misc.BetterChat;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

// The client's own chat lines whilst BetterChat sends them to a piece of the HUD of their own.
// Every line is written and read on the game thread.
public final class ClientLines {

    // One line and when it came on the wall clock.
    public record Line(Component text, long at) {
    }

    // Far more than any panel shows at once.
    private static final int KEPT = 100;

    // The newest first.
    private static final Deque<Line> LINES = new ArrayDeque<>();

    // How many commands or later replies to them are running. A command may run another.
    private static int commands;

    private ClientLines() {
    }

    // Runs a command or a reply to one that comes later. What it writes stays in the chat where
    // the history keeps it.
    public static void inChat(Runnable action) {
        commands++;
        try {
            action.run();
        } finally {
            commands--;
        }
    }

    // True whilst a command or a later reply to one runs.
    public static boolean replying() {
        return commands > 0;
    }

    // Runs a reply on the game thread once its command has returned. It stays in the chat like the
    // lines the command wrote straight away.
    public static void inChatLater(Runnable reply) {
        OfflineClient.MC.execute(() -> inChat(reply));
    }

    // Keeps the line for the panel whilst BetterChat splits the client's lines from the chat.
    // False leaves it to the chat. A line with a link in it stays there to be clicked.
    public static boolean take(Component text) {
        BetterChat betterChat = Modules.get(BetterChat.class);
        if (commands > 0 || betterChat == null || !betterChat.splitsClientLines() || clickable(text)) {
            return false;
        }
        LINES.addFirst(new Line(text, System.currentTimeMillis()));
        if (LINES.size() > KEPT) {
            LINES.removeLast();
        }
        return true;
    }

    private static boolean clickable(Component text) {
        return text.visit((style, part) -> Optional.ofNullable(style.getClickEvent()), Style.EMPTY).isPresent();
    }

    // Up to this many of the newest lines with the newest first.
    public static List<Line> newest(int count) {
        List<Line> newest = new ArrayList<>(Math.min(count, LINES.size()));
        for (Line line : LINES) {
            if (newest.size() >= count) {
                break;
            }
            newest.add(line);
        }
        return newest;
    }

    // Takes the newest line the test accepts out.
    public static void removeRecent(Predicate<String> text) {
        Iterator<Line> it = LINES.iterator();
        while (it.hasNext()) {
            if (text.test(it.next().text().getString())) {
                it.remove();
                return;
            }
        }
    }
}
