package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.List;

// A grid of items drawn inside a tooltip.
public final class ContainerPreview implements ClientTooltipComponent {

    private static final int SLOT = 18;
    private static final int PADDING = 2;
    private static final int BACKGROUND = 0xC0100010;
    private static final int SHULKER_SLOTS = 27;
    // How far a dyed box background sits from black towards its dye.
    private static final float TINT_SHADE = 0.45f;
    private static final int BORDER = 0x60FFFFFF;

    private final List<ItemStack> items;
    private final int columns;
    private final int rows;
    private final int background;

    // The stacks of a shulker box item with the empty slots in place.
    public static List<ItemStack> contentsOf(ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(SHULKER_SLOTS, ItemStack.EMPTY);
        stack.get(DataComponents.CONTAINER).copyInto(items);
        return items;
    }

    // A shulker box previews on a dark shade of its own dye.
    public static int tintOf(ItemStack stack) {
        if (!(Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock box) || box.getColor() == null) {
            return BACKGROUND;
        }
        int dye = box.getColor().getTextureDiffuseColor();
        int shaded = ColorUtil.lerp(0xFF000000, dye | 0xFF000000, TINT_SHADE);
        return ColorUtil.withAlpha(shaded, BACKGROUND >>> 24);
    }

    public ContainerPreview(List<ItemStack> items, int columns) {
        this(items, columns, BACKGROUND);
    }

    // A background of the box's own dye colour. A red shulker previews red.
    public ContainerPreview(List<ItemStack> items, int columns, int background) {
        this.items = items;
        this.columns = Math.max(1, Math.min(columns, items.size()));
        this.rows = Math.max(1, (items.size() + this.columns - 1) / this.columns);
        this.background = background;
    }

    // Draws the grid at a point on any screen and returns the index under the mouse or minus one.
    public int drawAt(GuiGraphicsExtractor context, Font font, int x, int y, int mouseX, int mouseY) {
        extractImage(font, x, y, 0, 0, context);
        int hovered = -1;
        for (int i = 0; i < items.size(); i++) {
            int slotX = x + PADDING + i % columns * SLOT;
            int slotY = y + PADDING + i / columns * SLOT;
            if (mouseX >= slotX && mouseX < slotX + SLOT && mouseY >= slotY && mouseY < slotY + SLOT) {
                hovered = i;
            }
        }
        return hovered;
    }

    public ItemStack item(int index) {
        return index < 0 || index >= items.size() ? ItemStack.EMPTY : items.get(index);
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
        context.fill(x, y, x + width, y + height, background);
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
