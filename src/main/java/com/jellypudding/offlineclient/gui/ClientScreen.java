package com.jellypudding.offlineclient.gui;

// Modules that move the player whilst a screen is open leave these alone and never take keys from
// them whilst the player types.
public interface ClientScreen {

    boolean isTyping();
}
