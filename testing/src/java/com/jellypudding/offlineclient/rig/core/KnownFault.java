package com.jellypudding.offlineclient.rig.core;

// Thrown by a check that failed only in a way the client is already known to fail. The mark
// says what is known. Anything else a check finds is thrown as a plain failure.
public final class KnownFault extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String mark;

    public KnownFault(String seen, String mark) {
        super(seen);
        this.mark = mark;
    }

    public String mark() {
        return mark;
    }
}
