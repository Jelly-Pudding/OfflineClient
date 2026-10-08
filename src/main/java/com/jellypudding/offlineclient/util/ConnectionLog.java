package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.misc.Conceal;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

// The newest lines of the game log from the start of the last join. The connecting and
// disconnected screens draw them whilst AutoReconnect asks for it.
public final class ConnectionLog extends AbstractAppender {

    private record Line(String text, int colour) {
    }

    private static final String NAME = "OfflineClientConnectionLog";
    private static final int KEPT = 64;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final int MARGIN = 4;
    private static final int GAP = 6;
    private static final int LINE = 10;
    private static final int INFO_COLOUR = 0xFFAAAAAA;
    private static final int WARN_COLOUR = 0xFFFFDD55;
    private static final int ERROR_COLOUR = 0xFFFF7070;

    private static ConnectionLog attached;

    private final Deque<Line> lines = new ArrayDeque<>();

    private ConnectionLog() {
        super(NAME, null, null, true, Property.EMPTY_ARRAY);
    }

    // Called as every join starts. Each join begins with an empty log. A game started with another
    // logging backend has no log to show and still joins.
    public static synchronized void begin() {
        if (attached == null) {
            try {
                ConnectionLog log = new ConnectionLog();
                log.start();
                LoggerContext context = LoggerContext.getContext(false);
                context.getConfiguration().getRootLogger().addAppender(log, Level.INFO, null);
                context.updateLoggers();
                attached = log;
            } catch (RuntimeException e) {
                OfflineClient.LOG.warn("Cannot follow the game log", e);
                return;
            }
        }
        attached.clear();
    }

    // The newest lines that fit between the top of the screen and the bottom given. The oldest of
    // them is drawn at the top.
    public static void draw(GuiGraphicsExtractor graphics, Font font, int width, int bottom) {
        if (Conceal.hides(Conceal.Part.OVERLAY)) {
            return;
        }
        ConnectionLog log;
        synchronized (ConnectionLog.class) {
            log = attached;
        }
        if (log == null) {
            return;
        }
        List<Line> shown = log.newest((bottom - GAP - MARGIN) / LINE);
        int y = MARGIN;
        for (Line line : shown) {
            graphics.text(font, font.plainSubstrByWidth(line.text(), width - MARGIN * 2), MARGIN, y,
                line.colour(), true);
            y += LINE;
        }
    }

    private synchronized void clear() {
        lines.clear();
    }

    private synchronized List<Line> newest(int count) {
        List<Line> newest = new ArrayList<>(lines);
        return count <= 0 ? List.of() : newest.subList(Math.max(0, newest.size() - count), newest.size());
    }

    // Reached from every thread that logs.
    @Override
    public void append(LogEvent event) {
        String message = event.getMessage().getFormattedMessage();
        if (event.getThrown() != null) {
            message += " " + event.getThrown();
        }
        String first = message.lines().findFirst().orElse("");
        String clock = LocalTime.ofInstant(Instant.ofEpochMilli(event.getTimeMillis()), ZoneId.systemDefault())
            .format(CLOCK);
        Line line = new Line(clock + " " + first, colourOf(event.getLevel()));
        synchronized (this) {
            if (lines.size() == KEPT) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }
    }

    private static int colourOf(Level level) {
        if (level.isMoreSpecificThan(Level.ERROR)) {
            return ERROR_COLOUR;
        }
        return level.isMoreSpecificThan(Level.WARN) ? WARN_COLOUR : INFO_COLOUR;
    }
}
