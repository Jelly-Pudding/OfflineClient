package com.jellypudding.offlineclient.mixinterface;

import net.minecraft.client.OptionInstance;

// Sets an OptionInstance value past its normal range. Vanilla puts an out of range
// value back to its default.
public interface ISimpleOption<T> {
    void offlineclient$forceSetValue(T newValue);

    @SuppressWarnings("unchecked")
    static <T> void force(OptionInstance<T> option, T value) {
        ((ISimpleOption<T>) (Object) option).offlineclient$forceSetValue(value);
    }
}
