package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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

    private final BoolSetting explosives = new BoolSetting("Explosives",
        "Block right clicks on beds and respawn anchors where they detonate.", true);
    private final RegistryListSetting<Block> useBlocks = new RegistryListSetting<>("No use",
        "Blocks that never take a right click. Click to pick them.",
        BuiltInRegistries.BLOCK, List.of());
    private final RegistryListSetting<Block> mineBlocks = new RegistryListSetting<>("No mine",
        "Blocks that never get broken. Click to pick them.",
        BuiltInRegistries.BLOCK, List.of());
    private final RegistryListSetting<EntityType<?>> entities = new RegistryListSetting<>("No hit",
        "Entities that never take a hit. Click to pick them.",
        BuiltInRegistries.ENTITY_TYPE, List.of());
    private final BoolSetting friends = new BoolSetting("Friends",
        "Never hit or click a friend.", true);
    private final BoolSetting babies = new BoolSetting("Babies",
        "Never hit a baby animal.", true);
    private final BoolSetting pets = new BoolSetting("Pets",
        "Never hit a tamed animal.", true);
    private final BoolSetting named = new BoolSetting("Named",
        "Never hit anything wearing a name tag.", false);

    public NoInteract() {
        super("NoInteract", "Stops clicks on the things you choose to protect.", Category.PLAYER);
        addSettings(explosives, useBlocks, mineBlocks, entities, friends, babies, pets, named);
        searchTags("no click", "protect", "anti bed");
    }

    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (!inGame() || mc.hitResult == null) {
            return;
        }
        if (mc.hitResult instanceof BlockHitResult hit && blocksUse(hit.getBlockPos())) {
            event.cancel();
        }
        if (mc.hitResult instanceof EntityHitResult hit && blocksHit(hit.getEntity())) {
            event.cancel();
        }
    }

    private boolean blocksUse(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        Block block = state.getBlock();
        if (useBlocks.contains(block)) {
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

    public boolean blocksMining(BlockPos pos) {
        if (!isEnabled() || !inGame()) {
            return false;
        }
        return mineBlocks.contains(mc.level.getBlockState(pos).getBlock());
    }

    public boolean blocksHit(Entity entity) {
        if (!isEnabled() || entity == null) {
            return false;
        }
        if (entities.contains(entity.getType())) {
            return true;
        }
        if (friends.isOn() && entity instanceof Player player
            && OfflineClient.INSTANCE.getFriendManager().isFriend(player.getGameProfile().name())) {
            return true;
        }
        if (babies.isOn() && entity instanceof LivingEntity living && living.isBaby()) {
            return true;
        }
        // An untamed wolf is ownable but has no owner.
        if (pets.isOn() && entity instanceof OwnableEntity ownable
            && ownable.getOwnerReference() != null) {
            return true;
        }
        return named.isOn() && entity.hasCustomName();
    }

    public boolean blocksAttack(HitResult result) {
        if (result instanceof EntityHitResult hit) {
            return blocksHit(hit.getEntity());
        }
        return result instanceof BlockHitResult hit && blocksMining(hit.getBlockPos());
    }
}
