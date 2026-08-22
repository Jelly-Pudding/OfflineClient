package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.combat.Hitboxes;
import net.minecraft.world.item.component.AttackRange;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(AttackRange.class)
public abstract class AttackRangeMixin {

    @Shadow
    @Final
    private float hitboxMargin;

    /** Widens the reach check to match the grown pick boxes. */
    @Redirect(method = "isInRange(Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/ToDoubleFunction;D)Z",
        at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/item/component/AttackRange;hitboxMargin:F",
            opcode = Opcodes.GETFIELD))
    private float growMargin(AttackRange self) {
        float margin = hitboxMargin;
        if (OfflineClient.INSTANCE.getModuleManager() != null) {
            margin += OfflineClient.INSTANCE.getModuleManager().get(Hitboxes.class).margin();
        }
        return margin;
    }
}
