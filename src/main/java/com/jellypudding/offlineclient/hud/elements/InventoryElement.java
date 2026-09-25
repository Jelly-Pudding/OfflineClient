package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

// Your bag drawn on the screen without opening it. Laid out like the inventory
// screen with your armour above and the hotbar below.
public final class InventoryElement extends HudElement {

    private static final int COLUMNS = 9;
    private static final int BAG_ROWS = 3;
    private static final int SLOT = 16;
    private static final int PAD = 2;
    // Extra room between the armour and the bag and between the bag and the hotbar.
    private static final int GROUP_GAP = 3;
    // A faint cell under every slot. An empty bag still reads as a bag.
    private static final int CELL = 0x30000000;
    private static final int SELECTED = 0x70FFFFFF;

    private final BoolSetting armour = new BoolSetting("Show armour",
        "Add a row above the bag for what you wear.", false);
    private final BoolSetting offhand = new BoolSetting("Show offhand",
        "Add what you hold in your other hand to the top row.", false);
    private final BoolSetting hotbar = new BoolSetting("Show hotbar",
        "Add the row you carry in hand below the bag.", false);
    private final BoolSetting hideGameHotbar = new BoolSetting("Hide game hotbar",
        "Hide the game's own hotbar. This one takes its place.", false).under(hotbar);
    private final BoolSetting background = new BoolSetting("Inventory background",
        "Draw a panel behind the slots.", true);
    private final ColorSetting backgroundColor = new ColorSetting("Inventory background colour",
        "Colour of that panel.", 240, 0.3f, 0.12f, false).under(background);

    private record Cell(ItemStack stack, int x, int y, boolean selected) {
    }

    public InventoryElement() {
        super("Inventory", "Your inventory on screen without opening it.", false, 4, 30);
        add(armour, offhand, hotbar, hideGameHotbar, background, backgroundColor);
    }

    // True whilst this stands in for the game's hotbar.
    public boolean hidesGameHotbar() {
        return isActive() && hotbar.isOn() && hideGameHotbar.isOn();
    }

    @Override
    public boolean visible() {
        return isActive() && OfflineClient.MC.player != null;
    }

    private boolean topRow() {
        return armour.isOn() || offhand.isOn();
    }

    // The armour sits at the left of the top row and the other hand at its right.
    private List<Cell> cells() {
        LocalPlayer player = OfflineClient.MC.player;
        List<Cell> cells = new ArrayList<>();
        if (player == null) {
            return cells;
        }
        int top = PAD;
        if (topRow()) {
            if (armour.isOn()) {
                for (int i = 0; i < ItemUtil.ARMOR_SLOTS.size(); i++) {
                    cells.add(cell(player.getItemBySlot(ItemUtil.ARMOR_SLOTS.get(i)), i, top, false));
                }
            }
            if (offhand.isOn()) {
                cells.add(cell(player.getOffhandItem(), COLUMNS - 1, top, false));
            }
            top += SLOT + GROUP_GAP;
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < BAG_ROWS * COLUMNS; i++) {
            cells.add(cell(inventory.getItem(InventoryUtil.MAIN_START + i), i % COLUMNS,
                top + i / COLUMNS * SLOT, false));
        }
        if (hotbar.isOn()) {
            int row = top + BAG_ROWS * SLOT + GROUP_GAP;
            for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
                cells.add(cell(inventory.getItem(i), i, row, i == inventory.getSelectedSlot()));
            }
        }
        return cells;
    }

    private static Cell cell(ItemStack stack, int column, int y, boolean selected) {
        return new Cell(stack, PAD + column * SLOT, y, selected);
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        List<Cell> cells = cells();
        if (background.isOn()) {
            context.fill(0, 0, width(font), height(font), backgroundColor.getColor());
        }
        for (Cell cell : cells) {
            context.fill(cell.x(), cell.y(), cell.x() + SLOT - 1, cell.y() + SLOT - 1,
                cell.selected() ? SELECTED : CELL);
        }
        context.guiRenderState.up();
        for (Cell cell : cells) {
            if (!cell.stack().isEmpty()) {
                context.item(cell.stack(), cell.x(), cell.y());
                context.itemDecorations(font, cell.stack(), cell.x(), cell.y());
            }
        }
    }

    @Override
    public int width(Font font) {
        return COLUMNS * SLOT + PAD * 2;
    }

    @Override
    public int height(Font font) {
        int height = BAG_ROWS * SLOT + PAD * 2;
        if (topRow()) {
            height += SLOT + GROUP_GAP;
        }
        if (hotbar.isOn()) {
            height += GROUP_GAP + SLOT;
        }
        return height;
    }
}
