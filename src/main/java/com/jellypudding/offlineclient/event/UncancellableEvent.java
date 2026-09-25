package com.jellypudding.offlineclient.event;

// An event nothing may stop. Several are one shared instance where a cancel would
// stick for the rest of the session.
public abstract class UncancellableEvent extends Event {

    @Override
    public final void cancel() {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " cannot be cancelled");
    }
}
