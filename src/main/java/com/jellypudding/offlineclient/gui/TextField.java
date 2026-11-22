package com.jellypudding.offlineclient.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.Mth;

public final class TextField {

    @FunctionalInterface
    public interface Filter {
        boolean accepts(char c);
    }

    public static final Filter ANY = c -> c >= ' ' && c != 127;

    public static final Filter NUMBER = c -> (c >= '0' && c <= '9') || c == '.' || c == '-';

    private static final long BLINK_MS = 500;

    private final StringBuilder buffer = new StringBuilder();

    private int caret;

    // Equal to the caret means nothing is selected.
    private int anchor;

    private int firstVisible;

    // Measuring a substring for every character on every frame is what makes a long value crawl.
    private int[] widths = {0};
    private String measured = "";
    private Font measuredIn;

    public String get() {
        return buffer.toString();
    }

    public boolean isEmpty() {
        return buffer.isEmpty();
    }

    public void set(String value) {
        buffer.setLength(0);
        buffer.append(value);
        caret = buffer.length();
        anchor = caret;
        firstVisible = 0;
    }

    public void clear() {
        set("");
    }

    public void selectAll() {
        anchor = 0;
        caret = buffer.length();
    }

    private boolean hasSelection() {
        return caret != anchor;
    }

    private int selStart() {
        return Math.min(caret, anchor);
    }

    private int selEnd() {
        return Math.max(caret, anchor);
    }

    public boolean keyPressed(KeyEvent event, Filter filter) {
        return clipboard(event, filter) || navigate(event) || erase(event);
    }

    private boolean clipboard(KeyEvent event, Filter filter) {
        if (event.isSelectAll()) {
            selectAll();
        } else if (event.isCopy()) {
            copySelection();
        } else if (event.isCut()) {
            copySelection();
            deleteSelection();
        } else if (event.isPaste()) {
            insert(Minecraft.getInstance().keyboardHandler.getClipboard(), filter);
        } else {
            return false;
        }
        return true;
    }

    private boolean navigate(KeyEvent event) {
        boolean shift = event.hasShiftDown();
        boolean word = event.hasControlDown();
        switch (event.key()) {
            case InputConstants.KEY_LEFT -> {
                // An unshifted arrow collapses a selection. The caret
                // lands on the near end.
                if (!shift && hasSelection()) {
                    moveTo(selStart(), false);
                } else {
                    moveTo(word ? wordLeft() : caret - 1, shift);
                }
            }
            case InputConstants.KEY_RIGHT -> {
                if (!shift && hasSelection()) {
                    moveTo(selEnd(), false);
                } else {
                    moveTo(word ? wordRight() : caret + 1, shift);
                }
            }
            case InputConstants.KEY_HOME -> moveTo(0, shift);
            case InputConstants.KEY_END -> moveTo(buffer.length(), shift);
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean erase(KeyEvent event) {
        boolean word = event.hasControlDown();
        switch (event.key()) {
            case InputConstants.KEY_BACKSPACE -> {
                if (!deleteSelection() && caret > 0) {
                    int from = word ? wordLeft() : caret - 1;
                    buffer.delete(from, caret);
                    moveTo(from, false);
                }
            }
            case InputConstants.KEY_DELETE -> {
                if (!deleteSelection() && caret < buffer.length()) {
                    buffer.delete(caret, word ? wordRight() : caret + 1);
                    anchor = caret;
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    public boolean charTyped(char c, Filter filter) {
        if (!filter.accepts(c)) {
            return false;
        }
        deleteSelection();
        buffer.insert(caret, c);
        moveTo(caret + 1, false);
        return true;
    }

    private void insert(String value, Filter filter) {
        if (value == null || value.isEmpty()) {
            return;
        }
        deleteSelection();
        StringBuilder kept = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (filter.accepts(c)) {
                kept.append(c);
            }
        }
        buffer.insert(caret, kept);
        moveTo(caret + kept.length(), false);
    }

    private void copySelection() {
        if (hasSelection()) {
            Minecraft.getInstance().keyboardHandler
                .setClipboard(buffer.substring(selStart(), selEnd()));
        }
    }

    private boolean deleteSelection() {
        if (!hasSelection()) {
            return false;
        }
        int start = selStart();
        buffer.delete(start, selEnd());
        moveTo(start, false);
        return true;
    }

    private void moveTo(int position, boolean extend) {
        caret = Math.clamp(position, 0, buffer.length());
        if (!extend) {
            anchor = caret;
        }
    }

    private int wordLeft() {
        int i = caret;
        while (i > 0 && buffer.charAt(i - 1) == ' ') {
            i--;
        }
        while (i > 0 && buffer.charAt(i - 1) != ' ') {
            i--;
        }
        return i;
    }

    private int wordRight() {
        int i = caret;
        while (i < buffer.length() && buffer.charAt(i) != ' ') {
            i++;
        }
        while (i < buffer.length() && buffer.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    public void click(Font font, int x, int room, double mouseX, boolean extend) {
        ensureVisible(font, room);
        int target = (int) Math.round(mouseX - x);
        int position = firstVisible;
        while (position < buffer.length()) {
            int next = width(firstVisible, position + 1);
            if (next > target) {
                if (target - width(firstVisible, position) > next - target) {
                    position++;
                }
                break;
            }
            position++;
        }
        moveTo(position, extend);
    }

    public int drawnWidth(Font font, int room) {
        ensureVisible(font, room);
        return width(firstVisible, lastFitting(firstVisible, room));
    }

    public void render(GuiGraphicsExtractor context, Font font, int x, int y, int room,
                       int color, boolean focused) {
        ensureVisible(font, room);
        int end = lastFitting(firstVisible, room);
        String visible = buffer.substring(firstVisible, end);

        if (hasSelection()) {
            int from = Math.clamp(selStart(), firstVisible, end);
            int to = Math.clamp(selEnd(), firstVisible, end);
            if (to > from) {
                int left = x + width(firstVisible, from);
                int right = x + width(firstVisible, to);
                context.fill(left, y - 1, right, y + GuiTheme.TEXT_HEIGHT + 1,
                    GuiTheme.accentOn(GuiTheme.bgPanel(), 0.45f));
            }
        }

        context.text(font, visible, x, y, color, false);

        if (focused && (System.currentTimeMillis() / BLINK_MS) % 2 == 0) {
            int caretX = x + width(firstVisible, Math.clamp(caret, firstVisible, end));
            context.fill(caretX, y - 1, caretX + 1, y + GuiTheme.TEXT_HEIGHT + 1,
                GuiTheme.accentText());
        }
    }

    private void ensureVisible(Font font, int room) {
        measure(font);
        firstVisible = Math.clamp(firstVisible, 0, buffer.length());
        if (firstVisible > caret) {
            firstVisible = caret;
        }
        while (firstVisible < caret && width(firstVisible, caret) > room) {
            firstVisible++;
        }
        while (firstVisible > 0 && width(firstVisible - 1, buffer.length()) <= room) {
            firstVisible--;
        }
    }

    private int lastFitting(int from, int room) {
        int end = from;
        while (end < buffer.length() && width(from, end + 1) <= room) {
            end++;
        }
        return end;
    }

    private int width(int from, int to) {
        return widths[to] - widths[from];
    }

    private void measure(Font font) {
        String text = buffer.toString();
        if (text.equals(measured) && font == measuredIn) {
            return;
        }
        measured = text;
        measuredIn = font;
        widths = letterEdges(font, text);
    }

    // A letter of a smooth font is not a whole pixel wide. Adding up the widths of single letters
    // would drift away from the text drawn in one piece.
    private static int[] letterEdges(Font font, String text) {
        int[] edges = new int[text.length() + 1];
        float edge = 0;
        for (int i = 0; i < text.length(); i++) {
            edge += font.getSplitter().stringWidth(String.valueOf(text.charAt(i)));
            edges[i + 1] = Mth.ceil(edge);
        }
        return edges;
    }
}
