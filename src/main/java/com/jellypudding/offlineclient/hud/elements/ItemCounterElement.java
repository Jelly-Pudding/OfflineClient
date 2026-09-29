package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.gui.GuiTheme;
import com.jellypudding.offlineclient.hud.Backdrop;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.HudLayout;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class ItemCounterElement extends HudElement {

    // What an item you carry none of shows.
    public enum Empty { HIDE, ZERO, GREY }

    private static final int SLOT = 16;
    private static final int GAP = 3;

    // A dark wash over an item you carry none of and the colour of its nought without icons.
    private static final int GREYED = 0xA0000000;
    private static final int GREY_TEXT = 0xFF808080;

    private record Counted(ItemStack stack, int count) {
    }

    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Counted items",
        "The items whose totals are shown.", BuiltInRegistries.ITEM,
        List.of(Items.TOTEM_OF_UNDYING, Items.ENCHANTED_GOLDEN_APPLE, Items.END_CRYSTAL,
            Items.OBSIDIAN, Items.ENDER_PEARL));
    private final NumberSetting leastDurability = new NumberSetting("Least durability",
        "Leaves out items with less durability left than this such as a spent elytra. Nought counts"
            + " every one.", 0, 0, 100, 1, "%").min(0).max(100);
    private final BoolSetting worn = new BoolSetting("Count worn items",
        "Also counts what you wear such as the elytra on your back.", true);
    private final EnumSetting<HudLayout> layout = HudLayout.setting("Counter layout",
        "Which way the totals are laid out.");
    private final BoolSetting icons = new BoolSetting("Counter icons",
        "Draw the item next to its total.", true);
    private final EnumSetting<Empty> empty = new EnumSetting<>("Empty counts",
        "What an item you carry none of shows.", Empty.HIDE)
        .describe(Empty.HIDE, "Leaves the item out.")
        .describe(Empty.ZERO, "Shows the item with a nought.")
        .describe(Empty.GREY, "Shows the item greyed out.");
    private final Backdrop backdrop = new Backdrop("Counter", "the totals", false);

    public ItemCounterElement() {
        super("Item counter", "How many of the items you pick you are carrying.", false, 50, 96);
        add(items, leastDurability, worn, layout, icons, empty);
        add(backdrop.settings());
    }

    @Override
    public boolean visible() {
        return isActive() && !tallies().isEmpty();
    }

    private List<Counted> tallies() {
        Player player = OfflineClient.MC.player;
        List<Counted> found = new ArrayList<>();
        if (player == null) {
            return found;
        }
        for (Item item : items.resolved()) {
            int count = count(player, item);
            if (count > 0 || !empty.is(Empty.HIDE)) {
                found.add(new Counted(new ItemStack(item), count));
            }
        }
        return found;
    }

    // The bag and the other hand and with Count worn items on whatever you wear.
    private int count(Player player, Item item) {
        Predicate<ItemStack> counted = stack -> stack.is(item) && durableEnough(stack);
        int total = InventoryUtil.count(counted, InventoryUtil.WHOLE_INVENTORY)
            + countOf(player.getOffhandItem(), counted);
        if (worn.isOn()) {
            for (EquipmentSlot slot : ItemUtil.ARMOR_SLOTS) {
                total += countOf(player.getItemBySlot(slot), counted);
            }
        }
        return total;
    }

    private static int countOf(ItemStack stack, Predicate<ItemStack> counted) {
        return counted.test(stack) ? stack.getCount() : 0;
    }

    // Anything that never wears down always counts.
    private boolean durableEnough(ItemStack stack) {
        return !stack.isDamageableItem() || ItemUtil.durabilityPercent(stack) >= leastDurability.getValue();
    }

    private boolean greyed(Counted tally) {
        return tally.count() == 0 && empty.is(Empty.GREY);
    }

    // A greyed item says it all with its icon. Without icons its nought stays.
    private String labelOf(Counted tally) {
        return greyed(tally) && icons.isOn() ? "" : String.valueOf(tally.count());
    }

    // Down the column the icon leads and across the row the number sits under it.
    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        List<Counted> found = tallies();
        if (backdrop.around(context, width(font), height(font))) {
            context.guiRenderState.up();
        }
        boolean across = layout.is(HudLayout.ACROSS);
        int step = across ? cellWidth(font) + GAP : cellHeight(font) + GAP;
        for (int i = 0; i < found.size(); i++) {
            Counted tally = found.get(i);
            int x = across ? i * step : 0;
            int y = across ? 0 : i * step;
            String label = labelOf(tally);
            int tint = greyed(tally) ? GREY_TEXT : GuiTheme.HUD_TEXT;
            if (!icons.isOn()) {
                context.text(font, label, x, y, tint, true);
                continue;
            }
            context.item(tally.stack(), x, y);
            if (greyed(tally)) {
                // The wash goes on a layer above the item it darkens.
                context.guiRenderState.up();
                context.fill(x, y, x + SLOT, y + SLOT, GREYED);
            } else if (across) {
                context.text(font, label, x + (SLOT - font.width(label)) / 2, y + SLOT, tint, true);
            } else {
                context.text(font, label, x + SLOT + GAP, y + (SLOT - font.lineHeight) / 2 + 1, tint, true);
            }
        }
    }

    private int cellWidth(Font font) {
        if (!icons.isOn()) {
            return widestLabel(font);
        }
        return layout.is(HudLayout.ACROSS) ? SLOT : SLOT + GAP + widestLabel(font);
    }

    private int cellHeight(Font font) {
        if (!icons.isOn()) {
            return TextRow.LINE;
        }
        return layout.is(HudLayout.ACROSS) ? SLOT + font.lineHeight : SLOT;
    }

    private int widestLabel(Font font) {
        int widest = 1;
        for (Counted tally : tallies()) {
            widest = Math.max(widest, font.width(labelOf(tally)));
        }
        return widest;
    }

    @Override
    public int width(Font font) {
        int count = Math.max(1, tallies().size());
        return layout.is(HudLayout.ACROSS)
            ? count * (cellWidth(font) + GAP) - GAP : cellWidth(font);
    }

    @Override
    public int height(Font font) {
        int count = Math.max(1, tallies().size());
        return layout.is(HudLayout.ACROSS)
            ? cellHeight(font) : count * (cellHeight(font) + GAP) - GAP;
    }
}
