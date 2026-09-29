package com.jellypudding.offlineclient.setting;

// A picked list that keeps a number beside every entry. The picker shows it on each chosen row
// and a click on it types a new one.
public interface CountedPickList<T> extends PickList<T> {

    int count(T entry);

    void setCount(T entry, int count);
}
