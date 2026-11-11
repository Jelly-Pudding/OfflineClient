package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.TextSetting;

import java.util.regex.Pattern;

// The numbers typed into a row such as the coordinates of a place. Spaces or commas may stand
// between them. The text is read again only when it has changed.
final class TypedNumbers {

    // A whole number or one with a decimal point. Nothing else the parser would take counts.
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?");

    private final TextSetting row;
    private final int fewest;
    private final int most;

    private String readFrom;
    private double[] numbers;

    // The row has to hold at least the fewest and at most the most numbers.
    TypedNumbers(TextSetting row, int fewest, int most) {
        this.row = row;
        this.fewest = fewest;
        this.most = most;
    }

    // Null whilst the text is not as many numbers as the row takes.
    double[] get() {
        String text = row.getValue();
        if (!text.equals(readFrom)) {
            readFrom = text;
            numbers = read(text);
        }
        return numbers;
    }

    private double[] read(String text) {
        String[] parts = text.trim().split("[\\s,]+");
        if (parts.length < fewest || parts.length > most) {
            return null;
        }
        double[] read = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (!NUMBER.matcher(parts[i]).matches()) {
                return null;
            }
            read[i] = Double.parseDouble(parts[i]);
        }
        return read;
    }
}
