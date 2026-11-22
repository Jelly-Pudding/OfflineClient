package com.jellypudding.offlineclient.hud;

import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.LiveText;

import java.util.List;
import java.util.OptionalDouble;

public final class ShowRule {

    public enum When { ALWAYS, BELOW, ABOVE, IS, IS_NOT }

    private static final String OPEN = "{";
    private static final String CLOSE = "}";
    private static final String PERCENT = "%";

    // A rule that cannot work never shows the piece.
    public record Verdict(boolean shows, String problem) {

        private static final Verdict SHOWN = new Verdict(true, null);
        private static final Verdict HIDDEN = new Verdict(false, null);

        private static Verdict because(String problem) {
            return new Verdict(false, problem);
        }
    }

    private final EnumSetting<When> when;
    private final TextSetting word;
    private final TextSetting value;

    public ShowRule(String name) {
        when = new EnumSetting<>(name + " show when", "When it shows.", When.ALWAYS)
            .describe(When.ALWAYS, "It always shows.")
            .describe(When.BELOW, "It shows whilst the watched word is a number below the value.")
            .describe(When.ABOVE, "It shows whilst the watched word is a number above the value.")
            .describe(When.IS, "It shows whilst the watched word reads the same as the value.")
            .describe(When.IS_NOT, "It shows whilst the watched word reads anything but the value.");
        word = new TextSetting(name + " show word",
            "The live word the rule watches such as health or count ender_pearl.", "health")
            .under(when, When.BELOW, When.ABOVE, When.IS, When.IS_NOT);
        value = new TextSetting(name + " show value",
            "What the watched word is held against such as 6 or on.", "6")
            .under(when, When.BELOW, When.ABOVE, When.IS, When.IS_NOT);
    }

    public List<Setting<?>> settings() {
        return List.of(when, word, value);
    }

    public Verdict judge() {
        if (when.is(When.ALWAYS)) {
            return Verdict.SHOWN;
        }
        String watched = unbraced(word.getValue());
        if (watched.isEmpty()) {
            return Verdict.because("Type a live word for the rule to watch.");
        }
        LiveText.Reading reading = LiveText.read(watched);
        if (reading == null) {
            return Verdict.because("There is no live word called " + watched + ".");
        }
        if (reading.unreadable()) {
            return Verdict.because("The live word " + watched + " cannot be read.");
        }
        boolean numbers = when.isAny(When.BELOW, When.ABOVE);
        if (numbers && numberIn(value.getValue()).isEmpty()) {
            return Verdict.because(when.getValueString() + " needs a number to hold " + watched + " against.");
        }
        if (numbers && !reading.hasNumber() && !reading.text().isBlank()) {
            return Verdict.because(watched + " is not a number. Pick Is or Is not for it.");
        }
        return holds(when.getValue(), reading.text(), reading.number(), value.getValue()) ? Verdict.SHOWN
            : Verdict.HIDDEN;
    }

    // A word with no number behind it is neither below nor above anything. Two readings are the
    // same when they read alike or come to the same number.
    public static boolean holds(When when, String shown, double number, String wanted) {
        OptionalDouble against = numberIn(wanted);
        boolean comparable = !Double.isNaN(number) && against.isPresent();
        return switch (when) {
            case ALWAYS -> true;
            case BELOW -> comparable && number < against.getAsDouble();
            case ABOVE -> comparable && number > against.getAsDouble();
            case IS -> same(shown, comparable, number, wanted, against);
            case IS_NOT -> !same(shown, comparable, number, wanted, against);
        };
    }

    private static boolean same(String shown, boolean comparable, double number, String wanted,
                                OptionalDouble against) {
        return shown.trim().equalsIgnoreCase(wanted.trim()) || comparable && number == against.getAsDouble();
    }

    private static String unbraced(String typed) {
        String word = typed.trim();
        if (word.startsWith(OPEN)) {
            word = word.substring(OPEN.length());
        }
        if (word.endsWith(CLOSE)) {
            word = word.substring(0, word.length() - CLOSE.length());
        }
        return word.trim();
    }

    // A share can be typed with its percent sign the way the piece shows it.
    private static OptionalDouble numberIn(String text) {
        String typed = text.trim();
        if (typed.endsWith(PERCENT)) {
            typed = typed.substring(0, typed.length() - PERCENT.length()).trim();
        }
        try {
            double number = Double.parseDouble(typed);
            return Double.isNaN(number) ? OptionalDouble.empty() : OptionalDouble.of(number);
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }
}
