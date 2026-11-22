package com.jellypudding.offlineclient.util;

import java.io.Serial;

// The unknown is an x or a question mark or an underscore and may only stand in a way that keeps
// the sum a straight line.
public final class ChatSum {

    private static final String UNKNOWN = "xX?_";
    private static final String TIMES = "*×·∙";
    private static final String MINUS = "-−–";

    private static final double LARGEST = 1e12;

    private static final int DECIMALS = 2;

    // Thrown out of a sum that cannot be read. Nothing ever prints it.
    private static final class Unreadable extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        Unreadable() {
            super(null, null, false, false);
        }
    }

    // A number of times the unknown plus a plain number.
    private record Line(double times, double plus) {

        static Line number(double value) {
            return new Line(0, value);
        }

        boolean isNumber() {
            return times == 0;
        }

        Line add(Line other, double sign) {
            return new Line(times + sign * other.times, plus + sign * other.plus);
        }

        // Two lines that both hold the unknown would give its square.
        Line multiply(Line other) {
            if (!isNumber() && !other.isNumber()) {
                throw new Unreadable();
            }
            return isNumber() ? new Line(plus * other.times, plus * other.plus)
                : new Line(times * other.plus, plus * other.plus);
        }

        Line divide(Line other) {
            if (!other.isNumber() || other.plus == 0) {
                throw new Unreadable();
            }
            return new Line(times / other.plus, plus / other.plus);
        }

        Line power(Line other) {
            if (!isNumber() || !other.isNumber()) {
                throw new Unreadable();
            }
            return number(Math.pow(plus, other.plus));
        }
    }

    private final String text;
    private int at;

    private ChatSum(String text) {
        this.text = text;
    }

    // An x only counts where no other letter touches it.
    public static boolean isSumChar(String line, int at) {
        char c = line.charAt(at);
        if (c == 'x' || c == 'X') {
            return !(at > 0 && Character.isLetter(line.charAt(at - 1)))
                && !(at + 1 < line.length() && Character.isLetter(line.charAt(at + 1)));
        }
        return Character.isDigit(c) || c == ' ' || "+/÷^()=.?_".indexOf(c) != -1 || TIMES.indexOf(c) != -1
            || MINUS.indexOf(c) != -1;
    }

    // Null for text that is no sum or has no one answer.
    public static String solve(String sum) {
        try {
            return new ChatSum(sum.strip()).answer();
        } catch (Unreadable | StackOverflowError unreadable) {
            return null;
        }
    }

    private String answer() {
        Line left = sum();
        double value;
        if (onlySpacesLeft()) {
            if (!left.isNumber()) {
                throw new Unreadable();
            }
            value = left.plus;
        } else {
            expect('=');
            // An equals sign with nothing after it asks for the left side.
            boolean open = onlySpacesLeft();
            Line right = open ? new Line(1, 0) : sum();
            double times = left.times - right.times;
            if (!onlySpacesLeft() || times == 0 || open && !left.isNumber()) {
                throw new Unreadable();
            }
            value = (right.plus - left.plus) / times;
        }
        if (!Double.isFinite(value) || Math.abs(value) > LARGEST) {
            throw new Unreadable();
        }
        return ChatUtil.decimal(value, DECIMALS);
    }

    private Line sum() {
        Line total = product();
        while (true) {
            skipSpaces();
            if (take("+")) {
                total = total.add(product(), 1);
            } else if (take(MINUS)) {
                total = total.add(product(), -1);
            } else {
                return total;
            }
        }
    }

    private Line product() {
        Line total = signed();
        while (true) {
            skipSpaces();
            if (take(TIMES) || timesLetter()) {
                total = total.multiply(signed());
            } else if (take("/÷")) {
                total = total.divide(signed());
            } else {
                return total;
            }
        }
    }

    // A minus in front takes in the whole power after it. Minus two squared is minus four.
    private Line signed() {
        skipSpaces();
        if (take(MINUS)) {
            return Line.number(0).add(signed(), -1);
        }
        take("+");
        Line base = operand();
        skipSpaces();
        return take("^") ? base.power(signed()) : base;
    }

    // A number right before the unknown or a bracket multiplies it.
    private Line operand() {
        skipSpaces();
        if (take("(")) {
            Line inside = sum();
            skipSpaces();
            expect(')');
            return inside;
        }
        if (take(UNKNOWN)) {
            return new Line(1, 0);
        }
        Line number = Line.number(number());
        skipSpaces();
        if (at < text.length() && text.charAt(at) == '(') {
            return number.multiply(operand());
        }
        return isUnknownNext() && take(UNKNOWN) ? number.multiply(new Line(1, 0)) : number;
    }

    private double number() {
        int from = at;
        while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
            at++;
        }
        try {
            return Double.parseDouble(text.substring(from, at));
        } catch (NumberFormatException noNumber) {
            throw new Unreadable();
        }
    }

    // An x between two things to multiply is the times sign. Anywhere else it is the unknown.
    private boolean timesLetter() {
        if (at >= text.length() || (text.charAt(at) != 'x' && text.charAt(at) != 'X') || !startsOperand(at + 1)) {
            return false;
        }
        at++;
        return true;
    }

    private boolean isUnknownNext() {
        if (at >= text.length() || UNKNOWN.indexOf(text.charAt(at)) == -1) {
            return false;
        }
        char c = text.charAt(at);
        return (c != 'x' && c != 'X') || !startsOperand(at + 1);
    }

    private boolean startsOperand(int from) {
        int next = from;
        while (next < text.length() && text.charAt(next) == ' ') {
            next++;
        }
        if (next >= text.length()) {
            return false;
        }
        char c = text.charAt(next);
        return Character.isDigit(c) || c == '(' || c == '.' || UNKNOWN.indexOf(c) != -1;
    }

    private boolean onlySpacesLeft() {
        skipSpaces();
        return at == text.length();
    }

    private void skipSpaces() {
        while (at < text.length() && text.charAt(at) == ' ') {
            at++;
        }
    }

    private boolean take(String anyOf) {
        if (at < text.length() && anyOf.indexOf(text.charAt(at)) != -1) {
            at++;
            return true;
        }
        return false;
    }

    private void expect(char wanted) {
        if (at >= text.length() || text.charAt(at) != wanted) {
            throw new Unreadable();
        }
        at++;
    }
}
