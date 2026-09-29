package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;

// Which gear counts by what it is enchanted with. Gear that takes enchantments may have to
// carry at least one and a picked list names the ones it must carry. A picked enchantment
// that cannot go on a piece is skipped for it. One list covers weapons and armour and tools.
public final class GearRule {

    // One picked enchantment and the lowest level that counts.
    private record Wanted(Holder<Enchantment> enchantment, int level) {
    }

    private static final String LEVELS = " An entry ending in a number asks for that level or higher."
        + " Join a world to fill the list.";

    // Null for a rule that only looks for the picked enchantments.
    private final BoolSetting enchantedOnly;
    private final ChoiceListSetting picked;

    // The picked list read against the enchantments of the world it was read in.
    private List<Wanted> wanted = List.of();
    private RegistryAccess readFor;
    private boolean stale = true;

    private GearRule(BoolSetting enchantedOnly, String name, String description) {
        this.enchantedOnly = enchantedOnly;
        picked = new ChoiceListSetting(name, description + LEVELS, ItemUtil::enchantmentLevels)
            .onChange(() -> stale = true);
    }

    // Narrows what a module marks. Unenchanted gear and gear missing a picked enchantment drop out.
    public static GearRule filter() {
        return new GearRule(new BoolSetting("Enchanted only",
                "Gear that takes enchantments counts only when it carries at least one.", false),
            "Needed enchantments", "Gear counts only when it carries every picked enchantment that can go on it.");
    }

    // A sign a module looks for. Only the picked list.
    public static GearRule clue() {
        return new GearRule(null, "Enchantments",
            "Flags gear carrying every picked enchantment that can go on it.");
    }

    public Setting<?>[] settings() {
        return enchantedOnly == null ? new Setting<?>[] {picked} : new Setting<?>[] {enchantedOnly, picked};
    }

    // True when the stack may be marked. Anything that is not gear passes the first row.
    public boolean passes(ItemStack stack) {
        ItemEnchantments on = stack.getEnchantments();
        if (enchantedOnly != null && enchantedOnly.isOn() && on.isEmpty() && isGear(stack)) {
            return false;
        }
        for (Wanted entry : wanted()) {
            if (entry.enchantment().value().isSupportedItem(stack) && on.getLevel(entry.enchantment()) < entry.level()) {
                return false;
            }
        }
        return true;
    }

    // The picked enchantments the stack carries in words such as Sharpness V and Mending. Null
    // unless one of them can go on the stack and it carries every one that can.
    public String carried(ItemStack stack) {
        ItemEnchantments on = stack.getEnchantments();
        List<String> names = new ArrayList<>();
        for (Wanted entry : wanted()) {
            if (!entry.enchantment().value().isSupportedItem(stack)) {
                continue;
            }
            int level = on.getLevel(entry.enchantment());
            if (level < entry.level()) {
                return null;
            }
            names.add(Enchantment.getFullname(entry.enchantment(), level).getString());
        }
        return names.isEmpty() ? null : String.join(" and ", names);
    }

    // Anything Unbreaking can go on. The elytra and the brush count as gear too.
    private static boolean isGear(ItemStack stack) {
        return stack.is(ItemTags.DURABILITY_ENCHANTABLE);
    }

    // Read again whenever the list changes or a world with other enchantments loads.
    private List<Wanted> wanted() {
        RegistryAccess access = OfflineClient.MC.level == null ? null : OfflineClient.MC.level.registryAccess();
        if (stale || access != readFor) {
            readFor = access;
            stale = false;
            wanted = read();
        }
        return wanted;
    }

    private List<Wanted> read() {
        List<Wanted> read = new ArrayList<>();
        for (String token : picked.getValue()) {
            ItemUtil.EnchantmentEntry entry = ItemUtil.enchantmentEntry(token);
            if (entry != null) {
                read.add(new Wanted(entry.enchantment(), entry.level()));
            }
        }
        return read;
    }
}
