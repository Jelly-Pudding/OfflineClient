package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

// Puts the name tag in your hotbar on every chosen mob in reach.
public final class AutoNametag extends Module {

    // Ticks before the same mob is tried again.
    private static final int COOLDOWN = 20;

    private final RegistryListSetting<EntityType<?>> entities = new RegistryListSetting<>("Entities",
        "The mobs to name.", BuiltInRegistries.ENTITY_TYPE,
        List.of(EntityTypes.VILLAGER, EntityTypes.HORSE, EntityTypes.WOLF, EntityTypes.CAT));
    private final NumberSetting range = new NumberSetting("Range",
        "How close a mob has to be.", 5, 1, 6, 0.1);
    private final EnumSetting<TargetPriority> priority = TargetPriority.setting("Names", TargetPriority.NEAREST);
    private final BoolSetting rename = new BoolSetting("Rename",
        "Also names mobs that already carry a different name.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards the mob on the server side.", true);

    private final Cooldowns<Integer> tried = new Cooldowns<>();
    private final SlotSwap slots = new SlotSwap();
    private int named;

    public AutoNametag() {
        super("AutoNametag", "Names every chosen mob near you with the tag you carry.", Category.WORLD);
        addSettings(entities, range, priority, rename, rotate);
        searchTags("name tag", "rename mobs");
    }

    @Override
    public String getSuffix() {
        return count(named, "named");
    }

    @Override
    protected void onEnable() {
        tried.clear();
        named = 0;
    }

    @Override
    protected void onDisable() {
        slots.restoreIfMine();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || mc.gui.screen() != null) {
            return;
        }
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.NAME_TAG));
        if (slot == -1) {
            disable("No name tag in the hotbar.");
            return;
        }
        tried.tick();
        ItemStack tag = mc.player.getInventory().getItem(slot);

        Entity target = EntityUtil.best(range.getValue(), priority.getValue(),
            entity -> wanted(entity, tag));
        if (target == null) {
            slots.restoreIfMine();
            return;
        }
        slots.select(slot);
        if (EntityUtil.interact(target, InteractionHand.MAIN_HAND, rotate.isOn())) {
            named++;
        }
        tried.put(target.getId(), COOLDOWN);
        slots.restoreIfMine();
    }

    private boolean wanted(Entity entity, ItemStack tag) {
        if (entity instanceof Player || !(entity instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }
        if (!entities.contains(entity.getType()) || tried.contains(entity.getId())) {
            return false;
        }
        if (!entity.hasCustomName()) {
            return true;
        }
        // A mob already wearing this exact name gains nothing from another tag.
        return rename.isOn() && !entity.getCustomName().getString().equals(tag.getHoverName().getString());
    }
}
