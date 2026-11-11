package com.jellypudding.offlineclient.util;

import net.minecraft.world.item.component.WritableBookContent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// What one book and quill edit may carry. The packet takes a hundred pages of 1024 characters and
// a longer one drops the connection. Paper also kicks a player whose pages hold more bytes than
// its budget allows. The budget grows with each page by its length and shrinks for characters
// past plain text. It judges only the total of everything sent.
public final class BookLimits {

    // Paper's budget as it ships. A server can set it lower and the client cannot know.
    private static final int PAPER_PAGE_MAX = 2560;
    private static final double PAPER_MULTIPLIER = 0.98;
    // A page this many characters long earns the whole of its share of the budget.
    private static final double PAPER_FULL_PAGE = 255;
    // The least share a short page earns.
    private static final double PAPER_LEAST_SHARE = 0.1;
    // Characters above this take more than one byte.
    private static final int PLAIN_TOP = 127;

    private BookLimits() {
    }

    // The pages cut to what one edit carries. Each page keeps at most 1024 characters and pages
    // past the hundredth or past Paper's budget are left off the end.
    public static List<String> fit(List<String> pages, boolean paper) {
        List<String> cut = new ArrayList<>();
        for (String page : pages) {
            if (cut.size() == WritableBookContent.MAX_PAGES) {
                break;
            }
            cut.add(cutPage(page));
        }
        return List.copyOf(paper ? cut.subList(0, paperPrefix(cut)) : cut);
    }

    // At most 1024 characters. A character made of two halves is never split.
    public static String cutPage(String page) {
        return TextCut.cut(page, WritableBookContent.PAGE_EDIT_LENGTH);
    }

    // True for a book Paper takes whole in one edit with the budget it ships with.
    public static boolean paperTakes(List<String> pages) {
        return paperPrefix(pages) == pages.size();
    }

    // How many leading pages Paper takes in one edit with the budget it ships with.
    static int paperPrefix(List<String> pages) {
        long total = 0;
        long allowed = PAPER_PAGE_MAX;
        int fits = 0;
        for (int i = 0; i < pages.size(); i++) {
            String page = pages.get(i);
            int bytes = page.getBytes(StandardCharsets.UTF_8).length;
            total += bytes;
            long wide = bytes == page.length() ? 0 : page.chars().filter(c -> c > PLAIN_TOP).count();
            allowed = (long) (allowed + PAPER_PAGE_MAX
                * Math.clamp(page.length() / PAPER_FULL_PAGE, PAPER_LEAST_SHARE, 1) * PAPER_MULTIPLIER);
            if (wide > 1) {
                allowed -= wide;
            }
            if (total <= allowed) {
                fits = i + 1;
            }
        }
        return fits;
    }
}
