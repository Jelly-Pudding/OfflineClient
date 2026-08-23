package com.jellypudding.offlineclient.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;

// A grid of items drawn inside a tooltip.
public final class ContainerPreview implements ClientTooltipComponent {

    private static final int SLOT = 18;
    private static final int PADDING = 2;
    private static final int BACKGROUND = 0xC0100010;
    private static final int BORDER = 0x60FFFFFF;

    private final List<ItemStack> items;
    private final int columns;
    private final int rows;

    public ContainerPreview(List<ItemStack> items, int columns) {
        this.items = items;
        this.columns = Math.max(1, Math.min(columns, items.size()));
        this.rows = Math.max(1, (items.size() + this.columns - 1) / this.columns);
    }

    @Override
    public int getWidth(Font font) {
        return columns * SLOT + PADDING;
    }

    @Override
    public int getHeight(Font font) {
        return rows * SLOT + PADDING;
    }

    @Override
    public void extractImage(Font font, int x, int y, int tooltipWidth, int tooltipHeight,
                             GuiGraphicsExtractor context) {
        int width = columns * SLOT + PADDING;
        int height = rows * SLOT + PADDING;
        context.fill(x, y, x + width, y + height, BACKGROUND);
        context.outline(x, y, width, height, BORDER);
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            int slotX = x + PADDING + i % columns * SLOT;
            int slotY = y + PADDING + i / columns * SLOT;
            context.item(stack, slotX, slotY);
            context.itemDecorations(font, stack, slotX, slotY);
        }
    }
}
