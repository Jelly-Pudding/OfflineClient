package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Tally;

// Met on the pop itself and not for the rest of that life.
final class TotemPops extends Condition {

    private final NumberSetting line;

    private int waiting;
    private int popped;

    TotemPops(Frame frame) {
        super(frame, "Totem pops");
        line = add(new NumberSetting(frame.name("Totem pops"),
            "A totem of yours pops and at least this many have popped in one life.", 1, 1, 10, 1));
    }

    void pop(int count) {
        if (count >= line.getInt()) {
            waiting = count;
        }
    }

    @Override
    public boolean holds() {
        if (waiting == 0) {
            return false;
        }
        popped = waiting;
        waiting = 0;
        return true;
    }

    @Override
    public String reason() {
        return Tally.counted(popped, "totem") + " of yours had popped.";
    }

    @Override
    public boolean isMoment() {
        return true;
    }

    @Override
    public void start() {
        waiting = 0;
    }
}
