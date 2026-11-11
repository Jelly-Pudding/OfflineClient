package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.mixinterface.IForcedOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Objects;

@Mixin(OptionInstance.class)
public class OptionInstanceMixin<T> implements IForcedOption<T> {

    @Shadow
    T value;

    @Shadow
    @Final
    private OptionInstance.ValueUpdateListener<? super T> onValueUpdate;

    // The game's own set without the range check.
    @Override
    public void offlineclient$force(T newValue) {
        // The options file loads before the game runs and nothing listens yet.
        if (!Minecraft.getInstance().isRunning()) {
            value = newValue;
            return;
        }
        if (!Objects.equals(value, newValue)) {
            value = newValue;
            onValueUpdate.valueChanged(value);
        }
    }
}
