package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.Backdrop;
import com.jellypudding.offlineclient.hud.Box;
import com.jellypudding.offlineclient.hud.GamePartElement;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.HudManager;
import com.jellypudding.offlineclient.hud.HudManager.Placement;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.render.ClientFonts;
import com.jellypudding.offlineclient.setting.Defaults;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.Tally;
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

// The overlay keeps its true size and the panel listing it follows the ClickGUI scale like every
// other panel.
public final class HudEditorScreen extends Screen implements ClientScreen {

    // An element that draws nothing still needs something to grab.
    private static final int MIN_GRAB = 40;

    private static final int LABEL_GAP = 11;
    private static final int LABEL_SPACE = 4;

    private static final int HANDLE = 5;
    private static final int EDGE_REACH = 2;

    private static final int TIP_WIDTH = 170;
    private static final int PANEL_GAP = 6;

    private static final double SIZE_STEP = 0.05;

    private static final int LONG_NUDGE = 10;

    private static final int NOWHERE = -100_000;

    private static final int SHADE = 0x90000000;

    // No edge at all moves the whole element.
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
        static final CursorType FALLING = CursorType.createStandardCursor(5, "resize_nwse", CursorTypes.RESIZE_ALL);
        static final CursorType RISING = CursorType.createStandardCursor(6, "resize_nesw", CursorTypes.RESIZE_ALL);
    }

    private record Target(Placement placement, Grip grip) {
    }

    private final HudManager manager;
    private final SettingHost settings = new SettingHost(this, this::setTooltip);
    private final TextInput textInput = new TextInput(this);
    private final HudFoot foot = new HudFoot();
    private final HudElementList panel;
    private final LatchedScale panelScale = new LatchedScale();
    private final HudPick pick = new HudPick();
    private final HudSnap snap = new HudSnap();

    private String tooltip;

    private HudElement pointed;
    private HudElement lastHeld;
    // A run of arrow presses keeps to the piece it began with until the pointer moves or clicks
    // or scrolls the panel.
    private HudElement nudging;
    private int lastMouseX;
    private int lastMouseY;

    // Frozen when the press begins. Measuring against the live placement would feed each new size
    // straight back into the next reading.
    private HudElement held;
    private Grip grip;
    private double pressX;
    private double pressY;
    private Placement start;
    private double startScaleX;
    private double startScaleY;
    private List<Placement> carried = List.of();

    public HudEditorScreen(HudManager manager) {
        super(Component.literal("HUD editor"));
        this.manager = manager;
        panel = new HudElementList("Overlay", PANEL_GAP, "hudList", manager.all(), settings, foot);
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

    public void piecesChanged(String said, Defaults.Change change) {
        pick.keepOnly(manager.all());
        if (!manager.all().contains(lastHeld)) {
            lastHeld = null;
        }
        nudging = null;
        foot.report(said, change);
    }

    public void refuse(String why) {
        foot.refuse(why);
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
        return manager.layout(ClientFonts.hud(), width, height);
    }

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
        Font font = ClientFonts.hud();
        manager.beginFrame(font, width, height, false);
        context.fill(0, 0, width, height, SHADE);
        context.guiRenderState.up();
        drawGameHud(context);
        List<Placement> placed = placements();
        for (Placement placement : placed) {
            manager.draw(context, font, placement);
        }
        context.guiRenderState.up();
        boolean busy = held != null || pick.drawing();
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

    private Box frame(Placement placement) {
        Font font = ClientFonts.hud();
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

    private static Grip gripAt(Box frame, double mouseX, double mouseY) {
        int right = frame.left() + frame.width() - 1;
        int bottom = frame.top() + frame.height() - 1;
        if (mouseX < frame.left() - EDGE_REACH || mouseX > right + EDGE_REACH
            || mouseY < frame.top() - EDGE_REACH || mouseY > bottom + EDGE_REACH) {
            return null;
        }
        return Grip.of(side(mouseX, frame.left(), right), side(mouseY, frame.top(), bottom));
    }

    private static int side(double at, int first, int last) {
        if (Math.abs(at - first) <= EDGE_REACH) {
            return -1;
        }
        return Math.abs(at - last) <= EDGE_REACH ? 1 : 0;
    }

    private void renderFrame(GuiGraphicsExtractor context, Box frame, HudElement element, boolean over,
                             boolean chosen) {
        if (chosen) {
            pick.tint(context, frame);
        }
        int usual = over ? GuiTheme.accentText() : chosen ? GuiTheme.accent() : GuiTheme.edge();
        context.outline(frame.left(), frame.top(), frame.width(), frame.height(),
            ColorUtil.lerp(usual, GuiTheme.GREEN, Defaults.freshness(element.getOptions())));
    }

    private void renderLabel(GuiGraphicsExtractor context, Box frame, HudElement element, boolean over) {
        String name = element.getName();
        String size = over ? sizeText(element) : null;
        Font font = ClientFonts.hud();
        int nameWidth = font.width(name);
        int total = size == null ? nameWidth : nameWidth + LABEL_SPACE + font.width(size);
        int x = Math.clamp(frame.left(), 0, Math.max(0, width - total));
        int y = labelY(frame);
        context.text(font, name, x, y, over ? GuiTheme.accentText() : GuiTheme.textDim(), true);
        if (size != null) {
            context.text(font, size, x + nameWidth + LABEL_SPACE, y, GuiTheme.textDim(), true);
        }
    }

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

    // Whilst a piece is held or a box drawn the panel sees no pointer and asks for no cursor of its
    // own.
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
        if (tooltip != null && !tooltip.isEmpty() && GuiTheme.hoverHelp()) {
            Font font = ClientFonts.gui();
            RenderUtil.tooltip(context, font, RenderUtil.wrap(font, tooltip, TIP_WIDTH),
                panelX, panelY, panelW, panelH, GuiTheme.bgTooltip(), GuiTheme.text());
        }
        context.pose().popMatrix();
    }

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
        List<HudElement> pieces = pick.has(element) ? picked(placements()).stream().map(Placement::element).toList()
            : List.of(element);
        List<HudElement> resized = pieces.stream().filter(piece -> !piece.sizeIsDefault()).toList();
        if (resized.isEmpty()) {
            putBackPlaces(pieces);
        } else {
            foot.report(resized.size() == 1 ? "Size put back." : "Sizes put back.",
                Defaults.reset(nameOf(resized), settingsOf(resized, HudElement::sizeSettings)));
        }
    }

    // Each piece goes where it started or as near as it fits where that place is taken. The change
    // is named after the pieces whose place it changed.
    private void putBackPlaces(List<HudElement> pieces) {
        Font font = ClientFonts.hud();
        manager.repin(pieces, font, width, height);
        List<HudElement> changed = new ArrayList<>();
        Defaults.Change change = Defaults.reset(() -> nameOf(changed),
            settingsOf(pieces, HudElement::placeSettings),
            () -> changed.addAll(manager.putBackNear(pieces, font, width, height)));
        if (change == null) {
            long crowded = pieces.stream().filter(piece -> !piece.positionIsDefault()).count();
            foot.refuse(crowded == 0 ? "Nothing to put back."
                : crowded == 1 ? "Its starting place is taken. It is as near as it fits."
                : "Their starting places are taken. They are as near as they fit.");
            return;
        }
        boolean one = changed.size() == 1;
        if (changed.stream().allMatch(HudElement::positionIsDefault)) {
            foot.report(one ? "Put back where it started." : "Put back where they started.", change);
        } else {
            foot.report(one ? "Its starting place is taken. Put as near as it fits."
                : "Put back as near to where they started as they fit.", change);
        }
    }

    private static String nameOf(List<HudElement> pieces) {
        return pieces.size() == 1 ? pieces.getFirst().getName() : Tally.counted(pieces.size(), "HUD piece");
    }

    private static List<Setting<?>> settingsOf(List<HudElement> pieces,
                                               Function<HudElement, List<Setting<?>>> part) {
        List<Setting<?>> settings = new ArrayList<>();
        for (HudElement piece : pieces) {
            settings.addAll(part.apply(piece));
        }
        return settings;
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
        if (held == null && ResetFoot.undoKey(event)) {
            return true;
        }
        return nudge(event) || super.keyPressed(event);
    }

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

    // A part of the game's own HUD moved alone also snaps back to its home. Shift moves freely.
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
            List<Box> others = manager.others(carried.stream().map(Placement::element).toList(),
                ClientFonts.hud(), width, height, false).stream().map(Placement::box).toList();
            dx = snap.stickAcross(group.left() + dx, group.width(), width, others) - group.left();
            dy = snap.stickDown(group.top() + dy, group.height(), height, others) - group.top();
        }
        snap.landed(slide(carried, dx, dy));
    }

    // The game's bars sit a few pixels apart. Home wins over their edges.
    private boolean goHome(Placement piece, int dx, int dy) {
        Placement home = HudManager.homePlacement(piece.element(), ClientFonts.hud(), width, height);
        if (home == null || Math.abs(piece.left() + dx - home.left()) > HudSnap.REACH
            || Math.abs(piece.top() + dy - home.top()) > HudSnap.REACH) {
            return false;
        }
        piece.element().resetPosition();
        snap.home(home.box());
        return true;
    }

    // They stop as one at the screen margin and keep their shape.
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
            pin(piece.element(), piece.left() + across, piece.top() + down, piece.width(), piece.height());
        }
        Box group = around(pieces);
        return new Box(group.left() + across, group.top() + down, group.width(), group.height());
    }

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

    // An edge leaves the far edge where it was. A corner keeps the shape and grows away from the
    // opposite corner. Shift turns off the steps.
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
        Placement now = HudManager.placement(held, ClientFonts.hud(), width, height);
        int left = grip.dx < 0 ? start.left() + start.width() - now.width() : start.left();
        int top = grip.dy < 0 ? start.top() + start.height() - now.height() : start.top();
        pin(held, left, top, now.width(), now.height());
    }

    private static double step(double stretch, boolean free) {
        return free ? stretch : Math.round(stretch / SIZE_STEP) * SIZE_STEP;
    }

    private void pin(HudElement element, int left, int top, int wide, int tall) {
        HudManager.pin(element, left, top, wide, tall, ClientFonts.hud(), width, height);
    }
}
