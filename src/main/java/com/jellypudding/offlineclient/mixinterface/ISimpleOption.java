package com.jellypudding.offlineclient.mixinterface;

/**
 * Lets us set an OptionInstance's value without its normal range clamp
 * (e.g. gamma above 1.0 for Fullbright).
 */
public interface ISimpleOption<T> {
    void offlineclient$forceSetValue(T newValue);
}
