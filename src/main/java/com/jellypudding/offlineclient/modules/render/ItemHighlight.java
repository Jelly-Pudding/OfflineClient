package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

// AbstractContainerScreenMixin paints the slot before the item goes on top.
public final class ItemHighlight extends Module {

    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "The items to pick out in any container screen.", BuiltInRegistries.ITEM,
        List.of(Items.TOTEM_OF_UNDYING, Items.ENCHANTED_GOLDEN_APPLE, Items.END_CRYSTAL,
            Items.EXPERIENCE_BOTTLE, Items.SHULKER_BOX));
    private final ColorSetting color = new ColorSetting("Colour",
        "Colour painted behind a matching item.", 130, false);
    private final NumberSetting strength = new NumberSetting("Strength",
        "How solid the paint is.", 50, 10, 100, 5, "%").max(100);

    public ItemHighlight() {
        super("ItemHighlight", "Paints a colour behind the items you pick in any inventory screen.", Category.RENDER);
        addSettings(items, color, strength);
        searchTags("inventory highlight", "slot colour");
    }

    // Zero when the stack is not one to pick out.
    public int colorFor(ItemStack stack) {
        if (!isEnabled() || stack.isEmpty() || !items.contains(stack.getItem())) {
            return 0;
        }
        return ColorUtil.fade(color.getColor(), strength.getFloat() / 100f);
    }
}
