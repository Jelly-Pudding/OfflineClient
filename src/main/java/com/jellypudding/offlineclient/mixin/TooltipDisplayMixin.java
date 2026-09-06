package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.BetterTooltips;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.item.component.TooltipDisplay;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TooltipDisplay.class)
public abstract class TooltipDisplayMixin {

    @ModifyExpressionValue(method = "shows", at = @At(value = "FIELD",
        target = "Lnet/minecraft/world/item/component/TooltipDisplay;hideTooltip:Z", opcode = Opcodes.GETFIELD))
    private boolean onHideTooltip(boolean hidden) {
        BetterTooltips tooltips = Modules.get(BetterTooltips.class);
        return hidden && (tooltips == null || !tooltips.showsHiddenTooltips());
    }

    @ModifyExpressionValue(method = "shows", at = @At(value = "INVOKE",
        target = "Ljava/util/SequencedSet;contains(Ljava/lang/Object;)Z"))
    private boolean onHiddenLine(boolean hidden) {
        BetterTooltips tooltips = Modules.get(BetterTooltips.class);
        return hidden && (tooltips == null || !tooltips.showsHiddenLines());
    }
}
