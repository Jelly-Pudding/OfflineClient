package com.jellypudding.offlineclient.condition;

// Leaving the server. It is the one thing that happens with no world left.
final class Leaving extends Condition {

    // True once a tick has passed in a world since the last leave.
    private boolean inside;

    Leaving(Frame frame) {
        super(frame, "Leaving");
        ticked("You leave the server or lose the connection. Nothing can be sent by then and only a switch of"
            + " modules can follow it.");
    }

    @Override
    public void start() {
        inside = false;
    }

    @Override
    public boolean holds() {
        inside = true;
        return false;
    }

    @Override
    public boolean watchesOutside() {
        return true;
    }

    @Override
    public boolean holdsOutside() {
        boolean left = inside;
        inside = false;
        return left;
    }

    @Override
    public String reason() {
        return "You left the server.";
    }

    @Override
    public boolean isMoment() {
        return true;
    }
}
