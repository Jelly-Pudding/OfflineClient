package com.jellypudding.offlineclient.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleSupplier;

// Works out a typed sum. It knows plus and minus and times and divide and the remainder and
// powers and brackets and a handful of named functions. Times and divide bind tighter than
// plus and minus and a power tighter still.
public final class Calculator {

    // What a sum came to. The problem is null for one that could be worked out.
    public record Answer(double value, String problem) {
    }

    // A sum read from a file may nest without end. Each bracket and each power of a power costs a call.
    private static final int DEEPEST = 64;

    // How much of the sum a complaint quotes.
    private static final int QUOTED = 16;

    private static final String BY_NOUGHT = "Nothing can be divided by nought.";

    private static final List<String> FUNCTIONS =
        List.of("sqrt", "abs", "floor", "ceil", "round", "min", "max");

    // Thrown to leave a sum that cannot be read. It never leaves this class.
    private static final class Unreadable extends RuntimeException {

        private Unreadable(String problem) {
            super(problem, null, false, false);
        }
    }

    private final String text;
    private int at;
    private int depth;

    private Calculator(String text) {
        this.text = text;
    }

    public static Answer work(String sum) {
        Calculator calculator = new Calculator(sum.toLowerCase(Locale.ROOT));
        try {
            double value = calculator.sum();
            calculator.skipSpaces();
            if (calculator.at < calculator.text.length()) {
                throw calculator.lost();
            }
            return new Answer(value, null);
        } catch (Unreadable e) {
            return new Answer(0, e.getMessage());
        }
    }

    private double sum() {
        double value = product();
        while (true) {
            if (take('+')) {
                value = finite(value + product());
            } else if (take('-')) {
                value = finite(value - product());
            } else {
                return value;
            }
        }
    }

    private double product() {
        double value = signed();
        while (true) {
            if (take('*')) {
                value = finite(value * signed());
            } else if (take('/')) {
                value = finite(value / divisor());
            } else if (take('%')) {
                value %= divisor();
            } else {
                return value;
            }
        }
    }

    private double divisor() {
        double value = signed();
        if (value == 0) {
            throw new Unreadable(BY_NOUGHT);
        }
        return value;
    }

    // A sign binds looser than a power. Minus two squared is minus four.
    private double signed() {
        boolean negative = false;
        while (true) {
            if (take('-')) {
                negative = !negative;
            } else if (!take('+')) {
                break;
            }
        }
        double value = power();
        return negative ? -value : value;
    }

    // Powers read from the right. Two to the three to the two is two to the nine.
    private double power() {
        double base = single();
        if (!take('^')) {
            return base;
        }
        double exponent = deeper(this::signed);
        if (base == 0 && exponent < 0) {
            throw new Unreadable(BY_NOUGHT);
        }
        double value = Math.pow(base, exponent);
        if (Double.isNaN(value)) {
            throw new Unreadable("A number below nought cannot be raised to a fraction.");
        }
        return finite(value);
    }

    private double single() {
        skipSpaces();
        if (take('(')) {
            double value = deeper(this::sum);
            if (!take(')')) {
                throw new Unreadable("A bracket is never closed.");
            }
            return value;
        }
        if (at < text.length() && Character.isLetter(text.charAt(at))) {
            return named();
        }
        return number();
    }

    private double deeper(DoubleSupplier part) {
        if (++depth > DEEPEST) {
            throw new Unreadable("That sum goes too deep to work out.");
        }
        double value = part.getAsDouble();
        depth--;
        return value;
    }

    private double number() {
        int start = at;
        while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
            at++;
        }
        if (at == start) {
            throw lost();
        }
        try {
            return finite(Double.parseDouble(text.substring(start, at)));
        } catch (NumberFormatException e) {
            throw new Unreadable(quoted(text.substring(start, at)) + " is not a number.");
        }
    }

    private double named() {
        int start = at;
        while (at < text.length() && Character.isLetter(text.charAt(at))) {
            at++;
        }
        String name = text.substring(start, at);
        if (name.equals("pi")) {
            return Math.PI;
        }
        if (!FUNCTIONS.contains(name)) {
            throw new Unreadable("There is nothing called " + quoted(name) + ". It knows pi and "
                + String.join(" ", FUNCTIONS) + ".");
        }
        if (!take('(')) {
            throw new Unreadable(name + " wants a bracket after it.");
        }
        List<Double> inputs = new ArrayList<>();
        do {
            inputs.add(deeper(this::sum));
        } while (take(','));
        if (!take(')')) {
            throw new Unreadable("A bracket is never closed.");
        }
        return apply(name, inputs);
    }

    private static double apply(String name, List<Double> inputs) {
        boolean takesTwo = name.equals("min") || name.equals("max");
        if (inputs.size() != (takesTwo ? 2 : 1)) {
            throw new Unreadable(name + " takes " + (takesTwo ? "two numbers." : "one number."));
        }
        double first = inputs.getFirst();
        return switch (name) {
            case "sqrt" -> rootOf(first);
            case "abs" -> Math.abs(first);
            case "floor" -> Math.floor(first);
            case "ceil" -> Math.ceil(first);
            case "round" -> Math.floor(first + 0.5);
            case "min" -> Math.min(first, inputs.getLast());
            default -> Math.max(first, inputs.getLast());
        };
    }

    private static double rootOf(double value) {
        if (value < 0) {
            throw new Unreadable("A number below nought has no square root.");
        }
        return Math.sqrt(value);
    }

    // A sum that runs past the largest number there is stops at the step that did it.
    private static double finite(double value) {
        if (Double.isInfinite(value)) {
            throw new Unreadable("That is too large to work out.");
        }
        return value;
    }

    private boolean take(char symbol) {
        skipSpaces();
        if (at < text.length() && text.charAt(at) == symbol) {
            at++;
            return true;
        }
        return false;
    }

    private void skipSpaces() {
        while (at < text.length() && text.charAt(at) == ' ') {
            at++;
        }
    }

    // Names the spot where the sum stopped making sense.
    private Unreadable lost() {
        return new Unreadable(at >= text.length() ? "The sum stops too soon."
            : "The sum cannot be read from " + quoted(text.substring(at)) + ".");
    }

    private static String quoted(String part) {
        return part.length() > QUOTED ? part.substring(0, QUOTED) : part;
    }
}
