package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;

import java.util.function.BooleanSupplier;

// A hand edited or broken layout must never stop a screen opening. What was read before the bad
// value stays.
final class SavedLayout {

    private SavedLayout() {
    }

    static boolean read(String part, BooleanSupplier read) {
        try {
            return read.getAsBoolean();
        } catch (RuntimeException e) {
            OfflineClient.LOG.warn("Ignoring a broken saved layout for {}", part, e);
            return false;
        }
    }
}
