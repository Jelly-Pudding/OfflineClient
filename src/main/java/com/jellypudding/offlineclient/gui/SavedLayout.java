package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;

import java.util.function.BooleanSupplier;

// Reads one part of the saved layout of the GUI. A hand edited or broken layout must never stop a
// screen opening. A part that cannot be read answers false. What it read before the bad value stays.
final class SavedLayout {

    private SavedLayout() {
    }

    // What the read answers. False when it throws.
    static boolean read(String part, BooleanSupplier read) {
        try {
            return read.getAsBoolean();
        } catch (RuntimeException e) {
            OfflineClient.LOG.warn("Ignoring a broken saved layout for {}", part, e);
            return false;
        }
    }
}
