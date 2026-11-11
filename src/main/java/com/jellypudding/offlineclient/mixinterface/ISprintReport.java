package com.jellypudding.offlineclient.mixinterface;

// What the game last told the server about the local player's sprint. Once a tick it
// compares the sprint with this and sends a start or a stop only when they differ.
public interface ISprintReport {

    boolean offlineclient$toldSprinting();
}
