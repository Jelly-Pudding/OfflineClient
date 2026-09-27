package com.jellypudding.offlineclient.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// Works out where each element sits and draws it. The editor screen asks for the
// same layout. What you drag is exactly what you see whilst playing.
public final class HudManager {

    // Kept clear of the screen edge. Nothing touches the very border.
    private static final int MARGIN = 3;

    // An element whose anchor falls in the first third of the screen keeps its left
    // edge on that spot and the last third keeps its right edge.
    private static final double FIRST_THIRD = 1 / 3.0;
    private static final double LAST_THIRD = 2 / 3.0;

    public record Placement(HudElement element, int left, int top, int width, int height) {
    }

    private final List<HudElement> elements = new ArrayList<>();
    private final Map<GamePart, GamePartElement> gameParts = new EnumMap<>(GamePart.class);

    public void add(HudElement element) {
        elements.add(element);
        if (element instanceof GamePartElement part) {
            gameParts.put(part.part(), part);
        }
    }

    public List<HudElement> all() {
        return Collections.unmodifiableList(elements);
    }

    // Where each visible element lands on a screen of the given size.
    public List<Placement> layout(Font font, int screenWidth, int screenHeight, boolean editing) {
        List<Placement> placed = new ArrayList<>();
        for (HudElement element : elements) {
            if (editing ? element.isActive() : element.visible()) {
                placed.add(placement(element, font, screenWidth, screenHeight));
            }
        }
        return placed;
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
        // A part of the game's own HUD may sit flush with the edge as it does at home.
        int margin = element.home(screenWidth, screenHeight) == null ? MARGIN : 0;
        return new Placement(element, corner(element.xPercent(), screenWidth, width, margin),
            corner(element.yPercent(), screenHeight, height, margin), width, height);
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
        return (int) Math.max(1, Math.round(element.width(font) * element.scaleX()));
    }

    private static int scaledHeight(HudElement element, Font font) {
        return (int) Math.max(1, Math.round(element.height(font) * element.scaleY()));
    }

    // The top or left edge for an anchor given as a share of the screen.
    private static int corner(double percent, int room, int size, int margin) {
        double share = Math.clamp(percent / 100.0, 0, 1);
        double anchor = share * room;
        double edge = anchor - align(share) * size;
        return (int) Math.round(Math.clamp(edge, margin, Math.max(margin, room - size - margin)));
    }

    // Nought pins the near edge and one the far edge and a half the middle.
    static double align(double share) {
        if (share < FIRST_THIRD) {
            return 0;
        }
        return share > LAST_THIRD ? 1 : 0.5;
    }

    // The share of the screen an element pinned at this edge would be stored as.
    public static double shareOf(int edge, int size, int room) {
        if (room <= 0) {
            return 0;
        }
        double middle = (edge + size / 2.0) / room;
        return Math.clamp((edge + align(middle) * size) / room, 0, 1);
    }

    public void render(GuiGraphicsExtractor context, Font font) {
        int screenWidth = context.guiWidth();
        int screenHeight = context.guiHeight();
        for (Placement placement : layout(font, screenWidth, screenHeight, false)) {
            draw(context, font, placement);
        }
    }

    public void draw(GuiGraphicsExtractor context, Font font, Placement placement) {
        HudElement element = placement.element();
        element.place(placement.left(), placement.top(), placement.width(), placement.height());
        context.pose().pushMatrix();
        context.pose().translate(placement.left(), placement.top());
        context.pose().scale((float) element.scaleX(), (float) element.scaleY());
        element.render(context, font);
        context.pose().popMatrix();
    }

    // Draws a part of the game's own HUD where its element sits. The game still lays
    // the part out at home and the pose carries it across. A part switched off is skipped.
    public void drawGamePart(GamePart part, GuiGraphicsExtractor context, Font font, Runnable draw) {
        GamePartElement element = gameParts.get(part);
        if (element == null) {
            draw.run();
            return;
        }
        if (!element.isActive()) {
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
