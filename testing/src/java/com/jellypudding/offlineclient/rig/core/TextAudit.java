package com.jellypudding.offlineclient.rig.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Rules a frame of the interface can be held to without eyes. Every text stays inside the box
// it is written in and on the screen and no text lies over another.
public final class TextAudit {

    // A rectangle counts as the box of a text when it reaches this close to its top and bottom.
    private static final int TOP_SLACK = 1;
    private static final int BOTTOM_SLACK = 2;
    // A rectangle narrower than this is a line or a mark and never a box for words.
    private static final int NARROWEST_BOX = 12;
    // Paint fainter than this is no box anybody sees.
    private static final int FAINTEST_BOX = 0x18;
    // Paint at least this solid hides the text under it.
    private static final int SOLID = 0xB0;
    // Two texts overlap when they share more than this many pixels each way.
    private static final int OVERLAP = 2;
    private static final int FAINTEST_TEXT = 0x20;

    private TextAudit() {
    }

    // One line for each rule a text of the frame breaks.
    public static List<String> problems(List<Drawn> frame, int screenWidth, int screenHeight) {
        Set<String> problems = new LinkedHashSet<>();
        Drawn.Box screen = new Drawn.Box(0, 0, screenWidth, screenHeight);
        for (int i = 0; i < frame.size(); i++) {
            Drawn text = frame.get(i);
            if (!readable(text)) {
                continue;
            }
            Drawn.Box box = boxOf(frame, i);
            Drawn.Box room = box == null ? screen : box;
            String where = box == null ? "the screen" : "its box";
            if (text.box().right() > room.right()) {
                problems.add(quoted(text) + " runs " + (text.box().right() - room.right())
                    + " pixels past the right edge of " + where);
            } else if (text.clip() != null && text.box().right() > text.clip().right()) {
                problems.add(quoted(text) + " is cut off " + (text.box().right() - text.clip().right())
                    + " pixels before its end");
            }
            if (box == null && (text.box().left() < 0 || text.box().top() < 0
                || text.box().bottom() > screenHeight)) {
                problems.add(quoted(text) + " leaves the screen at " + text.box());
            }
            String under = textUnder(frame, i);
            if (under != null) {
                problems.add(quoted(text) + " lies over '" + under + "'");
            }
        }
        return new ArrayList<>(problems);
    }

    private static boolean readable(Drawn drawn) {
        return drawn.isText() && !drawn.text().isBlank() && drawn.alpha() >= FAINTEST_TEXT
            && !drawn.shown().isEmpty();
    }

    private static String quoted(Drawn text) {
        return "'" + text.text() + "'";
    }

    // The smallest rectangle painted before the text that holds where the text starts.
    private static Drawn.Box boxOf(List<Drawn> frame, int textIndex) {
        Drawn text = frame.get(textIndex);
        int startX = text.box().left() + 1;
        int middleY = (text.box().top() + text.box().bottom()) / 2;
        Drawn.Box best = null;
        for (int i = 0; i < textIndex; i++) {
            Drawn paint = frame.get(i);
            if (paint.isText() || paint.alpha() < FAINTEST_BOX) {
                continue;
            }
            Drawn.Box box = paint.shown();
            if (box.width() < NARROWEST_BOX || !box.holds(startX, middleY)
                || box.top() > text.box().top() + TOP_SLACK || box.bottom() < text.box().bottom() - BOTTOM_SLACK) {
                continue;
            }
            if (best == null || box.area() < best.area()) {
                best = box;
            }
        }
        return best;
    }

    // An earlier text this one is written across with nothing solid painted between the two.
    private static String textUnder(List<Drawn> frame, int textIndex) {
        Drawn upper = frame.get(textIndex);
        for (int i = 0; i < textIndex; i++) {
            Drawn lower = frame.get(i);
            if (!readable(lower) || lower.text().equals(upper.text())) {
                continue;
            }
            Drawn.Box shared = lower.shown().cut(upper.shown());
            if (shared.width() <= OVERLAP || shared.height() <= OVERLAP) {
                continue;
            }
            if (!coveredBetween(frame, i, textIndex, shared)) {
                return lower.text();
            }
        }
        return null;
    }

    private static boolean coveredBetween(List<Drawn> frame, int from, int to, Drawn.Box shared) {
        for (int i = from + 1; i < to; i++) {
            Drawn paint = frame.get(i);
            if (!paint.isText() && paint.alpha() >= SOLID && paint.shown().holds(shared)) {
                return true;
            }
        }
        return false;
    }
}
