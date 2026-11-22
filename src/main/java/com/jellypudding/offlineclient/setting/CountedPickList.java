package com.jellypudding.offlineclient.setting;

public interface CountedPickList<T> extends PickList<T> {

    int count(T entry);

    void setCount(T entry, int count);
}
