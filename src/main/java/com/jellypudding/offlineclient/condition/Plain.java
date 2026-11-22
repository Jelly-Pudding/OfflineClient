package com.jellypudding.offlineclient.condition;

import java.util.function.Supplier;

// Its test hands back what was met as a sentence and null on a tick it is not met.
final class Plain extends Condition {

    private final boolean moment;
    private final Supplier<String> test;
    private final Runnable forget;
    private String reason = "";

    private Plain(Frame frame, String name, String description, boolean moment, Supplier<String> test,
                  Runnable forget) {
        super(frame, name);
        this.moment = moment;
        this.test = test;
        this.forget = forget;
        ticked(description);
    }

    static Plain state(Frame frame, String name, String description, Supplier<String> test) {
        return new Plain(frame, name, description, false, test, () -> {
        });
    }

    static Plain moment(Frame frame, String name, String description, Supplier<String> test, Runnable forget) {
        return new Plain(frame, name, description, true, test, forget);
    }

    @Override
    public boolean holds() {
        String met = test.get();
        if (met == null) {
            return false;
        }
        reason = met;
        return true;
    }

    @Override
    public String reason() {
        return reason;
    }

    @Override
    public boolean isMoment() {
        return moment;
    }

    @Override
    public void start() {
        forget.run();
    }
}
