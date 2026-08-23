package com.jellypudding.offlineclient.mixinterface;

// Sets an OptionInstance value past its normal range clamp. Fullbright needs gamma above 1.0.
public interface ISimpleOption<T> {
    void offlineclient$forceSetValue(T newValue);
}
