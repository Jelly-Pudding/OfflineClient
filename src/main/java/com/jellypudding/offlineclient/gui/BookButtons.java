package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.BookText;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class BookButtons {

    private static final int WIDTH = 50;
    private static final int LABEL_PAD = 6;
    private static final int GAP = 4;
    private static final int TOP = 18;
    private static final int TITLE_TOP = 50;

    private static final int SWATCH = 13;
    private static final int SWATCH_PITCH = SWATCH + 1;
    private static final int SWATCHES_ACROSS = 4;

    private static String lastTitle;

    private BookButtons() {
    }

    private static FlashButton beside(Screen screen, int top, String label, String tip,
                                      Consumer<FlashButton> press) {
        int width = Math.max(WIDTH, OfflineClient.MC.font.width(label) + 2 * LABEL_PAD);
        return new FlashButton((screen.width + BookEditScreen.IMAGE_WIDTH) / 2 + GAP, top, width,
            Button.DEFAULT_HEIGHT, Component.literal(label), tip, press);
    }

    public static Button copy(Screen screen, Supplier<List<String>> pages) {
        return beside(screen, TOP, "Copy", "Copies every page to the clipboard. A line holding only "
            + BookText.PAGE_MARK + " stands between one page and the next.", button -> {
                OfflineClient.MC.keyboardHandler.setClipboard(BookText.joined(pages.get()));
                button.flash("Copied");
            });
    }

    public static Button paste(Screen screen, Supplier<String> press) {
        return beside(screen, TOP + Button.DEFAULT_HEIGHT + GAP, "Paste", "Puts the text on the clipboard into the"
            + " book from this page on. A line holding only " + BookText.PAGE_MARK + " starts a new page. What the"
            + " book or the server has no room for is left off the end.", button -> button.flash(press.get()));
    }

    public static void signed(String title) {
        if (!title.isBlank()) {
            lastTitle = title;
        }
    }

    public static Button sameTitle(Screen screen, Consumer<String> fill) {
        String title = lastTitle;
        if (title == null) {
            return null;
        }
        return beside(screen, TITLE_TOP, "Same title", "Titles this book " + title
            + " like the last book you signed.", button -> fill.accept(title));
    }

    public static BookText.Wrap wrap(Font font) {
        return text -> {
            List<int[]> lines = new ArrayList<>();
            font.getSplitter().splitLines(text, BookEditScreen.TEXT_WIDTH, Style.EMPTY, false,
                (style, start, end) -> lines.add(new int[] {start, end}));
            return lines;
        };
    }

    public static List<Button> palette(Screen screen, Predicate<ChatFormatting> insert) {
        List<Button> buttons = new ArrayList<>();
        int left = (screen.width - BookEditScreen.IMAGE_WIDTH) / 2 - GAP - SWATCHES_ACROSS * SWATCH_PITCH;
        for (ChatFormatting format : ChatFormatting.values()) {
            int index = buttons.size();
            String words = format.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            // A colour shows as a coloured A and a style as its first letter such as B for bold.
            String letter = format.compareTo(ChatFormatting.WHITE) <= 0 ? "A" : format.name().substring(0, 1);
            String tip = format == ChatFormatting.RESET ? "Ends every colour and style from the caret on."
                : "Makes the text " + words + " from the caret on.";
            buttons.add(new FlashButton(left + index % SWATCHES_ACROSS * SWATCH_PITCH,
                TOP + index / SWATCHES_ACROSS * SWATCH_PITCH, SWATCH, SWATCH,
                Component.literal(letter).withStyle(format), tip, button -> {
                    if (!insert.test(format)) {
                        button.flash("No room on this page.");
                    }
                }));
        }
        return buttons;
    }
}
