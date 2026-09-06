package com.jellypudding.offlineclient.module;

// Modules that cannot run at once. Switching one on switches the rest off.
public enum ExclusivityGroup {

    // Drives BlockMiner. The server only tracks one breaking block per player.
    MINING,

    // Swaps the held weapon. Two modules fighting over the hotbar lose both swaps.
    WEAPON_SWAP,

    // Takes over a fall. Two of these cancel each other out in mid air.
    FALL_CONTROL,

    // Decides what happens at the lip of a block. One jumps off it and the other stops there.
    EDGE
}
