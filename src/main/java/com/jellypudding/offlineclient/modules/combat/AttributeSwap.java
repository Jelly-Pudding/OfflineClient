package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.AttackEntityEvent;
import com.jellypudding.offlineclient.event.events.LeftClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class AttributeSwap extends Module {

    public enum Mode { SIMPLE, SMART }

    // How far a spear swing is looked along for something to reach.
    private static final double SPEAR_LOOK = 7;

    // A spear hits a little outside the hitbox.
    private static final double SPEAR_MARGIN = 0.15;

    // Blocks past the normal reach before a spear is worth swapping to.
    private static final double REACH_SLACK = 0.5;

    // A mace only smashes after falling further than this.
    private static final double SMASH_FALL = 1.5;

    // A mob under this much health is about to drop its loot.
    private static final float NEARLY_DEAD = 20;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "What the swap goes to.", Mode.SMART)
        .describe(Mode.SIMPLE, "Always the one hotbar slot you pick.")
        .describe(Mode.SMART, "Whatever scores best against the target you hit.");
    private final NumberSetting targetSlot = new NumberSetting("Target slot",
        "The hotbar slot to swap to.", 1, 1, 9, 1)
        .under(mode, Mode.SIMPLE);
    private final BoolSetting swapOnMiss = new BoolSetting("Swap on miss",
        "Also swap on a swing that hits nothing so a spear can lunge.", false)
        .under(mode, Mode.SIMPLE);
    private final NumberSetting minGain = new NumberSetting("Min gain",
        "The other item must beat what you hold by this many points.", 0.5, 0, 10, 0.5)
        .under(mode, Mode.SMART);
    private final BoolSetting swapBack = new BoolSetting("Swap back",
        "Return to the slot you had once the hit has landed.", true);
    private final NumberSetting swapBackDelay = new NumberSetting("Swap back delay",
        "Ticks to hold the swapped item before returning.", 2, 0, 20, 1, " ticks")
        .under(swapBack);
    private final BoolSetting antiBreak = new BoolSetting("Anti break",
        "Skip items that are about to break.", true);

    private final BoolSetting shieldBreaker = new BoolSetting("Shield breaker",
        "Swap to an axe when the target is holding a shield up.", true)
        .under(mode, Mode.SMART);
    private final BoolSetting durabilitySaver = new BoolSetting("Durability saver",
        "Lean towards items that cannot wear out or carry Unbreaking.", true)
        .under(mode, Mode.SMART);

    private final BoolSetting swords = new BoolSetting("Sword enchantments",
        "Score the enchantments swords carry.", true)
        .under(mode, Mode.SMART);
    private final BoolSetting fireAspect = new BoolSetting("Fire aspect",
        "Prefer Fire Aspect against a target that is not burning yet.", true)
        .under(swords);
    private final BoolSetting looting = new BoolSetting("Looting",
        "Prefer Looting against mobs and twice as much when they are nearly dead.", true)
        .under(swords);
    private final BoolSetting sharpness = new BoolSetting("Sharpness",
        "Count the extra damage from Sharpness.", true)
        .under(swords);
    private final BoolSetting smite = new BoolSetting("Smite",
        "Count the extra damage from Smite against the undead.", true)
        .under(swords);
    private final BoolSetting bane = new BoolSetting("Bane of arthropods",
        "Count the extra damage from Bane of Arthropods against bugs.", true)
        .under(swords);
    private final BoolSetting sweeping = new BoolSetting("Sweeping edge",
        "Prefer Sweeping Edge for the wider hit.", true)
        .under(swords);

    private final BoolSetting maces = new BoolSetting("Mace enchantments",
        "Score maces and their enchantments whilst you are falling.", true)
        .under(mode, Mode.SMART);
    private final BoolSetting regularMace = new BoolSetting("Regular mace",
        "Swap to any mace once you have fallen far enough to smash.", true)
        .under(maces);
    private final BoolSetting density = new BoolSetting("Density",
        "Prefer Density for the harder smash the further you fall.", true)
        .under(maces);
    private final BoolSetting breach = new BoolSetting("Breach",
        "Prefer Breach against a target wearing armour.", true)
        .under(maces);
    private final BoolSetting windBurst = new BoolSetting("Wind burst",
        "Prefer Wind Burst to bounce up again after the smash.", true)
        .under(maces);

    private final BoolSetting spears = new BoolSetting("Spear swaps",
        "Swap to a spear for reach or for a lunge.", true)
        .under(mode, Mode.SMART);
    private final BoolSetting lunge = new BoolSetting("Lunge",
        "Swap to a Lunge spear on a swing that hits nothing so you travel.", true)
        .under(spears);
    private final BoolSetting reach = new BoolSetting("Reach",
        "Swap to a spear when what you swing at is past normal reach.", true)
        .under(spears);
    private final BoolSetting keepLungeSpear = new BoolSetting("Keep lunge spear",
        "Never use a Lunge spear for the reach swap.", true)
        .under(reach);

    private final BoolSetting others = new BoolSetting("Other enchantments",
        "Score enchantments that live on tridents and spears.", true)
        .under(mode, Mode.SMART);
    private final BoolSetting impaling = new BoolSetting("Impaling",
        "Count the extra damage from Impaling against sea creatures.", true)
        .under(others);

    private final BoolSetting onlyWithKillAura = new BoolSetting("Only with KillAura",
        "Stay put unless KillAura is on.", false);
    private final BoolSetting onlyOnWeapon = new BoolSetting("Only on weapon",
        "Only swap whilst you already hold one of the ticked kinds.", false);
    private final BoolSetting sword = new BoolSetting("Sword", "A sword counts.", true)
        .under(onlyOnWeapon);
    private final BoolSetting axe = new BoolSetting("Axe", "An axe counts.", true)
        .under(onlyOnWeapon);
    private final BoolSetting pickaxe = new BoolSetting("Pickaxe", "A pickaxe counts.", true)
        .under(onlyOnWeapon);
    private final BoolSetting shovel = new BoolSetting("Shovel", "A shovel counts.", true)
        .under(onlyOnWeapon);
    private final BoolSetting hoe = new BoolSetting("Hoe", "A hoe counts.", true)
        .under(onlyOnWeapon);
    private final BoolSetting mace = new BoolSetting("Mace", "A mace counts.", true)
        .under(onlyOnWeapon);
    private final BoolSetting trident = new BoolSetting("Trident", "A trident counts.", true)
        .under(onlyOnWeapon);

    private final SlotSwap slots = new SlotSwap();
    private int timer;

    public AttributeSwap() {
        super("AttributeSwap", "Swaps to your best item for the hit the moment you attack.",
            Category.COMBAT);
        addSettings(mode, targetSlot, swapOnMiss, minGain, swapBack, swapBackDelay, antiBreak,
            shieldBreaker, durabilitySaver,
            swords, fireAspect, looting, sharpness, smite, bane, sweeping,
            maces, regularMace, density, breach, windBurst,
            spears, lunge, reach, keepLungeSpear,
            others, impaling,
            onlyWithKillAura, onlyOnWeapon, sword, axe, pickaxe, shovel, hoe, mace, trident);
        searchTags("attribute", "damage swap", "hotbar", "spear", "mace");
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.WEAPON_SWAP;
    }

    @Override
    protected void onEnable() {
        slots.forget();
        timer = 0;
    }

    @Override
    protected void onDisable() {
        slots.restore();
    }

    // The click arrives before the game reads the held item. A swap here counts.
    @Subscribe
    private void onLeftClick(LeftClickEvent event) {
        if (!canSwap() || mc.hitResult == null || mc.hitResult.getType() == HitResult.Type.BLOCK) {
            return;
        }
        if (mode.is(Mode.SIMPLE)) {
            if (swapOnMiss.isOn()) {
                swapTo(targetSlot.getInt() - 1);
            }
            return;
        }
        if (!spears.isOn()) {
            return;
        }
        Entity far = reach.isOn() ? spearTarget() : null;
        if (far != null) {
            if (mc.player.distanceTo(far) <= mc.player.entityInteractionRange() + REACH_SLACK) {
                return;
            }
            int spear = spearSlot(false);
            if (spear != -1) {
                swapTo(spear);
                return;
            }
        }
        if (lunge.isOn()) {
            swapTo(spearSlot(true));
        }
    }

    @Subscribe
    private void onAttack(AttackEntityEvent event) {
        if (!canSwap()) {
            return;
        }
        if (mode.is(Mode.SIMPLE)) {
            // The miss swap already handled this click.
            if (!swapOnMiss.isOn()) {
                swapTo(targetSlot.getInt() - 1);
            }
            return;
        }
        if (event.getTarget() instanceof LivingEntity target) {
            swapTo(bestSlot(target));
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || !slots.isHolding()) {
            return;
        }
        if (!swapBack.isOn()) {
            slots.forget();
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        slots.restore();
    }

    private boolean canSwap() {
        if (!inGame() || mc.player.isSpectator() || slots.isHolding()) {
            return false;
        }
        if (onlyWithKillAura.isOn() && !Modules.enabled(KillAura.class)) {
            return false;
        }
        return !onlyOnWeapon.isOn() || allowedWeapon(mc.player.getMainHandItem());
    }

    private boolean allowedWeapon(ItemStack held) {
        return sword.isOn() && held.is(ItemTags.SWORDS)
            || axe.isOn() && held.is(ItemTags.AXES)
            || pickaxe.isOn() && held.is(ItemTags.PICKAXES)
            || shovel.isOn() && held.is(ItemTags.SHOVELS)
            || hoe.isOn() && held.is(ItemTags.HOES)
            || mace.isOn() && held.getItem() instanceof MaceItem
            || trident.isOn() && held.getItem() instanceof TridentItem;
    }

    // The slot packet leaves before the attack packet.
    private void swapTo(int slot) {
        if (slot < 0 || slot >= InventoryUtil.HOTBAR_SIZE || slot == InventoryUtil.selectedSlot()) {
            return;
        }
        slots.select(slot);
        timer = swapBackDelay.getInt();
    }

    // The first spear in the hotbar that fits. A lunge spear is asked for or avoided.
    private int spearSlot(boolean wantLunge) {
        for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (i == InventoryUtil.selectedSlot() || !stack.is(ItemTags.SPEARS) || skipped(stack)) {
                continue;
            }
            boolean hasLunge = ItemUtil.enchantLevel(Enchantments.LUNGE, stack) > 0;
            if (wantLunge ? hasLunge : !(keepLungeSpear.isOn() && hasLunge)) {
                return i;
            }
        }
        return -1;
    }

    // The nearest entity a spear thrust along the crosshair would touch.
    private Entity spearTarget() {
        Vec3 start = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1);
        Vec3 end = start.add(look.scale(SPEAR_LOOK));
        AABB sweep = mc.player.getBoundingBox().expandTowards(look.scale(SPEAR_LOOK)).inflate(1);
        Entity best = null;
        double bestDistance = SPEAR_LOOK * SPEAR_LOOK;
        for (Entity entity : mc.level.getEntities(mc.player, sweep,
            other -> !other.isSpectator() && other.isPickable())) {
            if (entity.getBoundingBox().inflate(SPEAR_MARGIN).clip(start, end).isEmpty()) {
                continue;
            }
            double distance = start.distanceToSqr(entity.position());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    private boolean skipped(ItemStack stack) {
        return antiBreak.isOn() && ItemUtil.nearlyBroken(stack);
    }

    // The hotbar slot scoring best against the target or minus one to stay put.
    private int bestSlot(LivingEntity target) {
        ItemStack held = mc.player.getMainHandItem();
        if (shieldBreaker.isOn() && target.isBlocking() && !held.is(ItemTags.AXES)) {
            int axeSlot = AutoWeapon.bestAxeSlot(target, antiBreak.isOn());
            if (axeSlot != -1) {
                return axeSlot;
            }
        }
        int selected = InventoryUtil.selectedSlot();
        double bestScore = score(held, target) + minGain.getValue();
        int best = -1;
        for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (i == selected || skipped(stack) || stack.isEmpty() && !durabilitySaver.isOn()) {
                continue;
            }
            double score = score(stack, target);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    // Points for one item. Real damage counts one point a heart and the rest are bonuses.
    private double score(ItemStack stack, LivingEntity target) {
        double score = durabilitySaver.isOn() ? durabilityScore(stack) : 0;
        if (stack.isEmpty()) {
            return score;
        }
        score += ItemUtil.attributeValue(stack, Attributes.ATTACK_DAMAGE, EquipmentSlot.MAINHAND);
        if (swords.isOn()) {
            score += swordScore(stack, target);
        }
        if (maces.isOn()) {
            score += maceScore(stack, target);
        }
        if (others.isOn() && impaling.isOn()
            && EntityUtil.typeIs(target, EntityTypeTags.SENSITIVE_TO_IMPALING)) {
            score += 2.5 * level(Enchantments.IMPALING, stack);
        }
        return score;
    }

    // An item that cannot wear out is a free hit. Unbreaking is a small nudge.
    private double durabilityScore(ItemStack stack) {
        if (!stack.isDamageableItem()) {
            return 1.5;
        }
        return 0.1 * level(Enchantments.UNBREAKING, stack);
    }

    private double swordScore(ItemStack stack, LivingEntity target) {
        double score = 0;
        boolean burning = target.isOnFire();
        if (fireAspect.isOn() && !burning && !fireProof(target)) {
            score += 4 * level(Enchantments.FIRE_ASPECT, stack);
        }
        if (looting.isOn() && !(target instanceof Player)) {
            boolean nearlyDead = target.getHealth() < NEARLY_DEAD || burning;
            score += (nearlyDead ? 4 : 2) * level(Enchantments.LOOTING, stack);
        }
        if (sharpness.isOn()) {
            int sharp = level(Enchantments.SHARPNESS, stack);
            score += sharp > 0 ? 0.5 * sharp + 0.5 : 0;
        }
        if (smite.isOn() && EntityUtil.typeIs(target, EntityTypeTags.SENSITIVE_TO_SMITE)) {
            score += 2.5 * level(Enchantments.SMITE, stack);
        }
        if (bane.isOn() && EntityUtil.typeIs(target, EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS)) {
            score += 2.5 * level(Enchantments.BANE_OF_ARTHROPODS, stack);
        }
        if (sweeping.isOn()) {
            score += level(Enchantments.SWEEPING_EDGE, stack);
        }
        return score;
    }

    // A mace only earns anything mid fall apart from Breach which works on the ground too.
    private double maceScore(ItemStack stack, LivingEntity target) {
        double score = 0;
        double armor = target.getAttributeValue(Attributes.ARMOR);
        if (breach.isOn() && armor > 0) {
            score += 0.15 * armor * level(Enchantments.BREACH, stack);
        }
        double fall = mc.player.fallDistance;
        if (fall <= SMASH_FALL) {
            return score;
        }
        if (regularMace.isOn() && stack.getItem() instanceof MaceItem) {
            score += smashBonus(fall);
        }
        if (density.isOn()) {
            score += 0.5 * fall * level(Enchantments.DENSITY, stack);
        }
        if (windBurst.isOn()) {
            score += 3 * level(Enchantments.WIND_BURST, stack);
        }
        return score;
    }

    // The vanilla smash bonus. Four a block to three then two to eight then one.
    private static double smashBonus(double fall) {
        if (fall <= 3) {
            return 4 * fall;
        }
        if (fall <= 8) {
            return 12 + 2 * (fall - 3);
        }
        return 22 + (fall - 8);
    }

    // Fire Resistance or any Fire Protection makes Fire Aspect a waste.
    private static boolean fireProof(LivingEntity target) {
        if (target.hasEffect(MobEffects.FIRE_RESISTANCE)) {
            return true;
        }
        for (EquipmentSlot slot : ItemUtil.ARMOR_SLOTS) {
            if (level(Enchantments.FIRE_PROTECTION, target.getItemBySlot(slot)) > 0) {
                return true;
            }
        }
        return false;
    }

    private static int level(ResourceKey<Enchantment> enchantment, ItemStack stack) {
        return ItemUtil.enchantLevel(enchantment, stack);
    }
}
