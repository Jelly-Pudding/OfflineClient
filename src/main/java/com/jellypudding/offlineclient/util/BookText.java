package com.jellypudding.offlineclient.util;

import net.minecraft.ChatFormatting;
import net.minecraft.world.item.component.WritableBookContent;

import java.util.ArrayList;
import java.util.List;

// The pages of a book as one piece of text for the clipboard and such text cut back into pages.
// A paste never makes a book the game cannot send or a Paper server kicks for.
public final class BookText {

    // A line holding only this starts a new page.
    public static final String PAGE_MARK = "<page>";

    // Lines of text on one page of the book and quill screen.
    public static final int PAGE_LINES = 14;

    // The most a copy holds. A written book can carry far more than anyone reads.
    private static final int MOST_COPIED = 1 << 20;

    // The most text a book has room for with a mark line for every page. Nothing past it is read.
    private static final int MOST_READ = WritableBookContent.MAX_PAGES
        * (WritableBookContent.PAGE_EDIT_LENGTH + PAGE_MARK.length() + 2);

    private static final char SPACE = ' ';
    private static final char DELETE = 127;

    private BookText() {
    }

    // Where each line of a text starts and ends once it is wrapped to the width of a page.
    @FunctionalInterface
    public interface Wrap {
        List<int[]> lines(String text);
    }

    // A book after a paste. First is the page the pasted text starts on. Taken is how many of
    // the offered pages went in.
    public record Pasted(List<String> pages, int first, int taken, int offered) {
    }

    // Every page with a mark line between one and the next.
    public static String joined(List<String> pages) {
        StringBuilder text = new StringBuilder();
        for (String page : pages) {
            if (text.length() + page.length() > MOST_COPIED) {
                break;
            }
            if (!text.isEmpty()) {
                text.append('\n').append(PAGE_MARK).append('\n');
            }
            text.append(page);
        }
        return text.toString();
    }

    // The text as pages. A mark line starts a new page and a stretch too long for one page runs
    // on to the next. No page is longer than the edit packet allows and no more pages come back
    // than a book holds. Blank pages at the end are dropped.
    public static List<String> pages(String text, Wrap wrap) {
        List<String> pages = new ArrayList<>();
        StringBuilder stretch = new StringBuilder();
        for (String line : clean(text).split("\n", -1)) {
            if (line.strip().equals(PAGE_MARK)) {
                flow(stretch.toString(), wrap, pages);
                stretch.setLength(0);
            } else {
                stretch.append(stretch.isEmpty() ? "" : "\n").append(line);
            }
        }
        flow(stretch.toString(), wrap, pages);
        while (pages.size() > WritableBookContent.MAX_PAGES || (!pages.isEmpty() && pages.getLast().isBlank())) {
            pages.removeLast();
        }
        return pages;
    }

    // Line breaks of every kind become one kind. A tab becomes a space and anything else the
    // font cannot draw goes. The section sign stays because it colours the text after it.
    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        String read = text.length() > MOST_READ ? text.substring(0, MOST_READ) : text;
        StringBuilder kept = new StringBuilder(read.length());
        for (char c : read.replace("\r\n", "\n").replace('\r', '\n').replace('\t', SPACE).toCharArray()) {
            if (c == '\n' || (c >= SPACE && c != DELETE)) {
                kept.append(c);
            }
        }
        return kept.toString();
    }

    // Cuts one stretch into pages at line ends. A page is full at its last line or at the most
    // letters a page may hold. A single line longer than that is cut where the room ends.
    private static void flow(String stretch, Wrap wrap, List<String> pages) {
        List<int[]> lines = new ArrayList<>(wrap.lines(stretch));
        if (lines.isEmpty()) {
            pages.add("");
            return;
        }
        int at = 0;
        while (at < lines.size() && pages.size() <= WritableBookContent.MAX_PAGES) {
            int start = lines.get(at)[0];
            int next = at;
            while (next < lines.size() && next - at < PAGE_LINES
                && lines.get(next)[1] - start <= WritableBookContent.PAGE_EDIT_LENGTH) {
                next++;
            }
            if (next == at) {
                int cut = start + WritableBookContent.PAGE_EDIT_LENGTH;
                // Neither a colour code nor a character made of two halves is cut in two.
                char last = stretch.charAt(cut - 1);
                if (last == ChatFormatting.PREFIX_CODE || Character.isHighSurrogate(last)) {
                    cut--;
                }
                pages.add(stretch.substring(start, cut));
                lines.set(at, new int[] {cut, lines.get(at)[1]});
            } else {
                pages.add(stretch.substring(start, lines.get(next - 1)[1]));
                at = next;
            }
        }
    }

    // Puts the text into the book from the page the player is on. That page goes only when it
    // is blank and every page after it moves back. Pasted pages the book has no room for and
    // pages that would make the book too heavy for Paper are left off the end of the paste.
    public static Pasted paste(List<String> book, int current, String text, Wrap wrap) {
        List<String> offered = pages(text, wrap);
        List<String> before = new ArrayList<>(book.subList(0, current));
        if (!book.get(current).isBlank()) {
            before.add(book.get(current));
        }
        List<String> after = new ArrayList<>(book.subList(current + 1, book.size()));
        while (!after.isEmpty() && after.getLast().isEmpty()) {
            after.removeLast();
        }
        int room = WritableBookContent.MAX_PAGES - before.size() - after.size();
        int taken = Math.clamp(room, 0, offered.size());
        while (taken > 0 && !BookLimits.paperTakes(merged(before, offered.subList(0, taken), after))) {
            taken--;
        }
        List<String> pages = merged(before, offered.subList(0, taken), after);
        if (pages.isEmpty()) {
            pages.add("");
        }
        return new Pasted(pages, Math.min(before.size(), pages.size() - 1), taken, offered.size());
    }

    private static List<String> merged(List<String> before, List<String> pasted, List<String> after) {
        List<String> pages = new ArrayList<>(before);
        pages.addAll(pasted);
        pages.addAll(after);
        return pages;
    }
}
