package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.ListMode;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

public final class NoInteract extends Module {

    public enum Hand { NONE, MAIN_HAND, OFF_HAND, BOTH }

    public enum Protect { NONE, HIT, USE, BOTH }

    public enum Target { BLOCKS, ENTITIES, BOTH }

    private static final int LAVA_QUIET_TICKS = 200;

    private final BoolSetting explosives = new BoolSetting("Explosives",
        "Blocks right clicks on beds and respawn anchors where they detonate.", true);
    private final RegistryListSetting<Block> useBlocks = new RegistryListSetting<>("No use",
        "Blocks that never take a right click. Click to pick them.",
        BuiltInRegistries.BLOCK, List.of());
    private final EnumSetting<ListMode> useBlocksMode = ListMode.setting("No use list mode",
        ListMode.BLACKLIST, "Only the listed blocks take a right click.",
        "Every block except the listed ones takes a right click.");
    private final EnumSetting<Hand> useHand = new EnumSetting<>("No use hand",
        "A hand whose right clicks on blocks are all dropped.", Hand.NONE)
        .describe(Hand.NONE, "Both hands click blocks as normal.")
        .describe(Hand.MAIN_HAND, "The main hand never right clicks a block.")
        .describe(Hand.OFF_HAND, "The off hand never right clicks a block.")
        .describe(Hand.BOTH, "Neither hand right clicks a block.");
    private final RegistryListSetting<Block> mineBlocks = new RegistryListSetting<>("No mine",
        "Blocks that never get broken. Click to pick them.",
        BuiltInRegistries.BLOCK, List.of());
    private final EnumSetting<ListMode> mineBlocksMode = ListMode.setting("No mine list mode",
        ListMode.BLACKLIST, "Only the listed blocks get broken.",
        "Every block except the listed ones gets broken.");
    private final BoolSetting lava = new BoolSetting("No mine by lava",
        "Leaves a block standing whilst lava beside or above it would pour into the gap. Your hand and every"
            + " mining module keep to it.", false);
    private final RegistryListSetting<EntityType<?>> hitEntities = new RegistryListSetting<>("No hit",
        "Entities that never take a hit. Click to pick them.",
        BuiltInRegistries.ENTITY_TYPE, List.of());
    private final EnumSetting<ListMode> hitEntitiesMode = ListMode.setting("No hit list mode",
        ListMode.BLACKLIST, "Only the listed entities take a hit.",
        "Every entity except the listed ones takes a hit.");
    private final RegistryListSetting<EntityType<?>> useEntities = new RegistryListSetting<>(
        "No entity use", "Entities that never take a right click. Click to pick them.",
        BuiltInRegistries.ENTITY_TYPE, List.of());
    private final EnumSetting<ListMode> useEntitiesMode = ListMode.setting("No entity use list mode",
        ListMode.BLACKLIST, "Only the listed entities take a right click.",
        "Every entity except the listed ones takes a right click.");
    private final EnumSetting<Hand> useEntityHand = new EnumSetting<>("No entity use hand",
        "A hand whose right clicks on entities are all dropped.", Hand.NONE)
        .describe(Hand.NONE, "Both hands click entities as normal.")
        .describe(Hand.MAIN_HAND, "The main hand never right clicks an entity.")
        .describe(Hand.OFF_HAND, "The off hand never right clicks an entity.")
        .describe(Hand.BOTH, "Neither hand right clicks an entity.");
    private final EnumSetting<Protect> friends = protect("Friends", "a friend", Protect.BOTH);
    private final EnumSetting<Protect> babies = protect("Babies", "a baby animal", Protect.HIT);
    private final EnumSetting<Protect> pets = protect("Pets", "a tamed animal", Protect.HIT);
    private final EnumSetting<Protect> named = protect("Named", "anything wearing a name tag",
        Protect.NONE);
    private final BoolSetting useAnyway = new BoolSetting("Use item anyway",
        "A right click NoInteract drops on a block or an entity still uses the item in your hand as if you"
            + " clicked the air. Items on the No use items list stay unused.", false);
    private final RegistryListSetting<Item> useItems = new RegistryListSetting<>("No use items",
        "Items that never right click a block or an entity whilst in your hand. Click to pick them.",
        BuiltInRegistries.ITEM, List.of());
    private final EnumSetting<Target> useItemsOn = new EnumSetting<>("No use items on",
        "What a right click with one of those items is kept off.", Target.BOTH)
        .describe(Target.BLOCKS, "Blocks only. A click on an entity goes through.")
        .describe(Target.ENTITIES, "Entities only. A click on a block goes through.")
        .describe(Target.BOTH, "Blocks and entities.")
        .under(useItems, () -> useItems.size() > 0);
    private final BoolSetting inShulkers = new BoolSetting("Inside shulker boxes",
        "A shulker box that holds one of those items counts as one.", true)
        .under(useItems, () -> useItems.size() > 0);
    private final RegistryListSetting<Item> noDrop = new RegistryListSetting<>("No drop",
        "Items you can never throw away by hand. Click to pick them.", BuiltInRegistries.ITEM,
        keptByDefault());
    private final BoolSetting intoFrames = new BoolSetting("Into frames",
        "Also keeps those items out of item frames and pots.", true)
        .under(noDrop, () -> noDrop.size() > 0);
    private final KeybindSetting throwKey = new KeybindSetting("Throw key",
        "Holding this key lets a protected item be thrown.", KeybindSetting.UNBOUND)
        .under(noDrop, () -> noDrop.size() > 0);

    private int lavaToldAt = -LAVA_QUIET_TICKS;

    public NoInteract() {
        super("NoInteract", "Stops clicks and throws on the things you choose to protect.", Category.PLAYER);
        addSettings(explosives, useBlocks, useBlocksMode, useHand, mineBlocks, mineBlocksMode, lava,
            hitEntities, hitEntitiesMode, useEntities, useEntitiesMode, useEntityHand,
            friends, babies, pets, named, useAnyway, useItems, useItemsOn, inShulkers, noDrop, intoFrames,
            throwKey);
        searchTags("no click", "protect", "anti bed", "anti drop", "no throw", "anti interact", "pearl through");
    }

    private static List<Item> keptByDefault() {
        List<Item> kept = new ArrayList<>(List.of(Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE,
            Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE, Items.DIAMOND_SPEAR,
            Items.NETHERITE_SWORD, Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL,
            Items.NETHERITE_HOE, Items.NETHERITE_SPEAR, Items.MACE, Items.TRIDENT, Items.BOW,
            Items.CROSSBOW, Items.SHIELD, Items.ELYTRA, Items.TOTEM_OF_UNDYING));
        BuiltInRegistries.ITEM.stream().filter(ItemUtil::isShulkerBox).forEach(kept::add);
        return kept;
    }

    private static EnumSetting<Protect> protect(String name, String what, Protect defaultValue) {
        return new EnumSetting<>(name, "Which clicks " + what + " is spared.", defaultValue)
            .describe(Protect.NONE, "Hits and right clicks land on " + what + ".")
            .describe(Protect.HIT, "Never lets you hit " + what + ".")
            .describe(Protect.USE, "Never lets you right click " + what + ".")
            .describe(Protect.BOTH, "Never lets you hit or right click " + what + ".");
    }

    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (inGame() && mc.hitResult != null && dropsWholeClick(mc.hitResult)) {
            event.cancel();
        }
    }

    private boolean dropsWholeClick(HitResult target) {
        if (target instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return blocksUse(hit.getBlockPos());
        }
        return target instanceof EntityHitResult hit && blocksEntityUse(hit.getEntity());
    }

    // Called from the game around a right click. True when the click goes on as one into the air
    // and the held items are used past what the crosshair rests on. Neither hand may hold an item
    // that is kept from use or that item would be used.
    public boolean clicksAsAir(HitResult target) {
        if (!isEnabled() || !useAnyway.isOn() || !inGame() || !dropsWholeClick(target)) {
            return false;
        }
        boolean onBlock = target.getType() == HitResult.Type.BLOCK;
        return !keptFromUse(mc.player.getMainHandItem(), onBlock) && !keptFromUse(mc.player.getOffhandItem(), onBlock);
    }

    public boolean blocksUse(BlockPos pos) {
        if (!isEnabled() || !inGame()) {
            return false;
        }
        BlockState state = mc.level.getBlockState(pos);
        Block block = state.getBlock();
        if (listed(useBlocks, useBlocksMode, block)) {
            return true;
        }
        if (!explosives.isOn()) {
            return false;
        }
        if (block instanceof BedBlock) {
            return ExplosionUtil.bedsExplodeHere();
        }
        return block instanceof RespawnAnchorBlock && anchorDetonates(state);
    }

    private boolean anchorDetonates(BlockState state) {
        if (!ExplosionUtil.anchorsExplodeHere()) {
            return false;
        }
        int charge = state.getValue(RespawnAnchorBlock.CHARGE);
        if (charge == 0) {
            return false;
        }
        return charge >= RespawnAnchorBlock.MAX_CHARGES || !holdingGlowstone();
    }

    // Glowstone tops an anchor up instead of setting it off.
    private boolean holdingGlowstone() {
        return mc.player.getMainHandItem().is(Items.GLOWSTONE)
            || mc.player.getOffhandItem().is(Items.GLOWSTONE);
    }

    // Called from the game mode mixin for each hand that tries a block. Null lets the click through.
    // FAIL ends the whole right click and PASS goes on to use the item in that hand. A protected
    // item never goes into a pot.
    public InteractionResult dropsBlockClick(InteractionHand hand, BlockHitResult hit) {
        if (!isEnabled()) {
            return null;
        }
        ItemStack held = mc.player.getItemInHand(hand);
        // A right click of the player carries the game's own crosshair result. A module builds its own.
        boolean player = hit == mc.hitResult;
        if (player && keptFromUse(held, true)) {
            return InteractionResult.FAIL;
        }
        boolean dropped = covers(useHand.getValue(), hand) || intoFrames.isOn()
            && mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof DecoratedPotBlock && keeps(held);
        if (!dropped) {
            return null;
        }
        return player && useAnyway.isOn() ? InteractionResult.PASS : InteractionResult.FAIL;
    }

    // The same for an entity. The game uses the held item after any entity click that fails and
    // CONSUME ends a right click of the player without a swing. A module only hears FAIL. A
    // protected item never goes into an empty item frame. A frame that holds something only turns it.
    public InteractionResult dropsEntityClick(InteractionHand hand, Entity target, EntityHitResult hit) {
        if (!isEnabled()) {
            return null;
        }
        ItemStack held = mc.player.getItemInHand(hand);
        boolean player = hit == mc.hitResult;
        if (player && keptFromUse(held, false)) {
            return InteractionResult.CONSUME;
        }
        boolean dropped = covers(useEntityHand.getValue(), hand) || intoFrames.isOn()
            && target instanceof ItemFrame frame && frame.getItem().isEmpty() && keeps(held);
        if (!dropped) {
            return null;
        }
        if (!player) {
            return InteractionResult.FAIL;
        }
        return useAnyway.isOn() ? InteractionResult.PASS : InteractionResult.CONSUME;
    }

    private boolean keptFromUse(ItemStack stack, boolean onBlock) {
        if (useItems.size() == 0 || stack.isEmpty() || useItemsOn.is(onBlock ? Target.ENTITIES : Target.BLOCKS)) {
            return false;
        }
        if (useItems.contains(stack.getItem())) {
            return true;
        }
        ItemContainerContents box = inShulkers.isOn() && ItemUtil.isShulkerBox(stack.getItem())
            ? stack.get(DataComponents.CONTAINER) : null;
        if (box == null) {
            return false;
        }
        for (ItemStackTemplate inside : box.nonEmptyItems()) {
            if (useItems.contains(inside.item().value())) {
                return true;
            }
        }
        return false;
    }

    public boolean keeps(ItemStack stack) {
        return isEnabled() && !stack.isEmpty() && noDrop.contains(stack.getItem()) && !throwKey.isHeld();
    }

    public boolean refusesThrow(AbstractContainerMenu menu, int slot, ContainerInput kind) {
        if (kind == ContainerInput.THROW) {
            return slot >= 0 && slot < menu.slots.size() && keeps(menu.getSlot(slot).getItem());
        }
        boolean outside = slot == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE
            && (kind == ContainerInput.PICKUP || kind == ContainerInput.QUICK_MOVE);
        return outside && keeps(menu.getCarried());
    }

    private static boolean covers(Hand chosen, InteractionHand hand) {
        return switch (chosen) {
            case NONE -> false;
            case MAIN_HAND -> hand == InteractionHand.MAIN_HAND;
            case OFF_HAND -> hand == InteractionHand.OFF_HAND;
            case BOTH -> true;
        };
    }

    public boolean blocksMining(BlockPos pos) {
        if (!isEnabled() || !inGame()) {
            return false;
        }
        return listed(mineBlocks, mineBlocksMode, mc.level.getBlockState(pos).getBlock()) || refusesBreak(pos);
    }

    // True whilst No mine by lava keeps the block standing. Asking does nothing else.
    public boolean holdsBackLava(BlockPos pos) {
        return isEnabled() && lava.isOn() && inGame() && !mc.level.getBlockState(pos).isAir()
            && BlockUtil.holdsBackLiquid(pos, liquid -> liquid.is(FluidTags.LAVA));
    }

    // The same for a break that was about to go out. A chat line says why at most once in ten seconds.
    public boolean refusesBreak(BlockPos pos) {
        if (!holdsBackLava(pos)) {
            return false;
        }
        int now = mc.player.tickCount;
        if (now - lavaToldAt >= LAVA_QUIET_TICKS || now < lavaToldAt) {
            lavaToldAt = now;
            tell("left the block at §f" + BlockUtil.text(pos)
                + " §7standing to hold back lava.");
        }
        return true;
    }

    private boolean blocksHit(Entity entity) {
        if (!isEnabled() || entity == null) {
            return false;
        }
        return listed(hitEntities, hitEntitiesMode, entity.getType())
            || protects(entity, Protect.HIT);
    }

    private boolean blocksEntityUse(Entity entity) {
        if (!isEnabled() || entity == null) {
            return false;
        }
        return listed(useEntities, useEntitiesMode, entity.getType())
            || protects(entity, Protect.USE);
    }

    private boolean protects(Entity entity, Protect click) {
        if (entity instanceof Player player && EntityUtil.isFriend(player)
            && spared(friends, click)) {
            return true;
        }
        if (entity instanceof LivingEntity living && living.isBaby() && spared(babies, click)) {
            return true;
        }
        // An untamed wolf is ownable but has no owner.
        if (entity instanceof OwnableEntity ownable && ownable.getOwnerReference() != null
            && spared(pets, click)) {
            return true;
        }
        return entity.hasCustomName() && spared(named, click);
    }

    private static boolean spared(EnumSetting<Protect> setting, Protect click) {
        return setting.is(click) || setting.is(Protect.BOTH);
    }

    private static <T> boolean listed(RegistryListSetting<T> list, EnumSetting<ListMode> mode,
                                      T entry) {
        return !mode.getValue().admits(list.contains(entry));
    }

    // A swing at nothing names the empty block at the end of the reach and is never held back.
    public boolean blocksAttack(HitResult result) {
        if (result instanceof EntityHitResult hit) {
            return blocksHit(hit.getEntity());
        }
        return result instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && blocksMining(hit.getBlockPos());
    }
}
