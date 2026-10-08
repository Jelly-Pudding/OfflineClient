package com.jellypudding.offlineclient.gui;

// A screen the client draws itself. Modules that move the player whilst a screen is open leave
// these alone unless told otherwise and never take keys from them whilst the player types in them.
public interface ClientScreen {

    // True whilst a key press would type or bind rather than reach the game.
    boolean isTyping();
}
