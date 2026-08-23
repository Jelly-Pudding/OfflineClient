package com.jellypudding.offlineclient.module;

// Modules that cannot run at once. Switching one on switches the rest off.
public enum ExclusivityGroup {

    // Drives BlockMiner. The server only tracks one breaking block per player.
    MINING
}
