package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.render.ContainerPreview;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.EnderChest;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

// Your bag drawn on the screen without opening it. Laid out like the inventory
// screen with your armour above and the hotbar below.
public final class InventoryElement extends HudElement {

    private static final int COLUMNS = 9;
    private static final int BAG_ROWS = 3;
    private static final int SLOT = 16;
    // Extra room between the armour and the bag and between the bag and the hotbar.
    private static final int GROUP_GAP = 3;
    // A faint cell under every slot. An empty bag still reads as a bag.
    private static final int CELL = 0x30000000;
    private static final int SELECTED = 0x70FFFFFF;

    private final BoolSetting armour = new BoolSetting("Show armour",
        "Adds a row above the bag for what you wear.", false);
    private final BoolSetting offhand = new BoolSetting("Show offhand",
        "Adds what you hold in your other hand to the top row.", false);
    private final BoolSetting hotbar = new BoolSetting("Show hotbar",
        "Adds the row you carry in hand below the bag. Switch off the Hotbar element to hide"
            + " the game's own.", false);
    private final BoolSetting container = new BoolSetting("Show held container",
        "Shows a shulker box you hold in place of your bag. An ender chest shows what yours held once"
            + " you have opened it in this world.", false);

    private record Cell(ItemStack stack, int x, int y, int color) {
    }

    // What fills the bag rows and the colour of the cells under them.
    private record Bag(List<ItemStack> stacks, int color) {
    }

    public InventoryElement() {
        super("Inventory", "Your inventory on screen without opening it.", false, 4, 30, Background.ON);
        add(armour, offhand, hotbar, container);
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
        int top = 0;
        if (topRow()) {
            if (armour.isOn()) {
                for (int i = 0; i < ItemUtil.ARMOR_SLOTS.size(); i++) {
                    cells.add(cell(player.getItemBySlot(ItemUtil.ARMOR_SLOTS.get(i)), i, top, CELL));
                }
            }
            if (offhand.isOn()) {
                cells.add(cell(player.getOffhandItem(), COLUMNS - 1, top, CELL));
            }
            top += SLOT + GROUP_GAP;
        }
        Inventory inventory = player.getInventory();
        Bag bag = bag(player, inventory);
        for (int i = 0; i < Math.min(BAG_ROWS * COLUMNS, bag.stacks().size()); i++) {
            cells.add(cell(bag.stacks().get(i), i % COLUMNS, top + i / COLUMNS * SLOT, bag.color()));
        }
        if (hotbar.isOn()) {
            int row = top + BAG_ROWS * SLOT + GROUP_GAP;
            for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
                cells.add(cell(inventory.getItem(i), i, row, i == inventory.getSelectedSlot() ? SELECTED : CELL));
            }
        }
        return cells;
    }

    // Your own bag unless Show held container finds a container in your main hand or else your
    // other hand. The cells under a container take a shulker box's dye.
    private Bag bag(LocalPlayer player, Inventory inventory) {
        if (container.isOn()) {
            for (ItemStack held : List.of(player.getMainHandItem(), player.getOffhandItem())) {
                List<ItemStack> inside = contentsOf(held);
                if (!inside.isEmpty()) {
                    return new Bag(inside, ContainerPreview.tintOf(held));
                }
            }
        }
        List<ItemStack> own = new ArrayList<>(BAG_ROWS * COLUMNS);
        for (int i = 0; i < BAG_ROWS * COLUMNS; i++) {
            own.add(inventory.getItem(InventoryUtil.MAIN_START + i));
        }
        return new Bag(own, CELL);
    }

    // The slots of a shulker box or of your ender chest once you have opened it in this world.
    // Empty for anything else.
    private static List<ItemStack> contentsOf(ItemStack held) {
        if (ItemUtil.isShulkerBox(held.getItem()) && held.has(DataComponents.CONTAINER)) {
            return ContainerPreview.contentsOf(held);
        }
        return held.is(Items.ENDER_CHEST) ? EnderChest.INSTANCE.contents() : List.of();
    }

    private static Cell cell(ItemStack stack, int column, int y, int color) {
        return new Cell(stack, column * SLOT, y, color);
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        List<Cell> cells = cells();
        for (Cell cell : cells) {
            context.fill(cell.x(), cell.y(), cell.x() + SLOT - 1, cell.y() + SLOT - 1, cell.color());
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
        return COLUMNS * SLOT;
    }

    @Override
    public int height(Font font) {
        int height = BAG_ROWS * SLOT;
        if (topRow()) {
            height += SLOT + GROUP_GAP;
        }
        if (hotbar.isOn()) {
            height += GROUP_GAP + SLOT;
        }
        return height;
    }
}
