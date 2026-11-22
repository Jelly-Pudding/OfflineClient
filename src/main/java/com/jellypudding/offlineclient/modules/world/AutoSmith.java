package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.StationModule;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.item.equipment.trim.TrimMaterial;
import net.minecraft.world.item.equipment.trim.TrimMaterials;
import net.minecraft.world.item.equipment.trim.TrimPattern;
import net.minecraft.world.item.equipment.trim.TrimPatterns;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

// Only the server knows the smithing recipes. The result shows up once it has answered.
public final class AutoSmith extends StationModule<SmithingMenu> {

    public enum Mode { UPGRADE, TRIM }

    public enum Pattern {
        NONE(null, null),
        SENTRY(TrimPatterns.SENTRY, Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE),
        DUNE(TrimPatterns.DUNE, Items.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE),
        COAST(TrimPatterns.COAST, Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE),
        WILD(TrimPatterns.WILD, Items.WILD_ARMOR_TRIM_SMITHING_TEMPLATE),
        WARD(TrimPatterns.WARD, Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE),
        EYE(TrimPatterns.EYE, Items.EYE_ARMOR_TRIM_SMITHING_TEMPLATE),
        VEX(TrimPatterns.VEX, Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE),
        TIDE(TrimPatterns.TIDE, Items.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE),
        SNOUT(TrimPatterns.SNOUT, Items.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE),
        RIB(TrimPatterns.RIB, Items.RIB_ARMOR_TRIM_SMITHING_TEMPLATE),
        SPIRE(TrimPatterns.SPIRE, Items.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE),
        WAYFINDER(TrimPatterns.WAYFINDER, Items.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE),
        SHAPER(TrimPatterns.SHAPER, Items.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE),
        SILENCE(TrimPatterns.SILENCE, Items.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE),
        RAISER(TrimPatterns.RAISER, Items.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE),
        HOST(TrimPatterns.HOST, Items.HOST_ARMOR_TRIM_SMITHING_TEMPLATE),
        FLOW(TrimPatterns.FLOW, Items.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE),
        BOLT(TrimPatterns.BOLT, Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE);

        private final ResourceKey<TrimPattern> key;
        private final Item template;

        Pattern(ResourceKey<TrimPattern> key, Item template) {
            this.key = key;
            this.template = template;
        }
    }

    public enum Material {
        QUARTZ(TrimMaterials.QUARTZ, Items.QUARTZ),
        IRON(TrimMaterials.IRON, Items.IRON_INGOT),
        NETHERITE(TrimMaterials.NETHERITE, Items.NETHERITE_INGOT),
        REDSTONE(TrimMaterials.REDSTONE, Items.REDSTONE),
        COPPER(TrimMaterials.COPPER, Items.COPPER_INGOT),
        GOLD(TrimMaterials.GOLD, Items.GOLD_INGOT),
        EMERALD(TrimMaterials.EMERALD, Items.EMERALD),
        DIAMOND(TrimMaterials.DIAMOND, Items.DIAMOND),
        LAPIS(TrimMaterials.LAPIS, Items.LAPIS_LAZULI),
        AMETHYST(TrimMaterials.AMETHYST, Items.AMETHYST_SHARD),
        RESIN(TrimMaterials.RESIN, Items.RESIN_BRICK);

        private final ResourceKey<TrimMaterial> key;
        private final Item item;

        Material(ResourceKey<TrimMaterial> key, Item item) {
            this.key = key;
            this.item = item;
        }
    }

    private record Job(Predicate<ItemStack> base, Predicate<ItemStack> template, Predicate<ItemStack> addition,
                       String noBase, String noTemplate, String noAddition, String verb) {
    }

    private static final int PACE = 4;

    private static final Map<EquipmentSlot, String> PIECES = Map.of(
        EquipmentSlot.HEAD, "Helmet", EquipmentSlot.CHEST, "Chestplate",
        EquipmentSlot.LEGS, "Leggings", EquipmentSlot.FEET, "Boots");

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "What the smithing table is used for.", Mode.UPGRADE)
        .describe(Mode.UPGRADE, "Upgrades the chosen diamond gear to netherite.")
        .describe(Mode.TRIM, "Puts the pattern picked for each armour slot on the armour you carry.");
    private final RegistryListSetting<Item> gear = new RegistryListSetting<>("Gear",
        "Diamond gear to upgrade. Enchantments carry over to the netherite piece.", BuiltInRegistries.ITEM,
        List.of(Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL,
            Items.DIAMOND_HOE, Items.DIAMOND_SPEAR, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
            Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS))
        .under(mode, Mode.UPGRADE);
    private final Map<EquipmentSlot, EnumSetting<Pattern>> patterns = new EnumMap<>(EquipmentSlot.class);
    private final Map<EquipmentSlot, EnumSetting<Material>> materials = new EnumMap<>(EquipmentSlot.class);
    private final BoolSetting replace = new BoolSetting("Replace trims",
        "Also trims armour that carries another trim already.", false)
        .under(mode, Mode.TRIM);

    private final Job upgrade = new Job(this::isGear, AutoSmith::isUpgradeTemplate, AutoSmith::isIngot,
        "Nothing left to upgrade.", "Out of netherite upgrade templates.", "Out of netherite ingots.", "upgrade");

    private int done;

    public AutoSmith() {
        super("AutoSmith", "Upgrades diamond gear to netherite or trims armour whilst a smithing table is open.",
            SmithingMenu.class, PACE);
        addSettings(mode, gear);
        for (EquipmentSlot slot : ItemUtil.ARMOR_SLOTS) {
            String piece = PIECES.get(slot);
            String lower = piece.toLowerCase(Locale.ROOT);
            EnumSetting<Pattern> pattern = new EnumSetting<>(piece + " pattern",
                "The trim pattern for every " + lower + " you carry.", Pattern.NONE)
                .describe(Pattern.NONE, "Leaves every " + lower + " as it is.")
                .under(mode, Mode.TRIM);
            EnumSetting<Material> material = new EnumSetting<>(piece + " material",
                "What the " + lower + " trim is made of.", Material.QUARTZ)
                .under(pattern, () -> !pattern.is(Pattern.NONE));
            patterns.put(slot, pattern);
            materials.put(slot, material);
            addSettings(pattern, material);
        }
        addSettings(replace);
        searchTags("smithing table", "netherite", "upgrade", "armour trim", "template");
    }

    @Override
    public String getSuffix() {
        return count(done, mode.is(Mode.TRIM) ? "trimmed" : "upgraded");
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        done = 0;
    }

    @Override
    protected void work(SmithingMenu menu) {
        Job job = mode.is(Mode.TRIM) ? trimJob(menu) : upgrade;
        if (job == null) {
            return;
        }
        if (!clearForeign(menu, SmithingMenu.TEMPLATE_SLOT, job.template())
            || !clearForeign(menu, SmithingMenu.ADDITIONAL_SLOT, job.addition())
            || !clearForeign(menu, SmithingMenu.BASE_SLOT, job.base())) {
            return;
        }
        if (menu.getSlot(SmithingMenu.RESULT_SLOT).hasItem()) {
            if (moveOut(menu, SmithingMenu.RESULT_SLOT, "for the finished piece.")) {
                done++;
            }
            return;
        }
        boolean baseEmpty = !menu.getSlot(SmithingMenu.BASE_SLOT).hasItem();
        if (!ready(menu, SmithingMenu.BASE_SLOT, job.base(), job.noBase())
            || !ready(menu, SmithingMenu.TEMPLATE_SLOT, job.template(), job.noTemplate())
            || !ready(menu, SmithingMenu.ADDITIONAL_SLOT, job.addition(), job.noAddition())) {
            return;
        }
        fill(menu, SmithingMenu.TEMPLATE_SLOT, job.template());
        fill(menu, SmithingMenu.ADDITIONAL_SLOT, job.addition());
        if (baseEmpty) {
            fill(menu, SmithingMenu.BASE_SLOT, job.base());
            startClock();
            awaitServer(menu);
            return;
        }
        if (stalled()) {
            String refused = menu.getSlot(SmithingMenu.BASE_SLOT).getItem().getHoverName().getString();
            MenuClicks.quickMove(menu, SmithingMenu.BASE_SLOT);
            disable("The server will not " + job.verb() + " " + refused + ".");
        }
    }

    private Job trimJob(SmithingMenu menu) {
        if (patterns.values().stream().allMatch(pattern -> pattern.is(Pattern.NONE))) {
            disable("Pick a pattern for at least one armour slot first.");
            return null;
        }
        ItemStack piece = menu.getSlot(SmithingMenu.BASE_SLOT).getItem();
        if (!wantsTrim(piece)) {
            int carried = MenuClicks.firstInventorySlot(menu, this::wantsTrim);
            if (carried == -1) {
                disable("Nothing left to trim.");
                return null;
            }
            piece = menu.getSlot(carried).getItem();
        }
        EquipmentSlot slot = ItemUtil.equipSlot(piece);
        Pattern pattern = patterns.get(slot).getValue();
        Material material = materials.get(slot).getValue();
        return new Job(stack -> wantsTrim(stack) && ItemUtil.equipSlot(stack) == slot,
            stack -> stack.is(pattern.template), stack -> stack.is(material.item),
            "Nothing left to trim.",
            "Out of " + EnumSetting.label(pattern) + " trim templates.",
            "Out of " + EnumSetting.label(material).toLowerCase(Locale.ROOT) + " for the trim.", "trim");
    }

    // The server refuses the same trim twice.
    private boolean wantsTrim(ItemStack stack) {
        EquipmentSlot slot = stack.is(ItemTags.TRIMMABLE_ARMOR) ? ItemUtil.equipSlot(stack) : null;
        EnumSetting<Pattern> pattern = slot == null ? null : patterns.get(slot);
        if (pattern == null || pattern.is(Pattern.NONE)) {
            return false;
        }
        ArmorTrim trim = stack.get(DataComponents.TRIM);
        if (trim == null) {
            return true;
        }
        return replace.isOn() && !(trim.pattern().is(pattern.getValue().key)
            && trim.material().is(materials.get(slot).getValue().key));
    }

    private boolean clearForeign(SmithingMenu menu, int slot, Predicate<ItemStack> belongs) {
        ItemStack stack = menu.getSlot(slot).getItem();
        return stack.isEmpty() || belongs.test(stack) || moveOut(menu, slot, "to empty the smithing table.");
    }

    // Nothing moves until all three parts are there.
    private boolean ready(SmithingMenu menu, int slot, Predicate<ItemStack> fits, String missing) {
        if (menu.getSlot(slot).hasItem() || MenuClicks.firstInventorySlot(menu, fits) != -1) {
            return true;
        }
        disable(missing);
        return false;
    }

    // The whole stack goes in. Each piece takes one of it.
    private static void fill(SmithingMenu menu, int slot, Predicate<ItemStack> fits) {
        if (!menu.getSlot(slot).hasItem()) {
            MenuClicks.quickMove(menu, MenuClicks.firstInventorySlot(menu, fits));
        }
    }

    private boolean isGear(ItemStack stack) {
        return gear.contains(stack.getItem());
    }

    private static boolean isUpgradeTemplate(ItemStack stack) {
        return stack.is(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
    }

    private static boolean isIngot(ItemStack stack) {
        return stack.is(ItemTags.NETHERITE_TOOL_MATERIALS);
    }
}
