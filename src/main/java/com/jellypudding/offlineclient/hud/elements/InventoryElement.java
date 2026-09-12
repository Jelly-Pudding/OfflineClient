package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

// Your bag drawn on the screen without opening it.
public final class InventoryElement extends HudElement {

    private static final int COLUMNS = 9;
    private static final int SLOT = 16;
    private static final int PAD = 2;

    private final BoolSetting hotbar = new BoolSetting("Show hotbar",
        "Include the row you carry in hand.", false);
    private final BoolSetting background = new BoolSetting("Inventory background",
        "Draw a panel behind the slots.", true);
    private final ColorSetting backgroundColor = new ColorSetting("Inventory background colour",
        "Colour of that panel.", 240, 0.3f, 0.12f, false).under(background);

    public InventoryElement() {
        super("Inventory", "Every item in your bag.", false, 4, 30);
        add(hotbar, background, backgroundColor);
    }

    @Override
    public boolean visible() {
        return isActive() && OfflineClient.MC.player != null;
    }

    // The bag runs from slot nine upward. The hotbar is the first nine slots.
    private List<ItemStack> stacks() {
        LocalPlayer player = OfflineClient.MC.player;
        List<ItemStack> items = new ArrayList<>(InventoryUtil.WHOLE_INVENTORY);
        if (player == null) {
            return items;
        }
        int first = hotbar.isOn() ? 0 : InventoryUtil.HOTBAR_SIZE;
        for (int i = first; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            items.add(player.getInventory().getItem(i));
        }
        return items;
    }

    private int rows() {
        return Math.max(1, (stacks().size() + COLUMNS - 1) / COLUMNS);
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        List<ItemStack> items = stacks();
        if (background.isOn()) {
            context.fill(0, 0, width(font), height(font), backgroundColor.getColor());
        }
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            int x = PAD + i % COLUMNS * SLOT;
            int y = PAD + i / COLUMNS * SLOT;
            context.item(stack, x, y);
            context.itemDecorations(font, stack, x, y);
        }
    }

    @Override
    public int width(Font font) {
        return COLUMNS * SLOT + PAD * 2;
    }

    @Override
    public int height(Font font) {
        return rows() * SLOT + PAD * 2;
    }
}
