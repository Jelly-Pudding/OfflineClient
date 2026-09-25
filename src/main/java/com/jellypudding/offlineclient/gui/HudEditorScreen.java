package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.HudManager;
import com.jellypudding.offlineclient.hud.HudManager.Placement;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

// Drag the overlay about and pull a corner to resize it. What you see here is
// what you get whilst playing. The overlay keeps its true size and the panel
// listing it follows the ClickGUI scale like every other panel.
public final class HudEditorScreen extends Screen {

    // How close an edge has to be before it sticks.
    private static final int SNAP = 4;

    // An element that draws nothing still needs something to grab.
    private static final int MIN_GRAB = 40;

    private static final int LABEL_GAP = 11;
    // Between an element's name and its size.
    private static final int LABEL_SPACE = 4;

    // The corner square that resizes an element.
    private static final int GRIP = 5;
    private static final int TIP_WIDTH = 170;

    private static final double SCALE_STEP = 0.05;

    // Pulling back past the corner shrinks the element rather than turning
    // it inside out.
    private static final double MIN_PULL = 0.05;

    // A dim wash to stop the world fighting the boxes.
    private static final int SHADE = 0x90000000;

    private final HudManager manager;
    private final SettingHost settings = new SettingHost(this, this::setTooltip);
    private final TextInput textInput = new TextInput(this);
    private final HudElementList list;

    private String tooltip;

    private final LatchedScale panelScale = new LatchedScale();

    private HudElement dragged;
    private HudElement resized;
    private int grabX;
    private int grabY;

    // Frozen when a resize begins. Measuring against the live placement
    // would feed the new scale straight back into the next reading.
    private int originX;
    private int originY;
    private double startScale;
    private double spanX;
    private double spanY;

    public HudEditorScreen(HudManager manager) {
        super(Component.literal("HUD editor"));
        this.manager = manager;
        this.list = new HudElementList(manager.all(), settings);
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
        list.save();
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
        super.onClose();
    }

    private List<Placement> placements() {
        return manager.layout(font, width, height, true);
    }

    // The box you can grab. Never smaller than the name written above it.
    private int[] grabBox(Placement placement) {
        int wide = Math.max(placement.width(), Math.max(MIN_GRAB,
            font.width(placement.element().getName())));
        int tall = Math.max(placement.height(), font.lineHeight);
        return new int[] {placement.left(), placement.top(), wide, tall};
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY,
                                   float partialTicks) {
        textInput.set(settings.isEditing());
        context.fill(0, 0, width, height, SHADE);
        List<Placement> placed = placements();
        for (Placement placement : placed) {
            manager.draw(context, font, placement);
        }
        context.guiRenderState.up();

        HudElement picked = list.getHovered();
        List<int[]> boxes = new ArrayList<>(placed.size());
        for (Placement placement : placed) {
            int[] box = grabBox(placement);
            boxes.add(new int[] {box[0] - 2, box[1] - 2, box[0] + box[2] + 2, box[1] + box[3] + 2});
            HudElement element = placement.element();
            boolean live = element == dragged || element == resized;
            boolean over = live || element == picked || inside(mouseX, mouseY, box);
            int edge = over ? GuiTheme.accentText() : GuiTheme.edge();
            context.outline(box[0] - 2, box[1] - 2, box[2] + 4, box[3] + 4, edge);
            renderLabel(context, box, element, over);
            if (over) {
                renderGrip(context, box, element == resized
                    || overGrip(mouseX, mouseY, box));
            }
        }
        renderPanel(context, mouseX, mouseY, boxes);
    }

    // The name above the box and its size right after it whilst hovered.
    private void renderLabel(GuiGraphicsExtractor context, int[] box, HudElement element,
                             boolean over) {
        String name = element.getName();
        String size = over ? Math.round(element.scale() * 100) + "%" : null;
        int nameWidth = font.width(name);
        int sizeWidth = size == null ? 0 : font.width(size);
        int total = size == null ? nameWidth : nameWidth + LABEL_SPACE + sizeWidth;
        int x = Math.clamp(box[0] - 2, 0, Math.max(0, width - total));
        int y = labelY(box);
        context.text(font, name, x, y, over ? GuiTheme.accentText() : GuiTheme.textDim(), true);
        if (size != null) {
            context.text(font, size, x + nameWidth + LABEL_SPACE, y, GuiTheme.textDim(), true);
        }
    }

    // The panel and its tooltip draw at the ClickGUI scale. The overlay boxes it
    // covers are moved into the same scale first.
    private void renderPanel(GuiGraphicsExtractor context, int mouseX, int mouseY,
                             List<int[]> boxes) {
        panelScale.refresh();
        int panelX = toPanel(mouseX);
        int panelY = toPanel(mouseY);
        int panelW = toPanel(width);
        int panelH = toPanel(height);
        tooltip = null;
        list.update(panelX, panelY, panelW, panelH, 0);
        if (!panelScale.isHeld()) {
            keepInReach(panelW, panelH);
        }
        List<int[]> under = new ArrayList<>(boxes.size());
        for (int[] box : boxes) {
            under.add(RenderUtil.scaled(box[0], box[1], box[2], box[3], panelScale.get()));
        }
        context.pose().pushMatrix();
        context.pose().scale(panelScale.get(), panelScale.get());
        RenderUtil.cover(context, list.bounds(), under, GuiTheme.bgSolid());
        list.render(context, panelX, panelY);
        HudElement hovered = list.isCollapsed() ? null : list.getHovered();
        if (tooltip == null && hovered != null) {
            tooltip = hovered.getDescription();
        }
        if (tooltip != null && !tooltip.isEmpty() && GuiScreenBase.hoverHelp()) {
            RenderUtil.tooltip(context, font, RenderUtil.wrap(font, tooltip, TIP_WIDTH),
                panelX, panelY, panelW, panelH, GuiTheme.bgTooltip(), GuiTheme.text());
        }
        context.pose().popMatrix();
    }

    private int toPanel(double screen) {
        return (int) panelScale.toView(screen);
    }

    // A new scale or a smaller window must never leave the panel out of reach.
    private void keepInReach(int panelW, int panelH) {
        list.setPosition(Math.clamp(list.getX(), 0, Math.max(0, panelW - list.getWidth())),
            Math.clamp(list.getY(), 0, Math.max(0, panelH - GuiTheme.HEADER_HEIGHT)));
    }

    // An element pinned against the top edge has no room above it. Its name
    // drops underneath instead.
    private int labelY(int[] box) {
        int above = box[1] - LABEL_GAP - 2;
        return above < 0 ? box[1] + box[3] + 4 : above;
    }

    // Kept on screen. The grab box is padded out and can reach past an edge
    // the element itself never touches.
    private int[] grip(int[] box) {
        return new int[] {Math.min(box[0] + box[2] + 2, width) - GRIP,
            Math.min(box[1] + box[3] + 2, height) - GRIP};
    }

    private void renderGrip(GuiGraphicsExtractor context, int[] box, boolean hot) {
        int[] at = grip(box);
        context.fill(at[0], at[1], at[0] + GRIP, at[1] + GRIP,
            hot ? GuiTheme.accent() : GuiTheme.accentText());
    }

    private static boolean inside(double mouseX, double mouseY, int[] box) {
        return mouseX >= box[0] - 2 && mouseX <= box[0] + box[2] + 2
            && mouseY >= box[1] - 2 && mouseY <= box[1] + box[3] + 2;
    }

    private boolean overGrip(double mouseX, double mouseY, int[] box) {
        int[] at = grip(box);
        return mouseX >= at[0] && mouseX <= at[0] + GRIP
            && mouseY >= at[1] && mouseY <= at[1] + GRIP;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        panelScale.hold(true);
        settings.beginClick();
        if (list.mouseClicked(toPanel(event.x()), toPanel(event.y()), event.button())) {
            return true;
        }
        List<Placement> placed = placements();
        // Later elements draw on top and take the click first.
        for (int i = placed.size() - 1; i >= 0; i--) {
            Placement placement = placed.get(i);
            int[] box = grabBox(placement);
            if (!inside(event.x(), event.y(), box)) {
                continue;
            }
            HudElement element = placement.element();
            if (InputUtil.isRight(event.button())) {
                element.resetScale();
                OfflineClient.INSTANCE.getConfigManager().saveSoon();
                return true;
            }
            if (overGrip(event.x(), event.y(), box)) {
                beginResize(element, placement, event.x(), event.y());
            } else {
                dragged = element;
                grabX = (int) Math.round(event.x()) - placement.left();
                grabY = (int) Math.round(event.y()) - placement.top();
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void beginResize(HudElement element, Placement placement, double mx, double my) {
        resized = element;
        originX = placement.left();
        originY = placement.top();
        startScale = element.scale();
        spanX = Math.max(1, mx - originX);
        spanY = Math.max(1, my - originY);
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
        if (list.isOver(toPanel(mouseX), toPanel(mouseY))) {
            list.wheel(scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        panelScale.hold(false);
        list.mouseReleased();
        if (dragged != null || resized != null) {
            dragged = null;
            resized = null;
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (resized != null) {
            return resize(event);
        }
        if (dragged == null) {
            return super.mouseDragged(event, dragX, dragY);
        }
        Placement moving = null;
        List<int[]> others = new ArrayList<>();
        for (Placement placement : placements()) {
            if (placement.element() == dragged) {
                moving = placement;
            } else {
                others.add(new int[] {placement.left(), placement.top(),
                    placement.width(), placement.height()});
            }
        }
        if (moving == null) {
            return true;
        }
        // The grab box is padded out to stay clickable. Only the real size
        // may reach the stored position or the element jumps on release.
        int wide = moving.width();
        int tall = moving.height();
        int left = (int) Math.round(event.x()) - grabX;
        int top = (int) Math.round(event.y()) - grabY;
        if (!event.hasShiftDown()) {
            left = snap(left, wide, width, others, true);
            top = snap(top, tall, height, others, false);
        }
        dragged.moveTo(HudManager.shareOf(left, wide, width) * 100,
            HudManager.shareOf(top, tall, height) * 100);
        return true;
    }

    // How far the pointer has moved from where it grabbed the corner
    // measured against where it started. The longer stretch wins.
    private boolean resize(MouseButtonEvent event) {
        double across = (event.x() - originX) / spanX;
        double down = (event.y() - originY) / spanY;
        double wanted = startScale * Math.max(MIN_PULL, Math.max(across, down));
        if (!event.hasShiftDown()) {
            wanted = Math.round(wanted / SCALE_STEP) * SCALE_STEP;
        }
        resized.setScale(wanted);
        return true;
    }

    // Sticks to the screen edges and middle and to the edges of the other elements.
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
        for (int stop : stops) {
            if (Math.abs(edge - stop) <= SNAP) {
                return stop;
            }
        }
        return edge;
    }
}
