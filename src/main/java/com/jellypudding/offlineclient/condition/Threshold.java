package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.NumberSetting;

import java.util.function.DoubleSupplier;

final class Threshold extends Condition {

    enum Side { AT_OR_BELOW, BELOW, AT_OR_ABOVE, ABOVE }

    private final NumberSetting line;
    private final Side side;
    private final DoubleSupplier reading;
    private final String met;
    private Runnable restart = () -> {
    };

    // The sentence for what was met takes the line where it says %s.
    Threshold(Frame frame, String name, NumberSetting line, Side side, DoubleSupplier reading, String met) {
        super(frame, name);
        this.line = add(line);
        this.side = side;
        this.reading = reading;
        this.met = met;
    }

    Threshold restarting(Runnable restart) {
        this.restart = restart;
        return this;
    }

    @Override
    public void start() {
        restart.run();
    }

    @Override
    public boolean holds() {
        double now = reading.getAsDouble();
        double limit = line.getValue();
        return switch (side) {
            case AT_OR_BELOW -> now <= limit;
            case BELOW -> now < limit;
            case AT_OR_ABOVE -> now >= limit;
            case ABOVE -> now > limit;
        };
    }

    @Override
    public String reason() {
        return String.format(met, line.getValueString());
    }
}
