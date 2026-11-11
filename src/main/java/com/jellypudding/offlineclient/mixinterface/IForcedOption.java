package com.jellypudding.offlineclient.mixinterface;

import net.minecraft.client.OptionInstance;

// Sets an OptionInstance value past its normal range. Vanilla puts an out of range
// value back to its default.
public interface IForcedOption<T> {

    void offlineclient$force(T value);

    @SuppressWarnings("unchecked")
    static <T> void force(OptionInstance<T> option, T value) {
        ((IForcedOption<T>) (Object) option).offlineclient$force(value);
    }
}
