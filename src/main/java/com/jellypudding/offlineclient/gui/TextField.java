package com.jellypudding.offlineclient.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

// One line of editable text with a caret and a selection. Every place the
// client routes typed input through this for the same editing and clipboard keys.
public final class TextField {

    @FunctionalInterface
    public interface Filter {
        boolean accepts(char c);
    }

    // Anything printable.
    public static final Filter ANY = c -> c >= ' ' && c != 127;

    // Digits and the pieces of a decimal number.
    public static final Filter NUMBER = c -> (c >= '0' && c <= '9') || c == '.' || c == '-';

    private static final long BLINK_MS = 500;

    private final StringBuilder buffer = new StringBuilder();

    private int caret;

    // The fixed end of the selection. Equal to the caret means nothing is selected.
    private int anchor;

    // Index of the leftmost drawn character. Keeps a long value scrolled to the caret.
    private int firstVisible;

    // Pixels from the start of the text to each index. Measuring a substring for every
    // character on every frame is what makes a long value crawl.
    private int[] widths = {0};
    private String measured = "";

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

    public boolean hasSelection() {
        return caret != anchor;
    }

    private int selStart() {
        return Math.min(caret, anchor);
    }

    private int selEnd() {
        return Math.max(caret, anchor);
    }

    // True when the key belonged to this field.
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

    // Shift extends the selection and control moves a word at a time.
    private boolean navigate(KeyEvent event) {
        boolean shift = event.hasShiftDown();
        boolean word = event.hasControlDown();
        switch (event.key()) {
            case GLFW.GLFW_KEY_LEFT -> {
                // An unshifted arrow collapses a selection. The caret
                // lands on the near end.
                if (!shift && hasSelection()) {
                    moveTo(selStart(), false);
                } else {
                    moveTo(word ? wordLeft() : caret - 1, shift);
                }
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (!shift && hasSelection()) {
                    moveTo(selEnd(), false);
                } else {
                    moveTo(word ? wordRight() : caret + 1, shift);
                }
            }
            case GLFW.GLFW_KEY_HOME -> moveTo(0, shift);
            case GLFW.GLFW_KEY_END -> moveTo(buffer.length(), shift);
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean erase(KeyEvent event) {
        boolean word = event.hasControlDown();
        switch (event.key()) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (!deleteSelection() && caret > 0) {
                    int from = word ? wordLeft() : caret - 1;
                    buffer.delete(from, caret);
                    moveTo(from, false);
                }
            }
            case GLFW.GLFW_KEY_DELETE -> {
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

    public void insert(String value, Filter filter) {
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

    // Skips the run of spaces then the word before it.
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

    // Puts the caret where the pointer is. Shift keeps the far end of the selection.
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

    // Draws the visible run of text with the selection behind it and the
    // caret on top.
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

    // Scrolls the window to keep the caret inside it and waste no room on the right.
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

    // The index one past the last character that still fits in the room given.
    private int lastFitting(int from, int room) {
        int end = from;
        while (end < buffer.length() && width(from, end + 1) <= room) {
            end++;
        }
        return end;
    }

    // Pixels between two indexes of the text.
    private int width(int from, int to) {
        return widths[to] - widths[from];
    }

    private void measure(Font font) {
        String text = buffer.toString();
        if (text.equals(measured)) {
            return;
        }
        measured = text;
        widths = new int[text.length() + 1];
        for (int i = 0; i < text.length(); i++) {
            widths[i + 1] = widths[i] + font.width(String.valueOf(text.charAt(i)));
        }
    }
}
