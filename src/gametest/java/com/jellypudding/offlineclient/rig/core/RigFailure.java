package com.jellypudding.offlineclient.rig.core;

// Thrown by a check that did not hold. The message says what was seen.
public final class RigFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RigFailure(String seen) {
        super(seen);
    }

    public RigFailure(String seen, Throwable cause) {
        super(seen, cause);
    }
}
