package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.elements.ClientLinesElement;
import com.jellypudding.offlineclient.modules.misc.Conceal;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

// The client's own chat lines whilst the Client lines piece of the HUD is on and takes them out
// of the chat. Every line is written and read on the game thread.
public final class ClientLines {

    public record Line(Component text, long at) {
    }

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

    public static boolean replying() {
        return commands > 0;
    }

    // Runs a reply on the game thread once its command has returned. It stays in the chat like the
    // lines the command wrote straight away.
    public static void inChatLater(Runnable reply) {
        OfflineClient.MC.execute(() -> inChat(reply));
    }

    // Keeps the line for the panel whilst its piece of the HUD shows. False leaves it to the chat.
    // A line with a link in it stays there to be clicked.
    public static boolean take(Component text) {
        if (commands > 0 || !panelShows() || clickable(text)) {
            return false;
        }
        // The chat hides names once as a line comes in and the panel keeps its lines the same way.
        LINES.addFirst(new Line(EntityUtil.displayed(text), System.currentTimeMillis()));
        if (LINES.size() > KEPT) {
            LINES.removeLast();
        }
        return true;
    }

    // With the piece or the whole HUD off or Conceal hiding the HUD nothing would draw the line.
    private static boolean panelShows() {
        return Modules.hudShows(ClientLinesElement.class) && !Conceal.hides(Conceal.Part.OVERLAY);
    }

    private static boolean clickable(Component text) {
        return text.visit((style, part) -> Optional.ofNullable(style.getClickEvent()), Style.EMPTY).isPresent();
    }

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
