package com.jellypudding.offlineclient.module;

// Modules that clash by default. Switching one on switches off the rest it clashes with.
public enum ExclusivityGroup {

    // Mines on its own for as long as it is on. The server only tracks one breaking block for
    // each player. A module that breaks a single block to guard the player or to open an enemy
    // up stays out of the group and goes first. See BlockMiner.
    MINING,

    // Swaps the held weapon. Two modules fighting over the hotbar lose both swaps.
    WEAPON_SWAP,

    // Takes over a fall. Two of these cancel each other out in mid air.
    FALL_CONTROL,

    // Decides what happens at the lip of a block. One jumps off it and the other stops there.
    EDGE,

    // Decides what happens after a death. One respawns at once and the other stays behind as a ghost.
    RESPAWN,

    // Picks the blocks the chunk mesher keeps. Only one list can be read at a time.
    MESH_FILTER
}
