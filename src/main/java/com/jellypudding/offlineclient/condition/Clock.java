package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.TextSetting;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

// The clock of your computer reading a time of day. It holds for the whole of that minute.
final class Clock extends Condition {

    private static final int HOURS = 24;
    private static final int MINUTES = 60;

    // Minus one for text that is no time of day.
    private static final int NO_TIME = -1;

    private final TextSetting time;

    Clock(Frame frame) {
        super(frame, "Clock");
        time = add(new TextSetting(frame.name("Clock"),
            "The clock of your computer reads this time of day. Click to type it on the 24 hour clock such as"
                + " 23:30.", "06:00"));
    }

    // The minute of the day the text names. A sign or a space may stand between the hours and
    // the minutes and four digits in a row are read as both. Text with a letter in it is no
    // time. An am or pm would be dropped without a word.
    private int minuteOfDay() {
        String text = time.getValue();
        if (text.chars().anyMatch(Character::isLetter)) {
            return NO_TIME;
        }
        List<String> digits = new ArrayList<>();
        for (String part : text.split("\\D+")) {
            if (!part.isEmpty()) {
                digits.add(part);
            }
        }
        if (digits.size() == 1 && digits.getFirst().length() > 2) {
            String run = digits.removeFirst();
            digits.add(run.substring(0, run.length() - 2));
            digits.add(run.substring(run.length() - 2));
        }
        if (digits.size() != 2 || digits.get(0).length() > 2 || digits.get(1).length() > 2) {
            return NO_TIME;
        }
        int hour = Integer.parseInt(digits.get(0));
        int minute = Integer.parseInt(digits.get(1));
        return hour < HOURS && minute < MINUTES ? hour * MINUTES + minute : NO_TIME;
    }

    @Override
    public boolean holds() {
        LocalTime now = LocalTime.now();
        return now.getHour() * MINUTES + now.getMinute() == minuteOfDay();
    }

    @Override
    public String reason() {
        return "The clock reached " + time.getValue().trim() + ".";
    }

    @Override
    public String problem() {
        return minuteOfDay() == NO_TIME ? "Clock needs a time of day on the 24 hour clock such as 23:30." : null;
    }
}
