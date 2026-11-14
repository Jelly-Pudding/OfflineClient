package com.jellypudding.offlineclient.rig.core;

// How one case ended. A known fault is a failure the client is already known to have.
public enum Verdict {
    PASSED,
    FAILED,
    KNOWN_FAULT,
    SKIPPED
}
