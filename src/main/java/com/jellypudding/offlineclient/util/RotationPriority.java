package com.jellypudding.offlineclient.util;

// Declared from the least important to the most important and compared in that order.
public enum RotationPriority {

    IDLE,

    MINE,

    PLACE,

    ATTACK,

    AURA;

    public boolean beats(RotationPriority other) {
        return ordinal() > other.ordinal();
    }
}
