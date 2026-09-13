package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

public final class NoInteract extends Module {

    public enum ListMode { WHITELIST, BLACKLIST }

    public enum Hand { NONE, MAIN_HAND, OFF_HAND, BOTH }

    // Which clicks a protected kind of entity is spared.
    public enum Protect { NONE, HIT, USE, BOTH }

    private final BoolSetting explosives = new BoolSetting("Explosives",
        "Block right clicks on beds and respawn anchors where they detonate.", true);
    private final RegistryListSetting<Block> useBlocks = new RegistryListSetting<>("No use",
        "Blocks that never take a right click. Click to pick them.",
        BuiltInRegistries.BLOCK, List.of());
    private final EnumSetting<ListMode> useBlocksMode = listMode("No use list mode",
        "Only the listed blocks take a right click.",
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
    private final EnumSetting<ListMode> mineBlocksMode = listMode("No mine list mode",
        "Only the listed blocks get broken.",
        "Every block except the listed ones gets broken.");
    private final RegistryListSetting<EntityType<?>> hitEntities = new RegistryListSetting<>("No hit",
        "Entities that never take a hit. Click to pick them.",
        BuiltInRegistries.ENTITY_TYPE, List.of());
    private final EnumSetting<ListMode> hitEntitiesMode = listMode("No hit list mode",
        "Only the listed entities take a hit.",
        "Every entity except the listed ones takes a hit.");
    private final RegistryListSetting<EntityType<?>> useEntities = new RegistryListSetting<>(
        "No entity use", "Entities that never take a right click. Click to pick them.",
        BuiltInRegistries.ENTITY_TYPE, List.of());
    private final EnumSetting<ListMode> useEntitiesMode = listMode("No entity use list mode",
        "Only the listed entities take a right click.",
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

    public NoInteract() {
        super("NoInteract", "Stops clicks on the things you choose to protect.", Category.PLAYER);
        addSettings(explosives, useBlocks, useBlocksMode, useHand, mineBlocks, mineBlocksMode,
            hitEntities, hitEntitiesMode, useEntities, useEntitiesMode, useEntityHand,
            friends, babies, pets, named);
        searchTags("no click", "protect", "anti bed");
    }

    private static EnumSetting<ListMode> listMode(String name, String whitelist, String blacklist) {
        return new EnumSetting<>(name, "What the list means.", ListMode.BLACKLIST)
            .describe(ListMode.WHITELIST, whitelist)
            .describe(ListMode.BLACKLIST, blacklist);
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
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && blocksUse(hit.getBlockPos())) {
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
        // Whether a bed or an anchor goes off is an environment attribute of the position.
        if (block instanceof BedBlock) {
            return mc.level.environmentAttributes()
                .getValue(EnvironmentAttributes.BED_RULE, pos).explodes();
        }
        return block instanceof RespawnAnchorBlock && anchorDetonates(state, pos);
    }

    // True for an anchor that blows up. A charge or a spawn set means false.
    private boolean anchorDetonates(BlockState state, BlockPos pos) {
        if (mc.level.environmentAttributes()
            .getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, pos)) {
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

    // Called from the game mode mixin for each hand that tries a block.
    public boolean blocksUseWith(InteractionHand hand) {
        return isEnabled() && covers(useHand.getValue(), hand);
    }

    // Called from the game mode mixin for each hand that tries an entity.
    public boolean blocksEntityUseWith(InteractionHand hand) {
        return isEnabled() && covers(useEntityHand.getValue(), hand);
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
        return list.contains(entry) == mode.is(ListMode.BLACKLIST);
    }

    public boolean blocksAttack(HitResult result) {
        if (result instanceof EntityHitResult hit) {
            return blocksHit(hit.getEntity());
        }
        return result instanceof BlockHitResult hit && blocksMining(hit.getBlockPos());
    }
}
