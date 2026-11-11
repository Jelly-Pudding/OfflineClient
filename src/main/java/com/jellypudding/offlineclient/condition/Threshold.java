package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.NumberSetting;

import java.util.function.DoubleSupplier;

// A number the player sets held against a reading such as hunger at or below six points.
final class Threshold extends Condition {

    // Which side of the line the reading has to be on.
    enum Side { AT_OR_BELOW, BELOW, AT_OR_ABOVE, ABOVE }

    private final NumberSetting line;
    private final Side side;
    private final DoubleSupplier reading;
    private final String met;
    private Runnable restart = () -> {
    };

    // The line is the one row and carries the name of the condition. The sentence for what
    // was met takes the line where it says %s.
    Threshold(Frame frame, String name, NumberSetting line, Side side, DoubleSupplier reading, String met) {
        super(frame, name);
        this.line = add(line);
        this.side = side;
        this.reading = reading;
        this.met = met;
    }

    // For a reading that counts from the moment the watching begins such as a stopwatch.
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
