package com.jellypudding.offlineclient.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * One line of editable text with a caret and a selection. Every place the
 * client takes typed input routes through this so the editing keys and the
 * clipboard behave the same everywhere.
 */
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
        if (event.isSelectAll()) {
            selectAll();
            return true;
        }
        if (event.isCopy()) {
            copySelection();
            return true;
        }
        if (event.isCut()) {
            copySelection();
            deleteSelection();
            return true;
        }
        if (event.isPaste()) {
            insert(Minecraft.getInstance().keyboardHandler.getClipboard(), filter);
            return true;
        }

        boolean shift = event.hasShiftDown();
        boolean word = event.hasControlDown();
        switch (event.key()) {
            case GLFW.GLFW_KEY_LEFT -> {
                if (!shift && hasSelection()) {
                    moveTo(selStart(), false);
                } else {
                    moveTo(word ? wordLeft() : caret - 1, shift);
                }
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (!shift && hasSelection()) {
                    moveTo(selEnd(), false);
                } else {
                    moveTo(word ? wordRight() : caret + 1, shift);
                }
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                moveTo(0, shift);
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                moveTo(buffer.length(), shift);
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (!deleteSelection() && caret > 0) {
                    int from = word ? wordLeft() : caret - 1;
                    buffer.delete(from, caret);
                    moveTo(from, false);
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (!deleteSelection() && caret < buffer.length()) {
                    buffer.delete(caret, word ? wordRight() : caret + 1);
                    anchor = caret;
                }
                return true;
            }
            default -> {
                return false;
            }
        }
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
            int next = font.width(buffer.substring(firstVisible, position + 1));
            if (next > target) {
                int here = font.width(buffer.substring(firstVisible, position));
                if (target - here > next - target) {
                    position++;
                }
                break;
            }
            position++;
        }
        moveTo(position, extend);
    }

    /**
     * Draws the visible run of text with the selection behind it and the caret
     * on top. Returns the width the text took so a caller can lay out after it.
     */
    public int render(GuiGraphicsExtractor context, Font font, int x, int y, int room,
                      int color, boolean focused) {
        ensureVisible(font, room);
        String visible = font.plainSubstrByWidth(buffer.substring(firstVisible), room);
        int end = firstVisible + visible.length();

        if (hasSelection()) {
            int from = Math.clamp(selStart(), firstVisible, end);
            int to = Math.clamp(selEnd(), firstVisible, end);
            if (to > from) {
                int left = x + font.width(buffer.substring(firstVisible, from));
                int right = x + font.width(buffer.substring(firstVisible, to));
                context.fill(left, y - 1, right, y + GuiTheme.TEXT_HEIGHT + 1,
                    GuiTheme.accentOn(GuiTheme.BG_PANEL, 0.45f));
            }
        }

        context.text(font, visible, x, y, color, false);

        if (focused && (System.currentTimeMillis() / BLINK_MS) % 2 == 0) {
            int caretX = x + font.width(buffer.substring(firstVisible,
                Math.clamp(caret, firstVisible, end)));
            context.fill(caretX, y - 1, caretX + 1, y + GuiTheme.TEXT_HEIGHT + 1,
                GuiTheme.accentText());
        }
        return font.width(visible);
    }

    // Scrolls the window so the caret sits inside it and no room is wasted on the right.
    private void ensureVisible(Font font, int room) {
        firstVisible = Math.clamp(firstVisible, 0, buffer.length());
        if (firstVisible > caret) {
            firstVisible = caret;
        }
        while (firstVisible < caret
            && font.width(buffer.substring(firstVisible, caret)) > room) {
            firstVisible++;
        }
        while (firstVisible > 0 && font.width(buffer.substring(firstVisible - 1)) <= room) {
            firstVisible--;
        }
    }
}
