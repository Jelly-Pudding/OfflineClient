package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.combat.Hitboxes;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.item.component.AttackRange;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(AttackRange.class)
public abstract class AttackRangeMixin {

    @Shadow
    @Final
    private float hitboxMargin;

    @Redirect(method = "isInRange(Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/ToDoubleFunction;D)Z",
        at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/item/component/AttackRange;hitboxMargin:F",
            opcode = Opcodes.GETFIELD))
    private float growMargin(AttackRange self) {
        Hitboxes hitboxes = Modules.get(Hitboxes.class);
        return hitboxes == null ? hitboxMargin : hitboxMargin + hitboxes.margin();
    }

    // The crosshair pick inflates every entity box using this accessor.
    // Growing it here lands hits since the server allows three blocks of slack.
    @ModifyReturnValue(method = "hitboxMargin()F", at = @At("RETURN"))
    private float onHitboxMargin(float original) {
        Hitboxes hitboxes = Modules.get(Hitboxes.class);
        return hitboxes == null ? original : original + hitboxes.margin();
    }
}
