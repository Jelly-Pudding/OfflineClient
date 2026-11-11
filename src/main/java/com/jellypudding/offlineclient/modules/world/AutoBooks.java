package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.StationModule;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.ListMode;
import com.jellypudding.offlineclient.util.GearKind;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.Modules;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Puts the enchanted books you carry onto your gear at an open anvil one book after another.
// The anvil works its result out on this side as well. A pair that shows none is one it refuses.
public final class AutoBooks extends StationModule<AnvilMenu> {

    // Ticks between two rounds of clicks.
    private static final int PACE = 4;

    // The anvil calls a job too expensive once it would cost this many levels.
    private static final int TOO_EXPENSIVE = 40;

    private static final String NOTHING_TICKED = "Tick the enchantments to put on first.";

    // A piece and a book the anvil showed no result for. Neither is tried with the other again
    // until one of them changes.
    private record Refused(ItemStack gear, ItemStack book) {
    }

    private final EnumSetting<ListMode> listMode = ListMode.setting("List mode", ListMode.WHITELIST,
        "Only the enchantments ticked for a kind of gear go onto it.",
        "Every enchantment that fits goes on except the ones ticked for that kind of gear.")
        .startFolded();
    private final Map<GearKind, ChoiceListSetting> lists = new EnumMap<>(GearKind.class);
    private final ChoiceListSetting others = new ChoiceListSetting("Other gear",
        "The enchantments the list mode applies to on anything else such as an elytra or a fishing rod."
            + " Join a world to fill the list.", ItemUtil::enchantmentIds)
        .under(listMode, () -> true);

    private final List<Refused> refused = new ArrayList<>();
    private boolean waiting;
    // True whilst AutoRename has the anvil. It is said once for each anvil.
    private boolean yielding;
    private int applied;

    public AutoBooks() {
        super("AutoBooks", "Puts the enchanted books you carry onto your gear whilst an anvil is open.",
            AnvilMenu.class, PACE);
        addSettings(listMode);
        for (GearKind kind : GearKind.values()) {
            if (kind == GearKind.SHULKER_BOXES) {
                continue;
            }
            String label = EnumSetting.label(kind);
            ChoiceListSetting list = new ChoiceListSetting(label,
                "The enchantments the list mode applies to on " + label.toLowerCase(Locale.ROOT)
                    + ". Join a world to fill the list.", () -> fitting(kind.sample()))
                .under(listMode, () -> true);
            lists.put(kind, list);
            addSettings(list);
        }
        addSettings(others);
        searchTags("anvil", "enchanted book", "combine", "apply books", "kit");
    }

    @Override
    public String getSuffix() {
        if (yielding) {
            return "after AutoRename";
        }
        return waiting ? "waiting for levels" : count(applied, "applied");
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        applied = 0;
        if (nothingTicked()) {
            disable(NOTHING_TICKED);
        }
    }

    @Override
    protected void closed() {
        refused.clear();
        waiting = false;
        yielding = false;
    }

    @Override
    protected void work(AnvilMenu menu) {
        // AutoRename has the anvil first. A name costs less before books raise the price of the work.
        if (Modules.enabled(AutoRename.class)) {
            if (!yielding) {
                yielding = true;
                tell("waits until AutoRename is done with the anvil.");
            }
            return;
        }
        yielding = false;
        ItemStack gear = menu.getSlot(AnvilMenu.INPUT_SLOT).getItem();
        ItemStack book = menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem();
        if (gear.isEmpty() && book.isEmpty()) {
            placeNext(menu);
            return;
        }
        if (!fits(gear, book)) {
            empty(menu);
            return;
        }
        if (!menu.getSlot(AnvilMenu.RESULT_SLOT).hasItem()) {
            refuse(menu, gear, book);
            return;
        }
        int cost = menu.getCost();
        if (!mc.player.hasInfiniteMaterials() && mc.player.experienceLevel < cost) {
            if (!waiting) {
                waiting = true;
                tell("waits until you have §f" + cost + " §7levels for the next book.");
            }
            return;
        }
        waiting = false;
        if (moveOut(menu, AnvilMenu.RESULT_SLOT, "for the enchanted piece.")) {
            applied++;
        }
    }

    // The best piece a book fits goes in first and takes the left slot. The book then lands
    // beside it.
    private void placeNext(AnvilMenu menu) {
        List<Integer> mine = MenuClicks.inventorySlots(menu);
        Map<Integer, Double> scores = new HashMap<>();
        mine.forEach(slot -> scores.put(slot, ItemUtil.gearScore(menu.getSlot(slot).getItem())));
        List<Integer> pieces = new ArrayList<>(mine);
        pieces.sort(Comparator.<Integer, Double>comparing(scores::get).reversed());
        for (int gear : pieces) {
            ItemStack piece = menu.getSlot(gear).getItem();
            for (int book : mine) {
                ItemStack offered = menu.getSlot(book).getItem();
                if (fits(piece, offered) && !wasRefused(piece, offered)) {
                    MenuClicks.quickMove(menu, gear);
                    MenuClicks.quickMove(menu, book);
                    return;
                }
            }
        }
        if (nothingTicked()) {
            disable(NOTHING_TICKED);
            return;
        }
        disable(refused.isEmpty() ? "No book left that fits your gear."
            : "No book left that the anvil will put on your gear.");
    }

    // Only the ticked enchantments go on and none is ticked.
    private boolean nothingTicked() {
        return listMode.is(ListMode.WHITELIST) && others.size() == 0
            && lists.values().stream().allMatch(list -> list.size() == 0);
    }

    private void refuse(AnvilMenu menu, ItemStack gear, ItemStack book) {
        refused.add(new Refused(gear.copy(), book.copy()));
        tell("the anvil will not put §f" + book.getHoverName().getString()
            + " §7on §f" + gear.getHoverName().getString() + "§7."
            + (menu.getCost() >= TOO_EXPENSIVE ? " It would cost too many levels." : ""));
        empty(menu);
    }

    private void empty(AnvilMenu menu) {
        for (int slot : new int[] {AnvilMenu.ADDITIONAL_SLOT, AnvilMenu.INPUT_SLOT}) {
            if (menu.getSlot(slot).hasItem() && !moveOut(menu, slot, "to empty the anvil.")) {
                return;
            }
        }
    }

    private boolean wasRefused(ItemStack gear, ItemStack book) {
        for (Refused pair : refused) {
            if (ItemStack.matches(pair.gear(), gear) && ItemStack.matches(pair.book(), book)) {
                return true;
            }
        }
        return false;
    }

    // True when the book belongs on the piece. Everything on the book has to be wanted for that
    // kind of gear and able to go on beside what the piece carries and at least one enchantment
    // has to come out stronger. A book with anything else on it would waste that part.
    private boolean fits(ItemStack gear, ItemStack book) {
        if (!book.is(Items.ENCHANTED_BOOK) || gear.getCount() != 1 || gear.is(Items.ENCHANTED_BOOK)) {
            return false;
        }
        ItemEnchantments stored = book.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        ItemEnchantments carried = gear.getEnchantments();
        ChoiceListSetting list = listFor(gear);
        boolean stronger = false;
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : stored.entrySet()) {
            Holder<Enchantment> held = entry.getKey();
            if (!held.value().canEnchant(gear) || clashes(held, carried)
                || !listMode.getValue().admits(list.contains(held.getRegisteredName()))) {
                return false;
            }
            // The anvil adds one to two equal levels and keeps the higher of two others. Nothing
            // goes past the highest level.
            int has = carried.getLevel(held);
            int offered = entry.getIntValue();
            int after = Math.min(held.value().getMaxLevel(), offered == has ? offered + 1 : Math.max(offered, has));
            // A piece past the highest level would come out weaker.
            if (after < has) {
                return false;
            }
            stronger |= after > has;
        }
        return stronger;
    }

    private static boolean clashes(Holder<Enchantment> held, ItemEnchantments carried) {
        for (Holder<Enchantment> other : carried.keySet()) {
            if (!other.equals(held) && !Enchantment.areCompatible(held, other)) {
                return true;
            }
        }
        return false;
    }

    private ChoiceListSetting listFor(ItemStack gear) {
        GearKind kind = GearKind.of(gear);
        ChoiceListSetting list = kind == null ? null : lists.get(kind);
        return list == null ? others : list;
    }

    // The enchantments the world knows that can go on the item. Empty until a world is loaded.
    private static Collection<String> fitting(Item sample) {
        if (mc.level == null) {
            return List.of();
        }
        ItemStack stack = new ItemStack(sample);
        return mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements()
            .filter(holder -> holder.value().canEnchant(stack))
            .map(Holder::getRegisteredName).sorted().toList();
    }
}
