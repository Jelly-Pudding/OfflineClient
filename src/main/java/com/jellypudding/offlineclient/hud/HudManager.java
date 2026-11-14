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

// Works out where each element sits and draws it. The editor screen asks for the
// same layout. What you drag is exactly what you see whilst playing.
public final class HudManager {

    // Kept clear of the screen edge. Nothing touches the very border.
    private static final int MARGIN = 3;

    // Kept between two pieces that are stood side by side. Their backgrounds never touch.
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

    // The share of the screen an element is stored at as a percentage each way.
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

    // Files a piece straight after another. The list and the draw order keep a kind together.
    void addAfter(HudElement before, HudElement element) {
        elements.add(elements.indexOf(before) + 1, element);
        revision++;
    }

    void remove(HudElement element) {
        elements.remove(element);
        revision++;
    }

    // Goes up whenever a piece comes or goes.
    public int revision() {
        return revision;
    }

    public List<HudElement> all() {
        return Collections.unmodifiableList(elements);
    }

    // Where each switched on element lands in the editor on a screen of the given size.
    public List<Placement> layout(Font font, int screenWidth, int screenHeight) {
        List<Placement> placed = new ArrayList<>();
        for (HudElement element : elements) {
            if (element.isActive()) {
                placed.add(placement(element, font, screenWidth, screenHeight));
            }
        }
        return placed;
    }

    // Everything on the screen and where it sits. Whilst the overlay is hidden only the elements
    // that stay count. A part of the game's own HUD that is switched off sits at home at its own
    // size whatever its element holds.
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

    // Everything on the screen but the given pieces. A piece that is dragged lines up with these
    // and one that is seated keeps clear of them.
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

    // True for an element the HUD pass draws. In the editor every element that is switched on
    // is on the screen too even if only as its name.
    static boolean onScreen(HudElement element, boolean hidden) {
        return HudElement.editing() && element.isActive() || drawn(element, hidden);
    }

    // An element with a home sits there until it is moved. Everything else sits where
    // its anchor says.
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

    // How near the screen edge an element may sit.
    public static int margin(HudElement element) {
        return element.touchesEdges() ? 0 : MARGIN;
    }

    // Where an element sits at home at its present size. It grows away from the screen
    // edge its home is nearest. Null for an element without a home.
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

    // The top or left edge for an anchor given as a share of the screen. A pinned element reads
    // its share on the screen it was placed on. Nought for placed means the screen it is on.
    private static int corner(double percent, double placed, int room, int size, int margin) {
        double share = Math.clamp(percent / 100.0, 0, 1);
        double line = align(share);
        double measured = placed > 0 ? placed : room;
        double anchor = line * room + (share - line) * measured;
        double edge = anchor - line * size;
        return (int) Math.round(Math.clamp(edge, margin, Math.max(margin, room - size - margin)));
    }

    // Nought pins the near edge and one the far edge and a half the middle.
    static double align(double share) {
        if (share < FIRST_THIRD - ON_THE_LINE) {
            return 0;
        }
        return share > LAST_THIRD + ON_THE_LINE ? 1 : 0.5;
    }

    // The share of the screen an element pinned at this edge would be stored as.
    static double shareOf(int edge, int size, int room) {
        if (room <= 0) {
            return 0;
        }
        double middle = (edge + size / 2.0) / room;
        return Math.clamp((edge + align(middle) * size) / room, 0, 1);
    }

    // Pins the element at a spot it was moved to. The spot is stored as a share of this screen.
    // An element landing on its own home keeps the home itself and follows the game's layout on
    // any screen.
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

    // Once a frame before the overlay is laid out. A piece the player just switched on where it
    // started and would lie over another finds the nearest free place here.
    public void beginFrame(Font font, int screenWidth, int screenHeight, boolean hidden) {
        newcomers.seat(font, screenWidth, screenHeight, hidden);
    }

    // Called when saved or default values were applied in bulk such as a load or a reset of
    // everything. No switch among them was flipped by the player's own hand and every piece
    // stays where those values put it.
    public void takeAllAsTheyStand() {
        newcomers.takeAllAsTheyStand();
    }

    // Called with the settings a reset or an undo changed. A piece whose switch is among them
    // was not switched on by the player's own hand and stays where it is.
    public void takeAsTheyStand(Collection<? extends Setting<?>> changed) {
        for (HudElement element : elements) {
            if (element.switchIsAmong(changed)) {
                newcomers.takeAsItStands(element);
            }
        }
    }

    // Pins each piece whose place was measured on a screen of another size where it sits on this
    // one like a drag that moves it nowhere. A put back never changes the screen a piece is
    // measured on then and one that is taken back lands the piece just where it sat.
    public void repin(Collection<HudElement> pieces, Font font, int screenWidth, int screenHeight) {
        for (HudElement piece : pieces) {
            if (!measuredOn(piece.placedWidth(), screenWidth) || !measuredOn(piece.placedHeight(), screenHeight)) {
                Placement at = placement(piece, font, screenWidth, screenHeight);
                pin(piece, at.left(), at.top(), at.width(), at.height(), font, screenWidth, screenHeight);
            }
        }
    }

    // Nought for placed is a share that follows whatever screen it is on.
    private static boolean measuredOn(double placed, int room) {
        return placed == 0 || placed == room;
    }

    // Puts the pieces back where they started in the editor. One whose starting place is taken
    // goes as near to it as it fits. Hands back the pieces that hold another place for it.
    public List<HudElement> putBackNear(List<HudElement> pieces, Font font, int screenWidth, int screenHeight) {
        Map<HudElement, Share> held = new HashMap<>();
        for (HudElement piece : pieces) {
            held.put(piece, Share.of(piece));
            piece.resetPosition();
        }
        newcomers.makeRoom(pieces, font, screenWidth, screenHeight, false);
        return pieces.stream().filter(piece -> !Share.of(piece).equals(held.get(piece))).toList();
    }

    // The overlay drawn along with the game's own HUD. Elements that sit on top of
    // everything wait for renderOnTop. Whilst the overlay is hidden only the elements that
    // stay are drawn. Whilst the rest waits for the debug text they are all that is drawn here
    // as well and renderOverDebugText draws the rest.
    public void render(GuiGraphicsExtractor context, Font font, boolean hidden, boolean restWaits) {
        beginFrame(font, context.guiWidth(), context.guiHeight(), hidden);
        drawEach(context, font, element -> drawn(element, hidden || restWaits));
    }

    // Once the game has drawn the debug text of a frame whose render left the rest waiting. Those
    // elements get a stratum of their own above everything already drawn.
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

    // Once the game has drawn the rest of the frame. These elements get a stratum of their
    // own above everything already drawn.
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

    // Draws a part of the game's own HUD. Switched off it goes where the game puts it. Switched
    // on the game still lays it out at home and the pose carries it to where its element sits.
    // A hidden part is skipped.
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
