package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Puts the name tag in your hotbar on every chosen mob in reach.
public final class AutoNametag extends Module {

    // Ticks before the same mob is tried again.
    private static final int COOLDOWN = 20;

    private final RegistryListSetting<EntityType<?>> entities = new RegistryListSetting<>("Entities",
        "The mobs to name.", BuiltInRegistries.ENTITY_TYPE,
        List.of(EntityTypes.VILLAGER, EntityTypes.HORSE, EntityTypes.WOLF, EntityTypes.CAT));
    private final NumberSetting range = new NumberSetting("Range",
        "How close a mob has to be.", 5, 1, 6, 0.1).max(6);
    private final BoolSetting rename = new BoolSetting("Rename",
        "Also names mobs that already carry a different name.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards the mob on the server side.", true);

    private final Map<Integer, Integer> tried = new HashMap<>();
    private int lastTick;
    private final SlotSwap slots = new SlotSwap();
    private int named;

    public AutoNametag() {
        super("AutoNametag", "Names every chosen mob near you with the tag you carry.", Category.WORLD);
        addSettings(entities, range, rename, rotate);
        searchTags("name tag", "rename mobs");
    }

    @Override
    public String getSuffix() {
        return named == 0 ? null : named + " named";
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
            ChatUtil.error("No name tag in the hotbar.");
            setEnabled(false);
            return;
        }
        int now = mc.player.tickCount;
        if (now < lastTick) {
            tried.clear();
        }
        lastTick = now;
        tried.values().removeIf(expiry -> expiry <= now);
        ItemStack tag = mc.player.getInventory().getItem(slot);

        Entity target = EntityUtil.nearest(range.getValue(), entity -> wanted(entity, tag));
        if (target == null) {
            slots.restoreIfMine();
            return;
        }
        slots.select(slot);
        if (rotate.isOn()) {
            BlockUtil.faceVector(target.getBoundingBox().getCenter());
        }
        EntityHitResult hit = new EntityHitResult(target, target.getBoundingBox().getCenter());
        if (mc.gameMode.interact(mc.player, target, hit, InteractionHand.MAIN_HAND).consumesAction()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
            named++;
        }
        tried.put(target.getId(), now + COOLDOWN);
        slots.restoreIfMine();
    }

    private boolean wanted(Entity entity, ItemStack tag) {
        if (entity instanceof Player || !(entity instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }
        if (!entities.contains(entity.getType()) || tried.containsKey(entity.getId())) {
            return false;
        }
        if (!entity.hasCustomName()) {
            return true;
        }
        // A mob already wearing this exact name gains nothing from another tag.
        return rename.isOn() && !entity.getCustomName().getString().equals(tag.getHoverName().getString());
    }
}
