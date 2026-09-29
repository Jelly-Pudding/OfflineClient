package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.Box;
import com.jellypudding.offlineclient.hud.GamePartElement;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.HudManager;
import com.jellypudding.offlineclient.hud.HudManager.Placement;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

// Drag the overlay about and pull an edge or a corner to resize it. What you see
// here is what you get whilst playing. The overlay keeps its true size and the
// panels listing it follow the ClickGUI scale like every other panel.
public final class HudEditorScreen extends Screen {

    // How close an edge has to be before it sticks.
    private static final int SNAP = 4;

    // An element that draws nothing still needs something to grab.
    private static final int MIN_GRAB = 40;

    // The outline sits this far outside an element.
    private static final int PAD = 2;

    private static final int LABEL_GAP = 11;
    // Between an element's name and its size.
    private static final int LABEL_SPACE = 4;

    // The squares on the corners and edges of the element in hand.
    private static final int HANDLE = 5;
    // How far either side of an edge still takes hold of it.
    private static final int EDGE_REACH = 2;

    private static final int TIP_WIDTH = 170;
    private static final int PANEL_GAP = 6;

    private static final int HINT_TOP = 3;
    private static final String HINT = "Drag to move. Pull an edge or a corner to resize. Right click resets.";

    private static final double SIZE_STEP = 0.05;

    // A dim wash to stop the world fighting the boxes.
    private static final int SHADE = 0x90000000;

    // Which edges a drag moves. No edge at all moves the whole element.
    private enum Grip {
        MOVE(0, 0), LEFT(-1, 0), RIGHT(1, 0), TOP(0, -1), BOTTOM(0, 1),
        TOP_LEFT(-1, -1), TOP_RIGHT(1, -1), BOTTOM_LEFT(-1, 1), BOTTOM_RIGHT(1, 1);

        private final int dx;
        private final int dy;

        Grip(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }

        static Grip of(int dx, int dy) {
            for (Grip grip : values()) {
                if (grip.dx == dx && grip.dy == dy) {
                    return grip;
                }
            }
            return MOVE;
        }

        boolean corner() {
            return dx != 0 && dy != 0;
        }

        CursorType cursor() {
            if (corner()) {
                return dx == dy ? Diagonals.FALLING : Diagonals.RISING;
            }
            if (dx != 0) {
                return CursorTypes.RESIZE_EW;
            }
            return dy != 0 ? CursorTypes.RESIZE_NS : CursorTypes.RESIZE_ALL;
        }
    }

    // SDL has diagonal resize cursors the game never makes. Five and six are their numbers.
    private static final class Diagonals {
        // From the top left corner to the bottom right one.
        static final CursorType FALLING = CursorType.createStandardCursor(5, "resize_nwse", CursorTypes.RESIZE_ALL);
        static final CursorType RISING = CursorType.createStandardCursor(6, "resize_nesw", CursorTypes.RESIZE_ALL);
    }

    // The element under the pointer and the grip a press there would take.
    private record Target(Placement placement, Grip grip) {
    }

    private final HudManager manager;
    private final SettingHost settings = new SettingHost(this, this::setTooltip);
    private final TextInput textInput = new TextInput(this);
    private final List<HudElementList> lists;
    private final LatchedScale panelScale = new LatchedScale();

    private String tooltip;

    // What the pointer holds and how. Frozen when the press begins. Measuring against
    // the live placement would feed each new size straight back into the next reading.
    private HudElement held;
    private Grip grip;
    private double pressX;
    private double pressY;
    private int startLeft;
    private int startTop;
    private int startWidth;
    private int startHeight;
    private double startScaleX;
    private double startScaleY;

    public HudEditorScreen(HudManager manager) {
        super(Component.literal("HUD editor"));
        this.manager = manager;
        List<HudElement> overlay = new ArrayList<>();
        List<HudElement> game = new ArrayList<>();
        for (HudElement element : manager.all()) {
            (element instanceof GamePartElement ? game : overlay).add(element);
        }
        lists = List.of(
            new HudElementList("Overlay", PANEL_GAP, "hudList", overlay, settings),
            new HudElementList("Game HUD", PANEL_GAP * 2 + GuiTheme.PANEL_WIDTH, "gameHudList", game, settings));
    }

    // Null whilst the HUD module has not been built yet.
    public static HudEditorScreen open() {
        HudModule hud = Modules.get(HudModule.class);
        return hud == null ? null : new HudEditorScreen(hud.getManager());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void setTooltip(String text) {
        tooltip = text;
    }

    @Override
    public void removed() {
        textInput.set(false);
        super.removed();
    }

    @Override
    public void onClose() {
        settings.commitEditing();
        lists.forEach(HudElementList::save);
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
        super.onClose();
    }

    private List<Placement> placements() {
        return manager.layout(font, width, height, true);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY,
                                   float partialTicks) {
        textInput.set(settings.isEditing());
        context.fill(0, 0, width, height, SHADE);
        context.guiRenderState.up();
        drawGameHud(context);
        List<Placement> placed = placements();
        for (Placement placement : placed) {
            manager.draw(context, font, placement);
        }
        context.guiRenderState.up();
        // Shown until the first resize. By then the grips have been found.
        if (held == null && manager.all().stream().allMatch(HudElement::sizeIsDefault)) {
            int y = HINT_TOP;
            for (String line : RenderUtil.wrap(font, HINT, width - PANEL_GAP * 2)) {
                context.centeredText(font, line, width / 2, y, GuiTheme.textDim());
                y += font.lineHeight + 1;
            }
        }

        Target hover = held == null && !overPanel(mouseX, mouseY) ? targetAt(placed, mouseX, mouseY) : null;
        HudElement picked = pickedInList();
        List<int[]> frames = new ArrayList<>(placed.size());
        for (Placement placement : placed) {
            Box frame = frame(placement);
            frames.add(new int[] {frame.left(), frame.top(),
                frame.left() + frame.width(), frame.top() + frame.height()});
            HudElement element = placement.element();
            Grip active = element == held ? grip
                : hover != null && hover.placement().element() == element ? hover.grip() : null;
            boolean over = active != null || element == picked;
            context.outline(frame.left(), frame.top(), frame.width(), frame.height(),
                over ? GuiTheme.accentText() : GuiTheme.edge());
            // The game's own bars crowd together and are known by sight.
            if (over || !(element instanceof GamePartElement)) {
                renderLabel(context, frame, element, over);
            }
            if (over) {
                renderHandles(context, frame, active);
            }
        }
        Grip shown = held != null ? grip : hover != null ? hover.grip() : null;
        if (shown != null) {
            context.requestCursor(shown.cursor());
        }
        renderPanels(context, mouseX, mouseY, frames);
    }

    // The game's own bars draw here above the shade and below the overlay as in play.
    private void drawGameHud(GuiGraphicsExtractor context) {
        Hud hud = minecraft.gui.hud;
        if (minecraft.player != null && minecraft.gameMode != null && !hud.isHidden()) {
            hud.extractHotbarAndDecorations(context, minecraft.getDeltaTracker());
        }
    }

    // The outline round an element. Never smaller than the name written above it. It
    // stays on screen and every edge can be taken hold of.
    private Box frame(Placement placement) {
        int wide = Math.max(placement.width(), Math.max(MIN_GRAB, font.width(placement.element().getName())));
        int tall = Math.max(placement.height(), font.lineHeight);
        int left = Math.max(0, placement.left() - PAD);
        int top = Math.max(0, placement.top() - PAD);
        int right = Math.min(width, placement.left() + wide + PAD);
        int bottom = Math.min(height, placement.top() + tall + PAD);
        return new Box(left, top, right - left, bottom - top);
    }

    // Later elements draw on top and take the pointer first.
    private Target targetAt(List<Placement> placed, double mouseX, double mouseY) {
        for (int i = placed.size() - 1; i >= 0; i--) {
            Grip at = gripAt(frame(placed.get(i)), mouseX, mouseY);
            if (at != null) {
                return new Target(placed.get(i), at);
            }
        }
        return null;
    }

    // Which grip of the frame the pointer is on. Null when it is off the frame.
    private static Grip gripAt(Box frame, double mouseX, double mouseY) {
        int right = frame.left() + frame.width() - 1;
        int bottom = frame.top() + frame.height() - 1;
        if (mouseX < frame.left() - EDGE_REACH || mouseX > right + EDGE_REACH
            || mouseY < frame.top() - EDGE_REACH || mouseY > bottom + EDGE_REACH) {
            return null;
        }
        return Grip.of(side(mouseX, frame.left(), right), side(mouseY, frame.top(), bottom));
    }

    // Minus one near the first edge and one near the last and nought between them.
    private static int side(double at, int first, int last) {
        if (Math.abs(at - first) <= EDGE_REACH) {
            return -1;
        }
        return Math.abs(at - last) <= EDGE_REACH ? 1 : 0;
    }

    // The name above the frame and its size beside it whilst it is under the pointer.
    private void renderLabel(GuiGraphicsExtractor context, Box frame, HudElement element, boolean over) {
        String name = element.getName();
        String size = over ? sizeText(element) : null;
        int nameWidth = font.width(name);
        int total = size == null ? nameWidth : nameWidth + LABEL_SPACE + font.width(size);
        int x = Math.clamp(frame.left(), 0, Math.max(0, width - total));
        int y = labelY(frame);
        context.text(font, name, x, y, over ? GuiTheme.accentText() : GuiTheme.textDim(), true);
        if (size != null) {
            context.text(font, size, x + nameWidth + LABEL_SPACE, y, GuiTheme.textDim(), true);
        }
    }

    // One figure whilst both ways match. The width and then the height once stretched.
    private static String sizeText(HudElement element) {
        long across = Math.round(element.scaleX() * 100);
        long down = Math.round(element.scaleY() * 100);
        return across == down ? across + "%" : across + "% × " + down + "%";
    }

    // An element pinned against the top edge has no room above it. Its name
    // drops underneath instead.
    private int labelY(Box frame) {
        int above = frame.top() - LABEL_GAP;
        return above < 0 ? frame.top() + frame.height() + 2 : above;
    }

    // A square on each corner and in the middle of each edge. The one in use is brighter.
    private static void renderHandles(GuiGraphicsExtractor context, Box frame, Grip active) {
        int right = frame.left() + frame.width() - 1;
        int bottom = frame.top() + frame.height() - 1;
        for (Grip each : Grip.values()) {
            if (each == Grip.MOVE) {
                continue;
            }
            int x = each.dx < 0 ? frame.left() : each.dx > 0 ? right : (frame.left() + right) / 2;
            int y = each.dy < 0 ? frame.top() : each.dy > 0 ? bottom : (frame.top() + bottom) / 2;
            int corner = HANDLE / 2;
            context.fill(x - corner, y - corner, x - corner + HANDLE, y - corner + HANDLE,
                each == active ? GuiTheme.accent() : GuiTheme.accentText());
        }
    }

    // The panels and their tooltip draw at the ClickGUI scale. The overlay frames they
    // cover are moved into the same scale first.
    private void renderPanels(GuiGraphicsExtractor context, int mouseX, int mouseY, List<int[]> frames) {
        panelScale.refresh();
        int panelX = toPanel(mouseX);
        int panelY = toPanel(mouseY);
        int panelW = toPanel(width);
        int panelH = toPanel(height);
        tooltip = null;
        for (HudElementList list : lists) {
            list.update(panelX, panelY, panelW, panelH, 0);
            if (!panelScale.isHeld()) {
                keepInReach(list, panelW, panelH);
            }
        }
        List<int[]> under = new ArrayList<>(frames.size());
        for (int[] frame : frames) {
            under.add(RenderUtil.scaled(frame[0], frame[1], frame[2], frame[3], panelScale.get()));
        }
        context.pose().pushMatrix();
        context.pose().scale(panelScale.get(), panelScale.get());
        for (HudElementList list : lists) {
            RenderUtil.cover(context, list.bounds(), under, GuiTheme.bgSolid());
        }
        for (HudElementList list : lists) {
            list.render(context, panelX, panelY);
        }
        HudElement hovered = pickedInList();
        if (tooltip == null && hovered != null) {
            tooltip = hovered.getDescription();
        }
        if (tooltip != null && !tooltip.isEmpty() && GuiScreenBase.hoverHelp()) {
            RenderUtil.tooltip(context, font, RenderUtil.wrap(font, tooltip, TIP_WIDTH),
                panelX, panelY, panelW, panelH, GuiTheme.bgTooltip(), GuiTheme.text());
        }
        context.pose().popMatrix();
    }

    // The row under the pointer in any open panel.
    private HudElement pickedInList() {
        for (HudElementList list : lists) {
            if (!list.isCollapsed() && list.getHovered() != null) {
                return list.getHovered();
            }
        }
        return null;
    }

    private boolean overPanel(double mouseX, double mouseY) {
        return lists.stream().anyMatch(list -> list.isOver(toPanel(mouseX), toPanel(mouseY)));
    }

    private int toPanel(double screen) {
        return (int) panelScale.toView(screen);
    }

    // A new scale or a smaller window must never leave a panel out of reach.
    private static void keepInReach(HudElementList list, int panelW, int panelH) {
        list.setPosition(Math.clamp(list.getX(), 0, Math.max(0, panelW - list.getWidth())),
            Math.clamp(list.getY(), 0, Math.max(0, panelH - GuiTheme.HEADER_HEIGHT)));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        panelScale.hold(true);
        settings.beginClick();
        // The panel drawn last sits on top.
        for (HudElementList list : lists.reversed()) {
            if (list.mouseClicked(toPanel(event.x()), toPanel(event.y()), event.button())) {
                return true;
            }
        }
        Target target = targetAt(placements(), event.x(), event.y());
        if (target == null) {
            return super.mouseClicked(event, doubleClick);
        }
        if (InputUtil.isRight(event.button())) {
            reset(target.placement().element());
        } else if (InputUtil.isLeft(event.button())) {
            hold(target, event.x(), event.y());
        }
        return true;
    }

    // The size goes back first. A second right click puts the element back where it started.
    private static void reset(HudElement element) {
        if (element.sizeIsDefault()) {
            element.resetPosition();
        } else {
            element.resetSize();
        }
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
    }

    private void hold(Target target, double x, double y) {
        Placement placement = target.placement();
        held = placement.element();
        grip = target.grip();
        pressX = x;
        pressY = y;
        startLeft = placement.left();
        startTop = placement.top();
        startWidth = placement.width();
        startHeight = placement.height();
        startScaleX = held.scaleX();
        startScaleY = held.scaleY();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return settings.keyPressed(event) || super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return settings.charTyped((char) event.codepoint()) || super.charTyped(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        for (HudElementList list : lists) {
            if (list.isOver(toPanel(mouseX), toPanel(mouseY))) {
                list.wheel(scrollY);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        panelScale.hold(false);
        lists.forEach(HudElementList::mouseReleased);
        if (held != null) {
            held = null;
            grip = null;
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (held == null) {
            return super.mouseDragged(event, dragX, dragY);
        }
        if (grip == Grip.MOVE) {
            move(event);
        } else {
            resize(event);
        }
        return true;
    }

    // Snaps to the screen and to the other elements. A part of the game's own HUD
    // also snaps back to its home.
    private void move(MouseButtonEvent event) {
        Placement moving = null;
        List<int[]> others = new ArrayList<>();
        for (Placement placement : placements()) {
            if (placement.element() == held) {
                moving = placement;
            } else {
                others.add(new int[] {placement.left(), placement.top(), placement.width(), placement.height()});
            }
        }
        if (moving == null) {
            return;
        }
        // The frame is padded out to stay clickable. Only the real size may reach the
        // stored position or the element jumps on release.
        int wide = moving.width();
        int tall = moving.height();
        int left = startLeft + (int) Math.round(event.x() - pressX);
        int top = startTop + (int) Math.round(event.y() - pressY);
        Placement home = HudManager.homePlacement(held, font, width, height);
        // The game's bars sit a few pixels apart. Home wins over their edges.
        if (home != null && Math.abs(left - home.left()) <= SNAP && Math.abs(top - home.top()) <= SNAP) {
            held.resetPosition();
            return;
        }
        if (!event.hasShiftDown()) {
            left = snap(left, wide, width, others, true);
            top = snap(top, tall, height, others, false);
        }
        settle(held, left, top, wide, tall);
    }

    // An edge stretches one way and leaves the far edge where it was. A corner keeps
    // the shape and grows away from the opposite corner. Shift turns off the steps.
    private void resize(MouseButtonEvent event) {
        boolean free = event.hasShiftDown();
        double across = (startWidth + grip.dx * (event.x() - pressX)) / startWidth;
        double down = (startHeight + grip.dy * (event.y() - pressY)) / startHeight;
        if (grip.corner()) {
            // The longer pull wins and neither way may leave the allowed sizes.
            double least = HudElement.MIN_STRETCH / Math.min(startScaleX, startScaleY);
            double most = held.mostStretch() / Math.max(startScaleX, startScaleY);
            double stretch = Math.clamp(Math.max(across, down), least, most);
            held.setSize(step(startScaleX * stretch, free), step(startScaleY * stretch, free));
        } else if (grip.dx != 0) {
            held.setSize(step(startScaleX * across, free), startScaleY);
        } else {
            held.setSize(startScaleX, step(startScaleY * down, free));
        }
        Placement now = HudManager.placement(held, font, width, height);
        int left = grip.dx < 0 ? startLeft + startWidth - now.width() : startLeft;
        int top = grip.dy < 0 ? startTop + startHeight - now.height() : startTop;
        settle(held, left, top, now.width(), now.height());
    }

    // Five percent at a time unless shift is held.
    private static double step(double stretch, boolean free) {
        return free ? stretch : Math.round(stretch / SIZE_STEP) * SIZE_STEP;
    }

    // Stores the new spot as a share of the screen. An element landing on its own home
    // keeps the home itself and follows the game's layout on any screen.
    private void settle(HudElement element, int left, int top, int wide, int tall) {
        Placement home = HudManager.homePlacement(element, font, width, height);
        if (home != null && home.left() == left && home.top() == top) {
            element.resetPosition();
            return;
        }
        element.moveTo(HudManager.shareOf(left, wide, width) * 100,
            HudManager.shareOf(top, tall, height) * 100, width, height);
    }

    // Sticks to the nearest of the screen edges and middle and the edges of the other elements.
    private static int snap(int edge, int size, int room, List<int[]> others, boolean horizontal) {
        List<Integer> stops = new ArrayList<>();
        stops.add(0);
        stops.add((room - size) / 2);
        stops.add(room - size);
        for (int[] other : others) {
            int start = horizontal ? other[0] : other[1];
            int length = horizontal ? other[2] : other[3];
            stops.add(start);
            stops.add(start + length - size);
            stops.add(start + length);
            stops.add(start - size);
        }
        int best = edge;
        int bestGap = SNAP + 1;
        for (int stop : stops) {
            int gap = Math.abs(edge - stop);
            if (gap < bestGap) {
                best = stop;
                bestGap = gap;
            }
        }
        return best;
    }
}
