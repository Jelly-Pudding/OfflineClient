package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.combat.Hitboxes;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.item.component.AttackRange;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AttackRange.class)
public abstract class AttackRangeMixin {

    @ModifyExpressionValue(method = "isInRange(Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/ToDoubleFunction;D)Z",
        at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/item/component/AttackRange;hitboxMargin:F",
            opcode = Opcodes.GETFIELD))
    private float growMargin(float margin) {
        return offlineclient$grown(margin);
    }

    // The crosshair pick inflates every entity box using this accessor.
    // Growing it here lands hits since the server allows three blocks of slack.
    @ModifyReturnValue(method = "hitboxMargin()F", at = @At("RETURN"))
    private float onHitboxMargin(float original) {
        return offlineclient$grown(original);
    }

    @Unique
    private static float offlineclient$grown(float margin) {
        Hitboxes hitboxes = Modules.get(Hitboxes.class);
        return hitboxes == null ? margin : margin + hitboxes.margin();
    }
}
