package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.WindChargeItem;

import java.util.List;

// Cuts the four tick pause the game puts between right clicks. Blocks
// are left to FastPlace which shares the same delay cap.
public final class FastUse extends Module {

    public enum Mode { ALL_ITEMS, CHOSEN_ONLY }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "What gets sped up.", Mode.ALL_ITEMS)
        .describe(Mode.ALL_ITEMS, "Speeds up everything you can use.")
        .describe(Mode.CHOSEN_ONLY, "Speeds up only the kinds ticked below.");
    private final BoolSetting food = new BoolSetting("Food",
        "Anything you can eat or drink.", true).under(mode, Mode.CHOSEN_ONLY);
    private final BoolSetting pearls = new BoolSetting("Ender pearls",
        "Throws ender pearls with no delay.", true).under(mode, Mode.CHOSEN_ONLY);
    private final BoolSetting potions = new BoolSetting("Splash potions",
        "Splash and lingering potions.", true).under(mode, Mode.CHOSEN_ONLY);
    private final BoolSetting experience = new BoolSetting("Experience bottles",
        "Bottles of enchanting.", true).under(mode, Mode.CHOSEN_ONLY);
    private final BoolSetting throwables = new BoolSetting("Snowballs and eggs",
        "Snowballs and eggs and wind charges.", false).under(mode, Mode.CHOSEN_ONLY);
    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "Any other items to speed up. Click to pick them.", BuiltInRegistries.ITEM, List.of())
        .under(mode, Mode.CHOSEN_ONLY);
    private final NumberSetting cooldown = new NumberSetting("Cooldown",
        "Ticks to keep between uses. 0 is one use every tick.", 0, 0, 4, 1, " ticks").max(4);

    public FastUse() {
        super("FastUse", "Removes the delay between right clicks for items.", Category.PLAYER);
        addSettings(mode, food, pearls, potions, experience, throwables, items, cooldown);
        searchTags("fast pearl", "fast eat", "fast throw", "right click delay");
    }

    // Shared cap on the vanilla right click delay. The game sets it to
    // four on every use and counts it down once a tick.
    public static void capUseDelay(int ticks) {
        OfflineClient.MC.rightClickDelay = Math.min(OfflineClient.MC.rightClickDelay, Math.max(0, ticks));
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty()) {
            held = mc.player.getOffhandItem();
        }
        if (held.isEmpty() || held.getItem() instanceof BlockItem) {
            return;
        }
        if (mode.is(Mode.ALL_ITEMS) || wanted(held)) {
            capUseDelay(cooldown.getInt());
        }
    }

    private boolean wanted(ItemStack stack) {
        Item item = stack.getItem();
        if (items.contains(item)) {
            return true;
        }
        if (food.isOn() && (stack.has(DataComponents.FOOD) || stack.has(DataComponents.CONSUMABLE))) {
            return true;
        }
        if (pearls.isOn() && item instanceof EnderpearlItem) {
            return true;
        }
        if (potions.isOn() && item instanceof ThrowablePotionItem) {
            return true;
        }
        if (experience.isOn() && item instanceof ExperienceBottleItem) {
            return true;
        }
        return throwables.isOn()
            && (item instanceof SnowballItem || item instanceof EggItem || item instanceof WindChargeItem);
    }
}
