package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.combat.Hitboxes;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtilMixin {

    /**
     * The crosshair pick runs a line from where it met the grown box to the
     * middle of the entity and drops the hit when a block is in the way.
     * Near the ground or a wall that line crosses a block whilst the entity
     * itself is in plain view. With Hitboxes on the line runs to the nearest
     * point of the real box instead.
     */
    @WrapOperation(method = "getManyEntityHitResult",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;clipIncludingBorder(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private static BlockHitResult lenientSight(Level level, ClipContext context, Operation<BlockHitResult> original,
                                               @Local(ordinal = 1) Entity entity) {
        BlockHitResult hit = original.call(level, context);
        if (hit.getType() == HitResult.Type.MISS) {
            return hit;
        }
        Hitboxes hitboxes = Modules.active(Hitboxes.class);
        if (hitboxes == null || hitboxes.expansionFor(entity) <= 0) {
            return hit;
        }
        Vec3 from = context.getFrom();
        AABB box = entity.getBoundingBox();
        Vec3 nearest = new Vec3(Math.clamp(from.x, box.minX, box.maxX),
            Math.clamp(from.y, box.minY, box.maxY), Math.clamp(from.z, box.minZ, box.maxZ));
        return original.call(level, new ClipContext(from, nearest, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, entity));
    }
}
