package com.jellypudding.offlineclient.setting;

import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.List;

// A setting picked from a list in the two column picker screen. Registry
// entries and plain names share the one screen through this.
public interface PickList<T> {

    String getName();

    // Everything on offer. The picker sorts it by display name.
    Collection<T> options();

    // What has been picked in the pick order.
    List<T> chosen();

    boolean isChosen(T entry);

    void add(T entry);

    void addAll(Collection<? extends T> entries);

    void remove(T entry);

    void clear();

    int size();

    String displayName(T entry);

    // A second name to search by and the hover text such as a registry id.
    String idOf(T entry);

    // Empty when the entry has no picture.
    ItemStack icon(T entry);
}
