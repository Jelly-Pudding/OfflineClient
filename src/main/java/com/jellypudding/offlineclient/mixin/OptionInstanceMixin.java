package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.mixinterface.ISimpleOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Objects;

@Mixin(OptionInstance.class)
public class OptionInstanceMixin<T> implements ISimpleOption<T> {

    @Shadow
    T value;

    @Shadow
    @Final
    private OptionInstance.ValueUpdateListener<? super T> onValueUpdate;

    @Override
    public void offlineclient$forceSetValue(T newValue) {
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
