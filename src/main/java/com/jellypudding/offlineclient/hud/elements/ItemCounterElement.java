package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

public final class ItemCounterElement extends HudElement {

    public enum Layout { ACROSS, DOWN }

    private static final int SLOT = 16;
    private static final int GAP = 3;
    private static final int LINE = 10;

    private record Tally(ItemStack stack, int count) {
    }

    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Counted items",
        "The items whose totals are shown.", BuiltInRegistries.ITEM,
        List.of(Items.TOTEM_OF_UNDYING, Items.ENCHANTED_GOLDEN_APPLE, Items.END_CRYSTAL,
            Items.OBSIDIAN, Items.ENDER_PEARL));
    private final EnumSetting<Layout> layout = new EnumSetting<>("Counter layout",
        "Which way the totals are laid out.", Layout.ACROSS)
        .describe(Layout.ACROSS, "In a row.")
        .describe(Layout.DOWN, "In a column.");
    private final BoolSetting icons = new BoolSetting("Counter icons",
        "Draw the item next to its total.", true);
    private final BoolSetting hideEmpty = new BoolSetting("Hide empty counts",
        "Leave out an item you are carrying none of.", true);

    public ItemCounterElement() {
        super("Item counter", "How many of the items you pick you are carrying.", false, 50, 96);
        add(items, layout, icons, hideEmpty);
    }

    @Override
    public boolean visible() {
        return isActive() && !tallies().isEmpty();
    }

    private List<Tally> tallies() {
        Player player = OfflineClient.MC.player;
        List<Tally> found = new ArrayList<>();
        if (player == null) {
            return found;
        }
        for (Item item : items.resolved()) {
            int count = InventoryUtil.count(item, InventoryUtil.WHOLE_INVENTORY)
                + (player.getOffhandItem().is(item) ? player.getOffhandItem().getCount() : 0);
            if (count > 0 || !hideEmpty.isOn()) {
                found.add(new Tally(new ItemStack(item), count));
            }
        }
        return found;
    }

    // Down the column the icon leads and across the row the number sits under it.
    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        List<Tally> found = tallies();
        boolean across = layout.is(Layout.ACROSS);
        int step = across ? cellWidth(font) + GAP : cellHeight(font) + GAP;
        for (int i = 0; i < found.size(); i++) {
            Tally tally = found.get(i);
            int x = across ? i * step : 0;
            int y = across ? 0 : i * step;
            String label = String.valueOf(tally.count());
            if (!icons.isOn()) {
                context.text(font, label, x, y, 0xFFECECF4, true);
                continue;
            }
            context.item(tally.stack(), x, y);
            if (across) {
                context.text(font, label, x + (SLOT - font.width(label)) / 2, y + SLOT,
                    0xFFECECF4, true);
            } else {
                context.text(font, label, x + SLOT + GAP, y + (SLOT - font.lineHeight) / 2 + 1,
                    0xFFECECF4, true);
            }
        }
    }

    private int cellWidth(Font font) {
        if (!icons.isOn()) {
            return widestLabel(font);
        }
        return layout.is(Layout.ACROSS) ? SLOT : SLOT + GAP + widestLabel(font);
    }

    private int cellHeight(Font font) {
        if (!icons.isOn()) {
            return LINE;
        }
        return layout.is(Layout.ACROSS) ? SLOT + font.lineHeight : SLOT;
    }

    private int widestLabel(Font font) {
        int widest = 1;
        for (Tally tally : tallies()) {
            widest = Math.max(widest, font.width(String.valueOf(tally.count())));
        }
        return widest;
    }

    @Override
    public int width(Font font) {
        int count = Math.max(1, tallies().size());
        return layout.is(Layout.ACROSS)
            ? count * (cellWidth(font) + GAP) - GAP : cellWidth(font);
    }

    @Override
    public int height(Font font) {
        int count = Math.max(1, tallies().size());
        return layout.is(Layout.ACROSS)
            ? cellHeight(font) : count * (cellHeight(font) + GAP) - GAP;
    }
}
