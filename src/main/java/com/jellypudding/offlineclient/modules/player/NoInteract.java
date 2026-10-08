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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
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
import net.minecraft.world.item.Items;
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

    // Which clicks a protected kind of entity is spared.
    public enum Protect { NONE, HIT, USE, BOTH }

    private final BoolSetting explosives = new BoolSetting("Explosives",
        "Block right clicks on beds and respawn anchors where they detonate.", true);
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
    private final RegistryListSetting<Item> noDrop = new RegistryListSetting<>("No drop",
        "Items you can never throw away by hand. Click to pick them.", BuiltInRegistries.ITEM,
        keptByDefault());
    private final BoolSetting intoFrames = new BoolSetting("Into frames",
        "Also keeps those items out of item frames and pots.", true)
        .under(noDrop, () -> noDrop.size() > 0);
    private final KeybindSetting throwKey = new KeybindSetting("Throw key",
        "Holding this key lets a protected item be thrown.", KeybindSetting.UNBOUND)
        .under(noDrop, () -> noDrop.size() > 0);

    public NoInteract() {
        super("NoInteract", "Stops clicks and throws on the things you choose to protect.", Category.PLAYER);
        addSettings(explosives, useBlocks, useBlocksMode, useHand, mineBlocks, mineBlocksMode,
            hitEntities, hitEntitiesMode, useEntities, useEntitiesMode, useEntityHand,
            friends, babies, pets, named, noDrop, intoFrames, throwKey);
        searchTags("no click", "protect", "anti bed", "anti drop", "no throw");
    }

    // Diamond and netherite tools and weapons and other gear that is hard to replace. Every
    // shulker box joins them whatever its colour.
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
            .describe(Protect.HIT, "Never hit " + what + ".")
            .describe(Protect.USE, "Never right click " + what + ".")
            .describe(Protect.BOTH, "Never hit or right click " + what + ".");
    }

    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (!inGame() || mc.hitResult == null) {
            return;
        }
        BlockHitResult aimed = BlockUtil.aimedBlock();
        if (aimed != null && blocksUse(aimed.getBlockPos())) {
            event.cancel();
        }
        if (mc.hitResult instanceof EntityHitResult hit && blocksEntityUse(hit.getEntity())) {
            event.cancel();
        }
    }

    private boolean blocksUse(BlockPos pos) {
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
        return block instanceof RespawnAnchorBlock && anchorDetonates(state, pos);
    }

    // True when the click sets the anchor off rather than charging it or setting a spawn.
    private boolean anchorDetonates(BlockState state, BlockPos pos) {
        if (!ExplosionUtil.anchorsExplodeAt(pos)) {
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

    // Called from the game mode mixin for each hand that tries a block. A protected item
    // never goes into a pot.
    public boolean blocksUseWith(InteractionHand hand, BlockPos pos) {
        if (!isEnabled()) {
            return false;
        }
        if (covers(useHand.getValue(), hand)) {
            return true;
        }
        return intoFrames.isOn() && mc.level.getBlockState(pos).getBlock() instanceof DecoratedPotBlock
            && keeps(mc.player.getItemInHand(hand));
    }

    // Called from the game mode mixin for each hand that tries an entity. A protected item
    // never goes into an empty item frame. A frame that holds something only turns it.
    public boolean blocksEntityUseWith(InteractionHand hand, Entity target) {
        if (!isEnabled()) {
            return false;
        }
        if (covers(useEntityHand.getValue(), hand)) {
            return true;
        }
        return intoFrames.isOn() && target instanceof ItemFrame frame && frame.getItem().isEmpty()
            && keeps(mc.player.getItemInHand(hand));
    }

    // True when the stack may not be thrown away. Holding the throw key lets it go.
    public boolean keeps(ItemStack stack) {
        return isEnabled() && !stack.isEmpty() && noDrop.contains(stack.getItem()) && !throwKey.isHeld();
    }

    // True for a menu click that would throw a protected item out of a slot or drop it off the
    // cursor outside the window.
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
        return listed(mineBlocks, mineBlocksMode, mc.level.getBlockState(pos).getBlock());
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

    // True when one of the kinds the entity belongs to is spared this click.
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

    public boolean blocksAttack(HitResult result) {
        if (result instanceof EntityHitResult hit) {
            return blocksHit(hit.getEntity());
        }
        return result instanceof BlockHitResult hit && blocksMining(hit.getBlockPos());
    }
}
