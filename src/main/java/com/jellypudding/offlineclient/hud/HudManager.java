package com.jellypudding.offlineclient.hud;

import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class HudManager {

    private static final int MARGIN = 3;

    static final int CLEARANCE = 2 * Backdrop.PAD;

    // An element whose anchor falls in the first third of the screen keeps its left
    // edge on that spot and the last third keeps its right edge.
    private static final double FIRST_THIRD = 1 / 3.0;
    private static final double LAST_THIRD = 2 / 3.0;
    // A piece whose middle sits right on a third is stored as a percent and read back a hair
    // to one side. Within this of a third it keeps the middle either way.
    private static final double ON_THE_LINE = 1e-9;

    public record Placement(HudElement element, int left, int top, int width, int height) {

        public Box box() {
            return new Box(left, top, width, height);
        }
    }

    private record Share(double x, double y) {

        private static Share of(HudElement element) {
            return new Share(element.xPercent(), element.yPercent());
        }
    }

    private final List<HudElement> elements = new ArrayList<>();
    private final Map<GamePart, GamePartElement> gameParts = new EnumMap<>(GamePart.class);
    private final Newcomers newcomers = new Newcomers(this);
    private int revision;

    public void add(HudElement element) {
        elements.add(element);
        revision++;
        if (element instanceof GamePartElement part) {
            gameParts.put(part.part(), part);
        }
    }

    void addAfter(HudElement before, HudElement element) {
        elements.add(elements.indexOf(before) + 1, element);
        revision++;
    }

    void remove(HudElement element) {
        elements.remove(element);
        revision++;
    }

    public int revision() {
        return revision;
    }

    public List<HudElement> all() {
        return Collections.unmodifiableList(elements);
    }

    public List<Placement> layout(Font font, int screenWidth, int screenHeight) {
        List<Placement> placed = new ArrayList<>();
        for (HudElement element : elements) {
            if (element.isActive()) {
                placed.add(placement(element, font, screenWidth, screenHeight));
            }
        }
        return placed;
    }

    public List<Placement> shown(Font font, int screenWidth, int screenHeight, boolean hidden) {
        List<Placement> placed = new ArrayList<>();
        for (HudElement element : elements) {
            if (!onScreen(element, hidden)) {
                continue;
            }
            Box home = element.isActive() ? null : element.home(screenWidth, screenHeight);
            placed.add(home == null ? placement(element, font, screenWidth, screenHeight)
                : new Placement(element, home.left(), home.top(), home.width(), home.height()));
        }
        return placed;
    }

    public List<Placement> others(Collection<HudElement> pieces, Font font, int screenWidth, int screenHeight,
                                  boolean hidden) {
        List<Placement> others = shown(font, screenWidth, screenHeight, hidden);
        others.removeIf(placement -> pieces.contains(placement.element()));
        return others;
    }

    // An element hidden with the overlay is never asked whether it has anything to show. Asking
    // keeps some of them working such as Statistics asking the server for numbers.
    private static boolean drawn(HudElement element, boolean hidden) {
        return (!hidden || element.staysWhenHidden()) && element.visible();
    }

    static boolean onScreen(HudElement element, boolean hidden) {
        return HudElement.editing() && element.isActive() || drawn(element, hidden);
    }

    public static Placement placement(HudElement element, Font font, int screenWidth, int screenHeight) {
        if (element.positionIsDefault()) {
            Placement home = homePlacement(element, font, screenWidth, screenHeight);
            if (home != null) {
                return home;
            }
        }
        int width = scaledWidth(element, font);
        int height = scaledHeight(element, font);
        int margin = margin(element);
        return new Placement(element, corner(element.xPercent(), element.placedWidth(), screenWidth, width, margin),
            corner(element.yPercent(), element.placedHeight(), screenHeight, height, margin), width, height);
    }

    public static int margin(HudElement element) {
        return element.touchesEdges() ? 0 : MARGIN;
    }

    public static Placement homePlacement(HudElement element, Font font, int screenWidth, int screenHeight) {
        Box home = element.home(screenWidth, screenHeight);
        if (home == null) {
            return null;
        }
        int width = scaledWidth(element, font);
        int height = scaledHeight(element, font);
        double alignX = align((home.left() + home.width() / 2.0) / screenWidth);
        double alignY = align((home.top() + home.height() / 2.0) / screenHeight);
        int left = home.left() + (int) Math.round(alignX * (home.width() - width));
        int top = home.top() + (int) Math.round(alignY * (home.height() - height));
        return new Placement(element, left, top, width, height);
    }

    private static int scaledWidth(HudElement element, Font font) {
        return (int) Math.max(1, Math.round(element.shownWidth(font) * element.scaleX()));
    }

    private static int scaledHeight(HudElement element, Font font) {
        return (int) Math.max(1, Math.round(element.shownHeight(font) * element.scaleY()));
    }

    // A pinned element reads its share on the screen it was placed on. Nought for placed means the
    // screen it is on.
    private static int corner(double percent, double placed, int room, int size, int margin) {
        double share = Math.clamp(percent / 100.0, 0, 1);
        double line = align(share);
        double measured = placed > 0 ? placed : room;
        double anchor = line * room + (share - line) * measured;
        double edge = anchor - line * size;
        return (int) Math.round(Math.clamp(edge, margin, Math.max(margin, room - size - margin)));
    }

    static double align(double share) {
        if (share < FIRST_THIRD - ON_THE_LINE) {
            return 0;
        }
        return share > LAST_THIRD + ON_THE_LINE ? 1 : 0.5;
    }

    static double shareOf(int edge, int size, int room) {
        if (room <= 0) {
            return 0;
        }
        double middle = (edge + size / 2.0) / room;
        return Math.clamp((edge + align(middle) * size) / room, 0, 1);
    }

    // An element landing on its own home keeps the home itself and follows the game's layout on any
    // screen.
    public static void pin(HudElement element, int left, int top, int width, int height, Font font,
                           int screenWidth, int screenHeight) {
        Placement home = homePlacement(element, font, screenWidth, screenHeight);
        if (home != null && home.left() == left && home.top() == top) {
            element.resetPosition();
            return;
        }
        int margin = margin(element);
        element.moveTo(shareOf(flush(left, width, screenWidth, margin), width, screenWidth) * 100,
            shareOf(flush(top, height, screenHeight, margin), height, screenHeight) * 100, screenWidth, screenHeight);
    }

    // A piece against the margin is stored flush with the edge and is still drawn at the margin.
    // One put back in its starting corner by hand then holds its starting share.
    private static int flush(int edge, int size, int room, int margin) {
        if (edge <= margin) {
            return 0;
        }
        return edge >= room - size - margin ? room - size : edge;
    }

    // A piece the player just switched on where it started and would lie over another finds the
    // nearest free place here.
    public void beginFrame(Font font, int screenWidth, int screenHeight, boolean hidden) {
        newcomers.seat(font, screenWidth, screenHeight, hidden);
    }

    // No switch among values applied in bulk was flipped by the player's own hand and every piece
    // stays where those values put it.
    public void takeAllAsTheyStand() {
        newcomers.takeAllAsTheyStand();
    }

    // A piece whose switch a reset or an undo changed was not switched on by the player's own hand
    // and stays where it is.
    public void takeAsTheyStand(Collection<? extends Setting<?>> changed) {
        for (HudElement element : elements) {
            if (element.switchIsAmong(changed)) {
                newcomers.takeAsItStands(element);
            }
        }
    }

    // Pins each piece whose place was measured on a screen of another size where it sits on this
    // one like a drag that moves it nowhere.
    public void repin(Collection<HudElement> pieces, Font font, int screenWidth, int screenHeight) {
        for (HudElement piece : pieces) {
            if (!measuredOn(piece.placedWidth(), screenWidth) || !measuredOn(piece.placedHeight(), screenHeight)) {
                Placement at = placement(piece, font, screenWidth, screenHeight);
                pin(piece, at.left(), at.top(), at.width(), at.height(), font, screenWidth, screenHeight);
            }
        }
    }

    private static boolean measuredOn(double placed, int room) {
        return placed == 0 || placed == room;
    }

    public List<HudElement> putBackNear(List<HudElement> pieces, Font font, int screenWidth, int screenHeight) {
        Map<HudElement, Share> held = new HashMap<>();
        for (HudElement piece : pieces) {
            held.put(piece, Share.of(piece));
            piece.resetPosition();
        }
        newcomers.makeRoom(pieces, font, screenWidth, screenHeight, false);
        return pieces.stream().filter(piece -> !Share.of(piece).equals(held.get(piece))).toList();
    }

    // Whilst the overlay is hidden only the elements that stay are drawn. Elements that sit on top
    // of everything wait for renderOnTop.
    public void render(GuiGraphicsExtractor context, Font font, boolean hidden, boolean restWaits) {
        beginFrame(font, context.guiWidth(), context.guiHeight(), hidden);
        drawEach(context, font, element -> drawn(element, hidden || restWaits));
    }

    public void renderOverDebugText(GuiGraphicsExtractor context, Font font) {
        context.nextStratum();
        drawEach(context, font, element -> !element.staysWhenHidden() && element.visible());
    }

    private void drawEach(GuiGraphicsExtractor context, Font font, Predicate<HudElement> wanted) {
        for (HudElement element : elements) {
            if (!element.drawsOnTop() && wanted.test(element)) {
                draw(context, font, placement(element, font, context.guiWidth(), context.guiHeight()));
            }
        }
    }

    public void renderOnTop(GuiGraphicsExtractor context, Font font) {
        List<HudElement> onTop = elements.stream().filter(element -> element.drawsOnTop() && element.visible())
            .toList();
        if (onTop.isEmpty()) {
            return;
        }
        context.nextStratum();
        for (HudElement element : onTop) {
            draw(context, font, placement(element, font, context.guiWidth(), context.guiHeight()));
        }
    }

    // The background goes on before the stretch. Everything the element draws after it is
    // stretched with the element.
    public void draw(GuiGraphicsExtractor context, Font font, Placement placement) {
        HudElement element = placement.element();
        element.place(placement.left(), placement.top(), placement.width(), placement.height());
        context.pose().pushMatrix();
        context.pose().translate(placement.left(), placement.top());
        element.drawBackground(context);
        context.pose().scale((float) element.scaleX(), (float) element.scaleY());
        element.drawContent(context, font);
        context.pose().popMatrix();
    }

    // Switched on the game still lays the part out at home and the pose carries it to where its
    // element sits.
    public void drawGamePart(GamePart part, GuiGraphicsExtractor context, Font font, Runnable draw) {
        GamePartElement element = gameParts.get(part);
        if (element == null || !element.isActive()) {
            draw.run();
            return;
        }
        if (element.hidden()) {
            return;
        }
        Box home = part.home(context.guiWidth(), context.guiHeight());
        Placement at = placement(element, font, context.guiWidth(), context.guiHeight());
        context.pose().pushMatrix();
        context.pose().translate(at.left(), at.top());
        context.pose().scale((float) element.scaleX(), (float) element.scaleY());
        context.pose().translate(-home.left(), -home.top());
        draw.run();
        context.pose().popMatrix();
    }
}
