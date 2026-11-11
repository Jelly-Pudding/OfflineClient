package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EnchantText;
import com.jellypudding.offlineclient.util.GearKind;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

// The list is built again every tick a container screen is open and drawn beside it. A click
// on a row moves that stack across. A click on a heading takes the whole group out of the
// container or puts it in when only you hold it.
public final class LootList extends Module {

    public enum Side { LEFT, RIGHT }

    // One line of the list. Its slots are what a click moves and what the highlight paints.
    private record Row(Component text, ItemStack icon, List<Integer> slots, boolean heading) {
    }

    // The rows under one heading.
    private record Group(String name, List<Row> rows) {
    }

    private static final Set<GearKind> ARMOUR = EnumSet.of(GearKind.HELMETS, GearKind.CHESTPLATES,
        GearKind.LEGGINGS, GearKind.BOOTS);
    private static final Set<GearKind> TOOLS = EnumSet.of(GearKind.AXES, GearKind.PICKAXES,
        GearKind.SHOVELS, GearKind.HOES);
    private static final Set<GearKind> WEAPONS = EnumSet.of(GearKind.SWORDS, GearKind.AXES,
        GearKind.SPEARS, GearKind.MACES, GearKind.TRIDENTS);

    // Gear groups come first by kind then the books then the potions.
    private static final int OTHER_GEAR = GearKind.values().length;
    private static final int BOOKS = 100;
    private static final int POTIONS = 200;
    // Book groups for an enchantment that fits several kinds of gear.
    private static final int ANY_ARMOUR = OTHER_GEAR + 1;
    private static final int ANY_TOOL = OTHER_GEAR + 2;
    private static final int ANY_WEAPON = OTHER_GEAR + 3;
    private static final int ANY_GEAR = OTHER_GEAR + 4;
    private static final Map<Integer, String> MIXED_BOOKS = Map.of(ANY_ARMOUR, "armour", ANY_TOOL, "tools",
        ANY_WEAPON, "weapons", ANY_GEAR, "any gear");

    // One stack of each kind of gear to ask which kinds an enchantment fits. Each is built on first
    // use since item components are bound after the classes load.
    private static final Map<GearKind, ItemStack> SAMPLES = new EnumMap<>(GearKind.class);

    // Drinkable then splash then lingering.
    private static final List<Item> POTION_KINDS = List.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION);
    private static final List<String> POTION_GROUPS = List.of("Potions", "Splash potions", "Lingering potions");

    // Letters of each enchantment name written after a piece of gear and after a book.
    private static final int GEAR_LETTERS = 3;
    private static final int BOOK_LETTERS = 20;

    // Sizes in the list's own units before Scale.
    private static final int ROW_HEIGHT = 10;
    private static final int ICON_SIZE = 8;
    private static final float ICON_SCALE = 0.5f;
    private static final int PADDING = 3;
    private static final int GAP = 4;
    // Narrower than this the list is not worth drawing.
    private static final int MIN_WIDTH = 60;

    private static final int BACKGROUND = 0xC0101010;
    private static final int HEADING = 0xFF55FFFF;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int INSIDE = 0xFFAAAAAA;
    private static final int ROW_HOVER = 0x30FFFFFF;
    private static final int SLOT_PAINT = 0x8055FFFF;

    private final EnumSetting<Side> side = new EnumSetting<>("Side",
        "Which side of the container the list sits on.", Side.RIGHT)
        .describe(Side.LEFT, "Left of the container.")
        .describe(Side.RIGHT, "Right of the container.");
    private final BoolSetting yours = new BoolSetting("Your inventory",
        "Also lists what you carry.", false);
    private final BoolSetting shulkers = new BoolSetting("Shulker contents",
        "Also lists what the shulker boxes in the container hold.", true);
    private final BoolSetting highlight = new BoolSetting("Highlight",
        "Paints the slot of the row under the pointer.", true);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the list.", 1, 0.5, 2, 0.05).min(0.25);

    private List<Row> rows = List.of();
    // The width of the widest row in the list's own units. Measured whenever the rows are built.
    private int widest;
    private int scroll;
    // Where the list was drawn last in screen units. A width of nought means it was not drawn.
    private int left;
    private int top;
    private int width;
    private int visible;
    private float drawnScale = 1;
    private Row hovered;

    public LootList() {
        super("LootList", "Lists the enchanted gear and books and potions in an open container beside it.",
            Category.PLAYER);
        addSettings(side, yours, shulkers, highlight, scale);
        searchTags("enchantment handler", "enchanted books", "potions", "loot");
    }

    @Override
    protected void onDisable() {
        rows = List.of();
        width = 0;
        hovered = null;
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (mc.player == null || !(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)
            || !InventoryUtil.isStorage(screen)) {
            rows = List.of();
            scroll = 0;
            return;
        }
        rows = build(screen.getMenu());
        widest = measure(rows);
    }

    // The groups by rank with each heading above its rows.
    private List<Row> build(AbstractContainerMenu menu) {
        Map<Integer, Group> groups = new TreeMap<>();
        for (Slot slot : menu.slots) {
            if (!MenuClicks.isContainerSlot(slot) && !(yours.isOn() && MenuClicks.isInventorySlot(slot))) {
                continue;
            }
            ItemStack stack = slot.getItem();
            add(groups, stack, slot.index, false);
            ItemContainerContents inside = stack.get(DataComponents.CONTAINER);
            if (shulkers.isOn() && inside != null && ItemUtil.isShulkerBox(stack.getItem())) {
                inside.nonEmptyItemCopyStream().forEach(held -> add(groups, held, slot.index, true));
            }
        }
        List<Row> built = new ArrayList<>();
        for (Group group : groups.values()) {
            Set<Integer> slots = new LinkedHashSet<>();
            group.rows().forEach(row -> slots.addAll(row.slots()));
            List<Integer> inContainer = slots.stream()
                .filter(slot -> MenuClicks.isContainerSlot(menu.slots.get(slot))).toList();
            built.add(new Row(Component.literal(group.name() + " " + group.rows().size()).withColor(HEADING),
                ItemStack.EMPTY, inContainer.isEmpty() ? List.copyOf(slots) : inContainer, true));
            built.addAll(group.rows());
        }
        return built;
    }

    // The width of the widest row in the list's own units.
    private int measure(List<Row> listed) {
        int most = 0;
        for (Row row : listed) {
            most = Math.max(most, mc.font.width(row.text()) + (row.heading() ? 0 : ICON_SIZE + PADDING));
        }
        return most;
    }

    // A stack inside a shulker box moves with its box and is written dimmer.
    private static void add(Map<Integer, Group> groups, ItemStack stack, int slot, boolean inBox) {
        int rank = rank(stack);
        if (rank < 0) {
            return;
        }
        int letters = stack.is(Items.ENCHANTED_BOOK) ? BOOK_LETTERS : GEAR_LETTERS;
        String name = stack.getHoverName().getString();
        MutableComponent text = Component.literal(name.isBlank() ? stack.getItemName().getString() : name)
            .withColor(inBox ? INSIDE : TEXT);
        for (EnchantText.Label label : EnchantText.of(stack, id -> true, letters)) {
            text.append(Component.literal(" " + label.text()).withColor(label.color()));
        }
        groups.computeIfAbsent(rank, _ -> new Group(groupName(rank), new ArrayList<>()))
            .rows().add(new Row(text, stack.copy(), List.of(slot), false));
    }

    // Where a stack is listed. Minus one for anything that is no enchanted gear or book or potion.
    private static int rank(ItemStack stack) {
        if (stack.is(Items.ENCHANTED_BOOK)) {
            return BOOKS + bookRank(stack);
        }
        int potion = POTION_KINDS.indexOf(stack.getItem());
        if (potion >= 0) {
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            return contents != null && contents.hasEffects() ? POTIONS + potion : -1;
        }
        if (!stack.isEnchanted()) {
            return -1;
        }
        GearKind kind = GearKind.of(stack);
        return kind == null ? OTHER_GEAR : kind.ordinal();
    }

    // A book goes with the gear its first enchantment fits. A single kind keeps its own group
    // and a spread of kinds reads as armour or tools or weapons or any gear.
    private static int bookRank(ItemStack book) {
        ItemEnchantments stored = book.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (stored.isEmpty()) {
            return OTHER_GEAR;
        }
        Holder<Enchantment> first = stored.keySet().iterator().next();
        Set<GearKind> kinds = EnumSet.noneOf(GearKind.class);
        for (GearKind kind : GearKind.values()) {
            if (first.value().isSupportedItem(sample(kind))) {
                kinds.add(kind);
            }
        }
        if (kinds.isEmpty()) {
            return OTHER_GEAR;
        }
        if (kinds.size() == 1) {
            return kinds.iterator().next().ordinal();
        }
        if (ARMOUR.containsAll(kinds)) {
            return ANY_ARMOUR;
        }
        if (TOOLS.containsAll(kinds)) {
            return ANY_TOOL;
        }
        return WEAPONS.containsAll(kinds) ? ANY_WEAPON : ANY_GEAR;
    }

    private static ItemStack sample(GearKind kind) {
        return SAMPLES.computeIfAbsent(kind, unbuilt -> new ItemStack(unbuilt.sample()));
    }

    private static String groupName(int rank) {
        if (rank >= POTIONS) {
            return POTION_GROUPS.get(rank - POTIONS);
        }
        if (rank >= BOOKS) {
            return "Books for " + gearName(rank - BOOKS);
        }
        return rank == OTHER_GEAR ? "Other gear" : EnumSetting.label(GearKind.values()[rank]);
    }

    private static String gearName(int rank) {
        if (rank < OTHER_GEAR) {
            return EnumSetting.label(GearKind.values()[rank]).toLowerCase(Locale.ROOT);
        }
        return MIXED_BOOKS.getOrDefault(rank, "other gear");
    }

    // Called once the container is drawn. The list sits beside it and scrolls when it runs
    // past the bottom of the screen.
    public void draw(GuiGraphicsExtractor context, Font font, int leftPos, int topPos, int imageWidth,
                     int mouseX, int mouseY) {
        width = 0;
        hovered = null;
        if (!isEnabled() || rows.isEmpty()) {
            return;
        }
        drawnScale = scale.getFloat();
        int room = side.is(Side.LEFT) ? leftPos - 2 * GAP : context.guiWidth() - leftPos - imageWidth - 2 * GAP;
        int listWidth = Math.min(widest + 2 * PADDING, (int) (room / drawnScale));
        if (listWidth * drawnScale < MIN_WIDTH) {
            return;
        }
        width = Math.round(listWidth * drawnScale);
        left = side.is(Side.LEFT) ? leftPos - GAP - width : leftPos + imageWidth + GAP;
        top = Math.max(GAP, topPos);
        visible = Math.max(1, (int) ((context.guiHeight() - GAP - top) / (ROW_HEIGHT * drawnScale)));
        scroll = Math.clamp(scroll, 0, Math.max(0, rows.size() - visible));
        int shown = Math.min(visible, rows.size() - scroll);
        int under = rowAt(mouseX, mouseY);
        hovered = under < 0 ? null : rows.get(under);

        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.translate(left, top);
        pose.scale(drawnScale, drawnScale);
        context.fill(0, 0, listWidth, shown * ROW_HEIGHT, BACKGROUND);
        for (int i = 0; i < shown; i++) {
            Row row = rows.get(scroll + i);
            int y = i * ROW_HEIGHT;
            if (row == hovered) {
                context.fill(0, y, listWidth, y + ROW_HEIGHT, ROW_HOVER);
            }
            int x = PADDING;
            if (!row.heading()) {
                pose.pushMatrix();
                pose.translate(x, y + 1);
                pose.scale(ICON_SCALE, ICON_SCALE);
                context.item(row.icon(), 0, 0);
                pose.popMatrix();
                x += ICON_SIZE + PADDING;
            }
            // A row with no text at all splits into no lines.
            List<FormattedCharSequence> lines = font.split(row.text(), listWidth - x - PADDING);
            if (!lines.isEmpty()) {
                context.text(font, lines.getFirst(), x, y + 1, TEXT, true);
            }
        }
        pose.popMatrix();
    }

    // The index of the row under the pointer or minus one.
    private int rowAt(double mouseX, double mouseY) {
        if (width == 0 || mouseX < left || mouseX >= left + width || mouseY < top) {
            return -1;
        }
        int row = scroll + (int) ((mouseY - top) / (ROW_HEIGHT * drawnScale));
        return row < Math.min(rows.size(), scroll + visible) ? row : -1;
    }

    // A click on a row moves its stacks across. True when the click landed on the list.
    public boolean click(double mouseX, double mouseY, AbstractContainerMenu menu) {
        int row = rowAt(mouseX, mouseY);
        if (row < 0 || !menu.getCarried().isEmpty()) {
            return row >= 0;
        }
        for (int slot : rows.get(row).slots()) {
            MenuClicks.quickMove(menu, slot);
        }
        return true;
    }

    // True when the wheel turned over the list.
    public boolean scroll(double mouseX, double mouseY, double amount) {
        if (width == 0 || mouseX < left || mouseX >= left + width || mouseY < top) {
            return false;
        }
        scroll -= (int) Math.signum(amount);
        return true;
    }

    // The slot paint for the row under the pointer. Nought for every other slot.
    public int paintFor(Slot slot) {
        return isEnabled() && highlight.isOn() && hovered != null && hovered.slots().contains(slot.index)
            ? SLOT_PAINT : 0;
    }
}
