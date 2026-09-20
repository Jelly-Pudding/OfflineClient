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
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

// Drag the overlay about and pull a corner to resize it. What you see here is
// what you get whilst playing.
public final class HudEditorScreen extends Screen {

    // How close an edge has to be before it sticks.
    private static final int SNAP = 4;

    // An element that draws nothing still needs something to grab.
    private static final int MIN_GRAB = 40;

    private static final int LABEL_GAP = 11;

    // The corner square that resizes an element.
    private static final int GRIP = 5;

    private static final double SCALE_STEP = 0.05;

    // Pulling back past the corner shrinks the element rather than turning
    // it inside out.
    private static final double MIN_PULL = 0.05;

    // A dim wash to stop the world fighting the boxes.
    private static final int SHADE = 0x90000000;

    private final HudManager manager;
    private final HudElementList list;

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
        this.list = new HudElementList(manager.all());
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
    public void onClose() {
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
        context.fill(0, 0, width, height, SHADE);
        List<Placement> placed = placements();
        for (Placement placement : placed) {
            manager.draw(context, font, placement);
        }
        context.guiRenderState.up();

        HudElement picked = list.getHovered();
        for (Placement placement : placed) {
            int[] box = grabBox(placement);
            HudElement element = placement.element();
            boolean live = element == dragged || element == resized;
            boolean over = live || element == picked || inside(mouseX, mouseY, box);
            int edge = over ? GuiTheme.accentText() : GuiTheme.edge();
            context.outline(box[0] - 2, box[1] - 2, box[2] + 4, box[3] + 4, edge);
            context.text(font, element.getName(), box[0] - 2, labelY(box),
                over ? GuiTheme.accentText() : GuiTheme.textDim(), true);
            if (over) {
                renderGrip(context, box, element == resized
                    || overGrip(mouseX, mouseY, box));
                String size = Math.round(element.scale() * 100) + "%";
                context.text(font, size,
                    Math.min(box[0] + box[2] + 4, width) - font.width(size),
                    labelY(box), GuiTheme.textDim(), true);
            }
        }
        list.render(context, height, mouseX, mouseY);
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
        if (list.mouseClicked(event.x(), event.y(), event.button())) {
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
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (list.isOver(mouseX, mouseY)) {
            list.wheel(scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        list.release();
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
