package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.render.BetterTooltips;
import com.jellypudding.offlineclient.modules.render.Blur;
import com.jellypudding.offlineclient.render.ContainerPreview;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.List;

// A window that shows what a container item holds without placing it.
// Clicking an item inside that has contents of its own opens another window.
public final class PeekScreen extends Screen {

    private static final int COLUMNS = 9;
    private static final int SLOT = 18;
    private static final int PAD = 8;
    private static final int TITLE_HEIGHT = 14;

    private final Screen parent;
    private final ContainerPreview grid;
    private final int gridWidth;
    private final int gridHeight;
    private int hovered = -1;

    public PeekScreen(Screen parent, Component title, List<ItemStack> items, int background) {
        super(title);
        this.parent = parent;
        grid = new ContainerPreview(items, COLUMNS, background);
        Font font = OfflineClient.MC.font;
        gridWidth = grid.getWidth(font);
        gridHeight = grid.getHeight(font);
    }

    private int left() {
        return (width - gridWidth) / 2;
    }

    private int top() {
        return (height - gridHeight) / 2 + TITLE_HEIGHT / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        Blur blur = Modules.get(Blur.class);
        if (blur != null && blur.wants(this)) {
            blur.blurHere(context);
        }
        context.fillGradient(0, 0, width, height, 0x70101018, 0xA0060610);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
        Font font = OfflineClient.MC.font;
        int x = left();
        int y = top();
        RenderUtil.roundedBorderedRect(context, x - PAD, y - TITLE_HEIGHT - PAD, x + gridWidth + PAD,
            y + gridHeight + PAD, GuiTheme.CORNER, GuiTheme.bgWindow(), GuiTheme.edge());
        context.centeredText(font, getTitle(), width / 2, y - TITLE_HEIGHT - 1, GuiTheme.text());
        hovered = grid.drawAt(context, font, x, y, mouseX, mouseY);
        ItemStack stack = grid.item(hovered);
        if (!stack.isEmpty()) {
            context.setTooltipForNextFrame(font, stack, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        BetterTooltips tooltips = Modules.get(BetterTooltips.class);
        ItemStack stack = grid.item(hovered);
        if (tooltips != null && !stack.isEmpty() && tooltips.wantsToOpen(event) && tooltips.openContents(stack)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        BetterTooltips tooltips = Modules.get(BetterTooltips.class);
        ItemStack stack = grid.item(hovered);
        if (tooltips != null && !stack.isEmpty() && tooltips.wantsToOpen(event) && tooltips.openContents(stack)) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        OfflineClient.MC.gui.setScreen(parent);
    }
}
