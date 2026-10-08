package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.Backdrop;
import com.jellypudding.offlineclient.hud.Box;
import com.jellypudding.offlineclient.hud.GamePartElement;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.HudManager;
import com.jellypudding.offlineclient.hud.HudManager.Placement;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.setting.Defaults;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Flash;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.Tally;
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
// panel listing it follows the ClickGUI scale like every other panel.
public final class HudEditorScreen extends Screen implements ClientScreen {

    // An element that draws nothing still needs something to grab.
    private static final int MIN_GRAB = 40;

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
    private static final String HINT = "Drag to move. Pull an edge or a corner to resize. Right click resets."
        + " Arrow keys nudge. Drag across empty space to pick several.";

    // How long the line saying what a right click put back stays up.
    private static final long RESULT_MS = 10_000;

    private static final double SIZE_STEP = 0.05;

    // How far an arrow key moves the pieces whilst Shift is held.
    private static final int LONG_NUDGE = 10;

    // A pointer position no panel reaches.
    private static final int NOWHERE = -100_000;

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

    // What the last right click put back. A reset with no change found nothing to put back.
    private record Result(String text, Defaults.Change change, long at, long until) {

        private static Result nothing() {
            long now = System.currentTimeMillis();
            return new Result("Nothing to put back.", null, now, now + Flash.SHOWN_MS);
        }

        private static Result of(Defaults.Change change, String done) {
            long now = System.currentTimeMillis();
            return new Result(done + " Press " + InputUtil.undoKeys() + " to undo.", change, now, now + RESULT_MS);
        }

        // Over once its time is up or the reset cannot be taken back.
        private boolean over() {
            return System.currentTimeMillis() > until || (change != null && !Defaults.canUndo(change));
        }

        // Green at first and then the colour of the hint. It fades out at the end.
        private int colour() {
            int ink = change == null ? GuiTheme.textDim()
                : ColorUtil.lerp(GuiTheme.textDim(), GuiTheme.GREEN, Flash.strength(at + Flash.SHOWN_MS));
            return ColorUtil.fade(ink, Flash.strength(until));
        }
    }

    private final HudManager manager;
    private final SettingHost settings = new SettingHost(this, this::setTooltip);
    private final TextInput textInput = new TextInput(this);
    private final HudElementList panel;
    private final LatchedScale panelScale = new LatchedScale();
    private final HudPick pick = new HudPick();
    private final HudSnap snap = new HudSnap();

    private String tooltip;
    private Result result;

    // The arrow keys move the piece under the pointer or else the one last taken hold of.
    private HudElement pointed;
    private HudElement lastHeld;
    // A run of arrow presses keeps to the piece it began with until the pointer moves or clicks
    // or scrolls the panel.
    private HudElement nudging;
    private int lastMouseX;
    private int lastMouseY;

    // What the pointer holds and how. Frozen when the press begins. Measuring against
    // the live placement would feed each new size straight back into the next reading.
    private HudElement held;
    private Grip grip;
    private double pressX;
    private double pressY;
    private Placement start;
    private double startScaleX;
    private double startScaleY;
    // Everything a move carries. A piece in the pick brings the whole pick.
    private List<Placement> carried = List.of();

    public HudEditorScreen(HudManager manager) {
        super(Component.literal("HUD editor"));
        this.manager = manager;
        panel = new HudElementList("Overlay", PANEL_GAP, "hudList", manager.all(), settings);
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

    @Override
    public boolean isTyping() {
        return settings.isEditing() || settings.isListening();
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
        ResetFoot.disarm();
        panel.save();
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
        super.onClose();
    }

    private List<Placement> placements() {
        return manager.layout(font, width, height);
    }

    // The pieces of the pick that are switched on.
    private List<Placement> picked(List<Placement> placed) {
        return placed.stream().filter(placement -> pick.has(placement.element())).toList();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY,
                                   float partialTicks) {
        textInput.set(settings.isEditing());
        if (mouseX != lastMouseX || mouseY != lastMouseY) {
            nudging = null;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
        }
        context.fill(0, 0, width, height, SHADE);
        context.guiRenderState.up();
        drawGameHud(context);
        List<Placement> placed = placements();
        for (Placement placement : placed) {
            manager.draw(context, font, placement);
        }
        context.guiRenderState.up();
        boolean busy = held != null || pick.drawing();
        if (!busy) {
            renderTopLine(context);
        }
        if (held != null && grip == Grip.MOVE) {
            snap.draw(context, width, height, GuiTheme.accent());
        }

        Target hover = busy || overPanel(mouseX, mouseY) ? null : targetAt(placed, mouseX, mouseY);
        HudElement listed = hoveredInList();
        pointed = hover != null ? hover.placement().element() : listed;
        List<int[]> frames = new ArrayList<>(placed.size());
        for (Placement placement : placed) {
            Box frame = frame(placement);
            frames.add(new int[] {frame.left(), frame.top(),
                frame.left() + frame.width(), frame.top() + frame.height()});
            HudElement element = placement.element();
            Grip active = element == held ? grip
                : hover != null && hover.placement().element() == element ? hover.grip() : null;
            boolean over = active != null || element == listed;
            boolean chosen = pick.shows(element, frame);
            renderFrame(context, frame, element, over, chosen);
            // The game's own bars crowd together and are known by sight.
            if (over || chosen || !(element instanceof GamePartElement)) {
                renderLabel(context, frame, element, over);
            }
            if (over) {
                renderHandles(context, frame, active);
            }
        }
        pick.drawBox(context);
        Grip shown = held != null ? grip : hover != null ? hover.grip() : null;
        if (shown != null) {
            context.requestCursor(shown.cursor());
        }
        renderPanels(context, mouseX, mouseY, frames, busy);
    }

    // The game's own bars draw here above the shade and below the overlay as in play.
    private void drawGameHud(GuiGraphicsExtractor context) {
        Hud hud = minecraft.gui.hud;
        if (minecraft.player != null && minecraft.gameMode != null && !hud.isHidden()) {
            hud.extractHotbarAndDecorations(context, minecraft.getDeltaTracker());
        }
    }

    // What the last right click put back. Until the first resize the hint stands there
    // instead. By then the grips have been found.
    private void renderTopLine(GuiGraphicsExtractor context) {
        if (result != null && result.over()) {
            result = null;
        }
        if (result != null) {
            renderTop(context, result.text(), result.colour());
        } else if (manager.all().stream().allMatch(HudElement::sizeIsDefault)) {
            renderTop(context, HINT, GuiTheme.textDim());
        }
    }

    private void renderTop(GuiGraphicsExtractor context, String text, int colour) {
        int y = HINT_TOP;
        for (String line : RenderUtil.wrap(font, text, width - PANEL_GAP * 2)) {
            context.centeredText(font, line, width / 2, y, colour);
            y += font.lineHeight + 1;
        }
    }

    // The outline round an element sits where its background would. Never smaller than the
    // name written above it. It stays on screen and every edge can be taken hold of.
    private Box frame(Placement placement) {
        int wide = Math.max(placement.width(), Math.max(MIN_GRAB, font.width(placement.element().getName())));
        int tall = Math.max(placement.height(), font.lineHeight);
        int left = Math.max(0, placement.left() - Backdrop.PAD);
        int top = Math.max(0, placement.top() - Backdrop.PAD);
        int right = Math.min(width, placement.left() + wide + Backdrop.PAD);
        int bottom = Math.min(height, placement.top() + tall + Backdrop.PAD);
        return new Box(left, top, right - left, bottom - top);
    }

    // The game's own bars draw under the whole overlay and take the pointer only where no
    // overlay piece does. Within each the later pieces draw on top and take it first.
    private Target targetAt(List<Placement> placed, double mouseX, double mouseY) {
        Target overlay = topmost(placed, mouseX, mouseY, false);
        return overlay != null ? overlay : topmost(placed, mouseX, mouseY, true);
    }

    // The last overlay piece under the pointer. With gamePart set it is the last of the game's
    // own bars instead.
    private Target topmost(List<Placement> placed, double mouseX, double mouseY, boolean gamePart) {
        for (int i = placed.size() - 1; i >= 0; i--) {
            Placement placement = placed.get(i);
            if ((placement.element() instanceof GamePartElement) != gamePart) {
                continue;
            }
            Grip at = gripAt(frame(placement), mouseX, mouseY);
            if (at != null) {
                return new Target(placement, at);
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

    // A picked piece is tinted. An outline turns green for a moment when its piece is put back.
    private void renderFrame(GuiGraphicsExtractor context, Box frame, HudElement element, boolean over,
                             boolean chosen) {
        if (chosen) {
            pick.tint(context, frame);
        }
        int usual = over ? GuiTheme.accentText() : chosen ? GuiTheme.accent() : GuiTheme.edge();
        context.outline(frame.left(), frame.top(), frame.width(), frame.height(),
            ColorUtil.lerp(usual, GuiTheme.GREEN, Defaults.freshness(element.getOptions())));
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
    // cover are moved into the same scale first. Whilst a piece is held or a box drawn
    // the panel sees no pointer and asks for no cursor of its own.
    private void renderPanels(GuiGraphicsExtractor context, int mouseX, int mouseY, List<int[]> frames,
                              boolean busy) {
        panelScale.refresh();
        int panelX = toPanel(mouseX);
        int panelY = toPanel(mouseY);
        int panelW = toPanel(width);
        int panelH = toPanel(height);
        tooltip = null;
        panel.update(panelX, panelY, panelW, panelH, 0);
        if (!panelScale.isHeld()) {
            keepInReach(panel, panelW, panelH);
        }
        List<int[]> under = new ArrayList<>(frames.size());
        for (int[] frame : frames) {
            under.add(RenderUtil.scaled(frame[0], frame[1], frame[2], frame[3], panelScale.get()));
        }
        context.pose().pushMatrix();
        context.pose().scale(panelScale.get(), panelScale.get());
        RenderUtil.cover(context, panel.bounds(), under, GuiTheme.bgSolid());
        panel.render(context, busy ? NOWHERE : panelX, busy ? NOWHERE : panelY);
        HudElement hovered = hoveredInList();
        if (tooltip == null && hovered != null) {
            tooltip = hovered.getDescription();
        }
        if (tooltip != null && !tooltip.isEmpty() && GuiScreenBase.hoverHelp()) {
            RenderUtil.tooltip(context, font, RenderUtil.wrap(font, tooltip, TIP_WIDTH),
                panelX, panelY, panelW, panelH, GuiTheme.bgTooltip(), GuiTheme.text());
        }
        context.pose().popMatrix();
    }

    // The row under the pointer whilst the panel is open.
    private HudElement hoveredInList() {
        return panel.isCollapsed() ? null : panel.getHovered();
    }

    private boolean overPanel(double mouseX, double mouseY) {
        return panel.isOver(toPanel(mouseX), toPanel(mouseY));
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
        nudging = null;
        if (panel.mouseClicked(toPanel(event.x()), toPanel(event.y()), event.button())) {
            return true;
        }
        Target target = targetAt(placements(), event.x(), event.y());
        if (target == null) {
            // A click on empty space lets go of everything. A drag from there picks afresh.
            pick.clear();
            lastHeld = null;
            if (!InputUtil.isLeft(event.button())) {
                return super.mouseClicked(event, doubleClick);
            }
            pick.begin(event.x(), event.y());
            return true;
        }
        if (InputUtil.isRight(event.button())) {
            reset(target.placement().element());
        } else if (InputUtil.isLeft(event.button())) {
            hold(target, event.x(), event.y());
        }
        return true;
    }

    // The size goes back first and a second right click puts the place back. A right click
    // on a picked piece puts back the whole pick as one change.
    private void reset(HudElement element) {
        List<HudElement> pieces = new ArrayList<>();
        if (pick.has(element)) {
            for (Placement placement : picked(placements())) {
                pieces.add(placement.element());
            }
        } else {
            pieces.add(element);
        }
        boolean size = pieces.stream().anyMatch(piece -> !piece.sizeIsDefault());
        List<Setting<?>> scope = new ArrayList<>();
        List<HudElement> changed = new ArrayList<>();
        for (HudElement piece : pieces) {
            List<Setting<?>> part = size ? piece.sizeSettings() : piece.placeSettings();
            if (Defaults.changed(part) > 0) {
                changed.add(piece);
            }
            scope.addAll(part);
        }
        if (changed.isEmpty()) {
            result = Result.nothing();
            return;
        }
        boolean one = changed.size() == 1;
        String name = one ? changed.getFirst().getName() : Tally.counted(changed.size(), "HUD piece");
        String done;
        if (size) {
            done = one ? "Size put back." : "Sizes put back.";
        } else {
            done = one ? "Put back where it started." : "Put back where they started.";
        }
        result = Result.of(Defaults.reset(name, scope), done);
    }

    private void hold(Target target, double x, double y) {
        Placement placement = target.placement();
        held = placement.element();
        lastHeld = held;
        grip = target.grip();
        pressX = x;
        pressY = y;
        start = placement;
        startScaleX = held.scaleX();
        startScaleY = held.scaleY();
        // Taking hold of a piece outside the pick lets the pick go.
        if (!pick.has(held)) {
            pick.clear();
        }
        carried = grip == Grip.MOVE && pick.has(held) ? picked(placements()) : List.of(placement);
        snap.reset();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (settings.keyPressed(event)) {
            return true;
        }
        // Not whilst a piece is held. Its next move would write over whatever came back.
        if (held == null && InputUtil.isUndo(event)) {
            ChatUtil.undone(Defaults.undoNewest());
            return true;
        }
        return nudge(event) || super.keyPressed(event);
    }

    // An arrow key moves a piece one pixel and Shift makes it ten. A piece in the pick
    // brings the whole pick and with no piece to hand the pick moves on its own.
    private boolean nudge(KeyEvent event) {
        int dx = event.isRight() ? 1 : event.isLeft() ? -1 : 0;
        int dy = event.isDown() ? 1 : event.isUp() ? -1 : 0;
        if ((dx == 0 && dy == 0) || held != null || pick.drawing()) {
            return false;
        }
        HudElement focus = nudging != null ? nudging : pointed != null ? pointed : lastHeld;
        List<Placement> pieces = nudged(focus);
        if (pieces.isEmpty()) {
            return false;
        }
        int step = event.hasShiftDown() ? LONG_NUDGE : 1;
        slide(pieces, dx * step, dy * step);
        nudging = focus;
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
        return true;
    }

    private List<Placement> nudged(HudElement focus) {
        List<Placement> placed = placements();
        if (focus != null && !pick.has(focus)) {
            for (Placement placement : placed) {
                if (placement.element() == focus) {
                    return List.of(placement);
                }
            }
        }
        return picked(placed);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return settings.charTyped((char) event.codepoint()) || super.charTyped(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        nudging = null;
        if (overPanel(mouseX, mouseY)) {
            panel.wheel(scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        panelScale.hold(false);
        panel.mouseReleased();
        if (held != null) {
            held = null;
            grip = null;
            carried = List.of();
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
            return true;
        }
        if (pick.drawing() && InputUtil.isLeft(event.button())) {
            List<HudElement> touched = new ArrayList<>();
            for (Placement placement : placements()) {
                if (pick.touches(frame(placement))) {
                    touched.add(placement.element());
                }
            }
            pick.finish(touched);
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (pick.drawing()) {
            pick.drag(event.x(), event.y());
            return true;
        }
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

    // Snaps to the screen and to the pieces left behind. A part of the game's own HUD moved
    // alone also snaps back to its home. Shift moves freely.
    private void move(MouseButtonEvent event) {
        int dx = (int) Math.round(event.x() - pressX);
        int dy = (int) Math.round(event.y() - pressY);
        if (carried.size() == 1 && goHome(carried.getFirst(), dx, dy)) {
            return;
        }
        Box group = around(carried);
        if (minecraft.hasShiftDown()) {
            snap.loose();
        } else {
            List<Box> others = new ArrayList<>();
            for (Placement placement : placements()) {
                if (carried.stream().noneMatch(piece -> piece.element() == placement.element())) {
                    others.add(boxOf(placement));
                }
            }
            dx = snap.stickAcross(group.left() + dx, group.width(), width, others) - group.left();
            dy = snap.stickDown(group.top() + dy, group.height(), height, others) - group.top();
        }
        snap.landed(slide(carried, dx, dy));
    }

    // The game's bars sit a few pixels apart. Home wins over their edges.
    private boolean goHome(Placement piece, int dx, int dy) {
        Placement home = HudManager.homePlacement(piece.element(), font, width, height);
        if (home == null || Math.abs(piece.left() + dx - home.left()) > HudSnap.REACH
            || Math.abs(piece.top() + dy - home.top()) > HudSnap.REACH) {
            return false;
        }
        piece.element().resetPosition();
        snap.home(boxOf(home));
        return true;
    }

    // Moves the pieces together. They stop as one at the screen margin and keep their shape.
    // Hands back the box round them where they came to rest.
    private Box slide(List<Placement> pieces, int dx, int dy) {
        int across = dx;
        int down = dy;
        for (Placement piece : pieces) {
            int margin = HudManager.margin(piece.element());
            across = Math.clamp(across, Math.min(0, margin - piece.left()),
                Math.max(0, width - margin - piece.width() - piece.left()));
            down = Math.clamp(down, Math.min(0, margin - piece.top()),
                Math.max(0, height - margin - piece.height() - piece.top()));
        }
        for (Placement piece : pieces) {
            settle(piece.element(), piece.left() + across, piece.top() + down, piece.width(), piece.height());
        }
        Box group = around(pieces);
        return new Box(group.left() + across, group.top() + down, group.width(), group.height());
    }

    // The smallest box round every piece.
    private static Box around(List<Placement> pieces) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (Placement piece : pieces) {
            left = Math.min(left, piece.left());
            top = Math.min(top, piece.top());
            right = Math.max(right, piece.left() + piece.width());
            bottom = Math.max(bottom, piece.top() + piece.height());
        }
        return new Box(left, top, right - left, bottom - top);
    }

    private static Box boxOf(Placement placement) {
        return new Box(placement.left(), placement.top(), placement.width(), placement.height());
    }

    // An edge stretches one way and leaves the far edge where it was. A corner keeps
    // the shape and grows away from the opposite corner. Shift turns off the steps.
    private void resize(MouseButtonEvent event) {
        boolean free = minecraft.hasShiftDown();
        double across = (start.width() + grip.dx * (event.x() - pressX)) / start.width();
        double down = (start.height() + grip.dy * (event.y() - pressY)) / start.height();
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
        int left = grip.dx < 0 ? start.left() + start.width() - now.width() : start.left();
        int top = grip.dy < 0 ? start.top() + start.height() - now.height() : start.top();
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
        int margin = HudManager.margin(element);
        element.moveTo(HudManager.shareOf(flush(left, wide, width, margin), wide, width) * 100,
            HudManager.shareOf(flush(top, tall, height, margin), tall, height) * 100, width, height);
    }

    // A piece against the margin is stored flush with the edge and is still drawn at the margin.
    // One put back in its starting corner by hand then holds its starting share.
    private static int flush(int edge, int size, int room, int margin) {
        if (edge <= margin) {
            return 0;
        }
        return edge >= room - size - margin ? room - size : edge;
    }
}
