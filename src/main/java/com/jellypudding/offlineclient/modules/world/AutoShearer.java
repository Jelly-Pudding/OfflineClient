package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.TakeFrom;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;

import java.util.List;
import java.util.Set;

// Clips the picked animals and mobs as they wander past.
public final class AutoShearer extends Module {

    // Every kind the server lets shears take something from.
    private static final Set<EntityType<?>> SHEARABLE = Set.of(EntityTypes.SHEEP, EntityTypes.MOOSHROOM,
        EntityTypes.SNOW_GOLEM, EntityTypes.COPPER_GOLEM, EntityTypes.BOGGED, EntityTypes.SULFUR_CUBE);

    private final RegistryListSetting<EntityType<?>> mobs = new RegistryListSetting<>("Mobs",
        "The animals and mobs that get sheared. Click to pick them.", BuiltInRegistries.ENTITY_TYPE,
        List.of(EntityTypes.SHEEP))
        .only(SHEARABLE::contains);
    private final NumberSetting range = new NumberSetting("Range",
        "How close an animal has to be.", 5, 1, 6, 0.1, " blocks");
    private final BoolSetting leads = new BoolSetting("Leads",
        "Also shears animals on a lead and the first snip cuts the lead.", false);
    private final BoolSetting gear = new BoolSetting("Gear",
        "Also snips saddles and armour and carpets and harnesses off mounts nobody rides.", false);
    private final EnumSetting<TakeFrom> takeFrom = TakeFrom.setting("shears", TakeFrom.HOTBAR);
    private final BoolSetting antiBreak = new BoolSetting("Anti break",
        "Never uses shears that are about to break.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns towards the animal on the server side.", true);

    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private int sheared;

    public AutoShearer() {
        super("AutoShearer", "Shears the animals and mobs you pick as they come near.", Category.WORLD);
        addSettings(mobs, range, leads, gear, takeFrom, antiBreak, rotate);
        searchTags("sheep", "wool", "shears", "saddle");
    }

    @Override
    public String getSuffix() {
        return count(sheared, "sheared");
    }

    @Override
    protected void onEnable() {
        sheared = 0;
    }

    @Override
    protected void onDisable() {
        release();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.player.isSpectator() || mc.gui.screen() != null) {
            return;
        }
        // The shears make way whilst dying and whilst something else has the hands.
        if (mc.player.isDeadOrDying() || Hands.taken()) {
            loan.standAside();
            return;
        }
        // A sneaking click hands the leads you hold over to the animal instead.
        boolean sneaking = Modules.sneaking();
        Entity target = sneaking && !Leashable.leashableLeashedTo(mc.player).isEmpty()
            ? null : EntityUtil.nearest(range.getValue(), entity -> wanted(entity, sneaking));
        if (target == null || !holdShears()) {
            release();
            return;
        }
        // The first snip on a leashed animal only cuts the lead.
        boolean onLead = onLead(target);
        if (EntityUtil.interact(target, InteractionHand.MAIN_HAND, rotate.isOn()) && !onLead) {
            sheared++;
        }
    }

    private boolean wanted(Entity entity, boolean sneaking) {
        if (!entity.isAlive()) {
            return false;
        }
        boolean ready = entity instanceof Shearable shearable && mobs.contains(entity.getType())
            && shearable.readyForShearing();
        if (!ready && !(gear.isOn() && !sneaking && hasGear(entity))) {
            return false;
        }
        return leads.isOn() || !onLead(entity);
    }

    // On a lead or holding the lead of another. A snip cuts both.
    private static boolean onLead(Entity entity) {
        return (entity instanceof Leashable leashable && leashable.getLeashHolder() != null)
            || !Leashable.leashableLeashedTo(entity).isEmpty();
    }

    // Gear the server lets shears take. A ridden mount keeps its gear. A wolf only gives up
    // its armour to its owner and a curse of binding holds anything in place.
    private static boolean hasGear(Entity entity) {
        if (!(entity instanceof Mob mob) || mob.isVehicle() || (mob instanceof Wolf && !EntityUtil.isYours(mob))) {
            return false;
        }
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack stack = mob.getItemBySlot(slot);
            Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
            if (equippable != null && equippable.canBeSheared()
                && !EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
                return true;
            }
        }
        return false;
    }

    // Puts shears that will last in the main hand. True once they are there.
    private boolean holdShears() {
        if (usable(mc.player.getMainHandItem())) {
            return true;
        }
        return loan.select(InventoryUtil.findSlot(this::usable, takeFrom.getValue().limit()));
    }

    // Borrowed shears go home. The slot you held only comes back whilst you have not picked another.
    private void release() {
        loan.release(loan.stillMine());
    }

    private boolean usable(ItemStack stack) {
        return stack.is(Items.SHEARS) && (!antiBreak.isOn() || !ItemUtil.nearlyBroken(stack));
    }
}
