package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.HudManager;
import com.jellypudding.offlineclient.hud.HudManager.Placement;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

// Drag the overlay about. What you see here is what you get whilst playing.
public final class HudEditorScreen extends Screen {

    // How close an edge has to be before it sticks.
    private static final int SNAP = 4;

    // An element that draws nothing still needs something to grab.
    private static final int MIN_GRAB = 40;

    private static final int LABEL_GAP = 11;

    // A dim wash to stop the world fighting the boxes.
    private static final int SHADE = 0x90000000;

    private final HudManager manager;

    private HudElement dragged;
    private int grabX;
    private int grabY;

    public HudEditorScreen(HudManager manager) {
        super(Component.literal("HUD editor"));
        this.manager = manager;
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
        for (Placement placement : placements()) {
            manager.draw(context, font, placement);
        }
        context.guiRenderState.up();
        for (Placement placement : placements()) {
            int[] box = grabBox(placement);
            boolean over = placement.element() == dragged
                || inside(mouseX, mouseY, box);
            context.outline(box[0] - 2, box[1] - 2, box[2] + 4, box[3] + 4,
                over ? GuiTheme.accentText() : GuiTheme.edge());
            context.text(font, placement.element().getName(), box[0] - 2,
                box[1] - LABEL_GAP - 2, over ? GuiTheme.accentText() : GuiTheme.textDim(), true);
        }
        String hint = "Drag to move. Hold shift to ignore snapping. Escape to finish.";
        context.text(font, hint, (width - font.width(hint)) / 2, height - 14,
            GuiTheme.textDim(), true);
    }

    private static boolean inside(double mouseX, double mouseY, int[] box) {
        return mouseX >= box[0] - 2 && mouseX <= box[0] + box[2] + 2
            && mouseY >= box[1] - 2 && mouseY <= box[1] + box[3] + 2;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        List<Placement> placed = placements();
        // Later elements draw on top and take the click first.
        for (int i = placed.size() - 1; i >= 0; i--) {
            Placement placement = placed.get(i);
            if (inside(event.x(), event.y(), grabBox(placement))) {
                dragged = placement.element();
                grabX = (int) Math.round(event.x()) - placement.left();
                grabY = (int) Math.round(event.y()) - placement.top();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragged != null) {
            dragged = null;
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragged == null) {
            return super.mouseDragged(event, dragX, dragY);
        }
        Placement moving = null;
        List<int[]> others = new ArrayList<>();
        for (Placement placement : placements()) {
            if (placement.element() == dragged) {
                moving = placement;
            } else {
                others.add(grabBox(placement));
            }
        }
        if (moving == null) {
            return true;
        }
        int[] box = grabBox(moving);
        int left = (int) Math.round(event.x()) - grabX;
        int top = (int) Math.round(event.y()) - grabY;
        if (!event.hasShiftDown()) {
            left = snap(left, box[2], width, others, true);
            top = snap(top, box[3], height, others, false);
        }
        dragged.moveTo(HudManager.shareOf(left, box[2], width) * 100,
            HudManager.shareOf(top, box[3], height) * 100);
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
