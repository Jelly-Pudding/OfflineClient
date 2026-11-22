package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

// AbstractContainerScreenMixin paints the slot before the item goes on top.
public final class ItemHighlight extends Module {

    // A screen never shows as many stacks. The answers drawn least of late go first.
    private static final int MOST_VERDICTS = 2048;
    // Look inside reads this many levels down such as the items of a bundle in a shulker box.
    private static final int NESTING = 2;
    // A server can send far longer names and lore.
    private static final int MOST_TEXT = 4096;
    private static final String SEARCH_BREAK = ",";

    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "The items to pick out in any container screen.", BuiltInRegistries.ITEM,
        List.of(Items.TOTEM_OF_UNDYING, Items.ENCHANTED_GOLDEN_APPLE, Items.END_CRYSTAL,
            Items.EXPERIENCE_BOTTLE, Items.SHULKER_BOX));
    private final BoolSetting renamed = new BoolSetting("Renamed",
        "Also paints every item somebody gave a name.", false);
    private final BoolSetting renamedBoxes = new BoolSetting("Renamed boxes",
        "Counts a named shulker box too. Most players name theirs.", false)
        .under(renamed);
    private final BoolSetting impossible = new BoolSetting("Impossible items",
        "Also paints items survival cannot make such as two kinds of protection on one piece"
            + " or a level past the highest or no durability left.", false);
    private final TextSetting search = new TextSetting("Search",
        "Also paints items whose name or lore or enchantments or book title or author hold this text."
            + " A comma parts several searches.", "");
    private final BoolSetting lookInside = new BoolSetting("Look inside",
        "Also paints a shulker box or bundle that holds a matching item.", false);
    private final ColorSetting color = new ColorSetting("Colour",
        "Colour painted behind a matching item.", 130, false);
    private final NumberSetting strength = new NumberSetting("Strength",
        "How solid the paint is.", 50, 10, 100, 5, "%").min(0).max(100);
    private final BoolSetting hotbar = new BoolSetting("Hotbar",
        "Also paints the hotbar whilst you play with no inventory open.", true);

    // The server sends a new stack for every change but a bundle fills in place as you click items
    // into it.
    private record Verdict(boolean picked, BundleContents bundle) {
    }

    // What the rules read besides the stack itself. Every answer goes when one of them changes.
    private record Rules(Set<Item> items, boolean renamed, boolean boxes, boolean impossible, String search,
                         boolean inside) {
    }

    // The slots are drawn every frame and a stack is judged once. ItemStack has no equals of its
    // own and each stack is its own key.
    private final Map<ItemStack, Verdict> verdicts = new BoundedMap<>(MOST_VERDICTS, true);
    private Rules rules;
    private List<String> searches = List.of();

    public ItemHighlight() {
        super("ItemHighlight", "Paints a colour behind the items you pick in any inventory and the hotbar.", Category.RENDER);
        addSettings(items, renamed, renamedBoxes, impossible, search, lookInside, color, strength, hotbar);
        searchTags("inventory highlight", "slot colour", "renamed", "illegal items", "lore", "item search");
    }

    @Override
    protected void onDisable() {
        verdicts.clear();
        rules = null;
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        Rules now = new Rules(items.resolved(), renamed.isOn(), renamedBoxes.isOn(), impossible.isOn(),
            search.getValue(), lookInside.isOn());
        if (!now.equals(rules)) {
            rules = now;
            searches = searches();
            verdicts.clear();
        }
    }

    public int hotbarColorFor(ItemStack stack) {
        return hotbar.isOn() ? colorFor(stack) : 0;
    }

    public int colorFor(ItemStack stack) {
        if (!isEnabled() || stack.isEmpty() || !picked(stack)) {
            return 0;
        }
        return ColorUtil.fade(color.getColor(), strength.getFloat() / 100f);
    }

    private boolean picked(ItemStack stack) {
        if (items.contains(stack.getItem())) {
            return true;
        }
        if (!renamed.isOn() && !impossible.isOn() && search.isBlank() && !lookInside.isOn()) {
            return false;
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        Verdict verdict = verdicts.get(stack);
        if (verdict == null || verdict.bundle() != bundle) {
            verdict = new Verdict(matches(stack, lookInside.isOn() ? NESTING : 0), bundle);
            verdicts.put(stack, verdict);
        }
        return verdict.picked();
    }

    private List<String> searches() {
        List<String> found = new ArrayList<>();
        for (String part : search.getValue().toLowerCase(Locale.ROOT).split(SEARCH_BREAK)) {
            if (!part.isBlank()) {
                found.add(part.strip());
            }
        }
        return found;
    }

    private boolean matches(ItemStack stack, int deeper) {
        if (items.contains(stack.getItem()) || renamed.isOn() && isRenamed(stack)
            || impossible.isOn() && isImpossible(stack) || searchedFor(stack)) {
            return true;
        }
        return deeper > 0 && inside(stack).anyMatch(held -> matches(held, deeper - 1));
    }

    private boolean isRenamed(ItemStack stack) {
        return stack.has(DataComponents.CUSTOM_NAME)
            && (renamedBoxes.isOn() || !ItemUtil.isShulkerBox(stack.getItem()));
    }

    // An anvil only adds an enchantment the item supports up to its highest level and never
    // beside one it clashes with. A book takes any enchantment. A tool breaks the moment its
    // last use is spent and none is ever left holding nought.
    private static boolean isImpossible(ItemStack stack) {
        if (stack.isDamageableItem()
            && stack.getOrDefault(DataComponents.DAMAGE, 0) >= stack.getMaxDamage()) {
            return true;
        }
        ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        boolean book = stack.is(Items.ENCHANTED_BOOK);
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
            Holder<Enchantment> held = entry.getKey();
            if (entry.getIntValue() > held.value().getMaxLevel() || !book && !held.value().canEnchant(stack)) {
                return true;
            }
            for (Holder<Enchantment> other : enchantments.keySet()) {
                if (!other.equals(held) && !Enchantment.areCompatible(held, other)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean searchedFor(ItemStack stack) {
        if (searches.isEmpty()) {
            return false;
        }
        String text = textOf(stack);
        return searches.stream().anyMatch(text::contains);
    }

    private static String textOf(ItemStack stack) {
        StringBuilder text = new StringBuilder();
        add(text, stack.getHoverName());
        add(text, stack.getItemName());
        add(text, BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
        for (Component line : stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines()) {
            add(text, line);
        }
        for (Object2IntMap.Entry<Holder<Enchantment>> entry
            : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
            add(text, Enchantment.getFullname(entry.getKey(), entry.getIntValue()));
        }
        WrittenBookContent book = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (book != null) {
            add(text, book.title().raw());
            add(text, book.author());
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static void add(StringBuilder text, Component part) {
        int room = MOST_TEXT - text.length();
        if (room > 0) {
            add(text, part.getString(room));
        }
    }

    // Each part goes on a line of its own. A search never matches across two of them.
    private static void add(StringBuilder text, String part) {
        int room = MOST_TEXT - text.length();
        if (room > 0) {
            text.append(part, 0, Math.min(part.length(), room)).append('\n');
        }
    }

    private static Stream<ItemStack> inside(ItemStack stack) {
        ItemContainerContents box = stack.get(DataComponents.CONTAINER);
        if (box != null) {
            return box.nonEmptyItemCopyStream();
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        return bundle == null ? Stream.empty() : bundle.itemCopies();
    }
}
