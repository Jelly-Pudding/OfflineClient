package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

// A small square of cells each switched on or off. The ClickGUI draws it as a grid to
// paint on. A command types it one row to a word with a hash for on and a dot for off.
public final class GridSetting extends Setting<GridSetting.Pattern> {

    // Odd sizes keep one cell in the middle.
    public static final int MIN_SIZE = 3;
    // Every cell of a square this wide can still be reached from one spot.
    public static final int MAX_SIZE = 9;
    private static final int SIZE_STEP = 2;

    private static final char ON = '#';
    private static final char OFF = '.';

    // A cell counted from the top left corner.
    public record Cell(int column, int row) {
    }

    // One painted square that never changes. The whole widest square is kept whatever size
    // is in use. A cell outside the size returns once the size grows again.
    public static final class Pattern {

        private final int size;
        // The widest square row by row from the top. Never written once a pattern holds it.
        private final BitSet field;

        private Pattern(int size, BitSet field) {
            this.size = size;
            this.field = field;
        }

        public int size() {
            return size;
        }

        // A smaller square sits in the middle of the widest one.
        private static int index(int size, int column, int row) {
            int margin = (MAX_SIZE - size) / 2;
            return (row + margin) * MAX_SIZE + column + margin;
        }

        public boolean isOn(int column, int row) {
            return field.get(index(size, column, row));
        }

        private Pattern with(int column, int row, boolean on) {
            BitSet next = (BitSet) field.clone();
            next.set(index(size, column, row), on);
            return new Pattern(size, next);
        }

        private Pattern sized(int newSize) {
            return new Pattern(newSize, field);
        }

        // The cells switched on row by row from the top.
        public List<Cell> cellsOn() {
            List<Cell> on = new ArrayList<>();
            for (int row = 0; row < size; row++) {
                for (int column = 0; column < size; column++) {
                    if (isOn(column, row)) {
                        on.add(new Cell(column, row));
                    }
                }
            }
            return on;
        }

        private String row(int row) {
            StringBuilder out = new StringBuilder(size);
            for (int column = 0; column < size; column++) {
                out.append(isOn(column, row) ? ON : OFF);
            }
            return out.toString();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Pattern pattern && pattern.size == size && pattern.field.equals(field);
        }

        @Override
        public int hashCode() {
            return 31 * size + field.hashCode();
        }
    }

    // The default comes as rows of hashes and dots such as ".#." "###" ".#.".
    public GridSetting(String name, String description, String... rows) {
        super(name, description, parse(Arrays.asList(rows)));
        if (defaultValue == null) {
            throw new IllegalArgumentException(name + " needs an odd square of rows");
        }
    }

    // Null unless the rows make an odd square from the smallest size to the largest.
    private static Pattern parse(List<String> rows) {
        int size = rows.size();
        if (size < MIN_SIZE || size > MAX_SIZE || size % 2 == 0) {
            return null;
        }
        BitSet field = new BitSet(MAX_SIZE * MAX_SIZE);
        for (int row = 0; row < size; row++) {
            String cells = rows.get(row);
            if (cells.length() != size) {
                return null;
            }
            for (int column = 0; column < size; column++) {
                char cell = cells.charAt(column);
                if (cell != ON && cell != OFF) {
                    return null;
                }
                field.set(Pattern.index(size, column, row), cell == ON);
            }
        }
        return new Pattern(size, field);
    }

    public int size() {
        return value.size();
    }

    public boolean isOn(int column, int row) {
        return value.isOn(column, row);
    }

    public void set(int column, int row, boolean on) {
        value = value.with(column, row, on);
    }

    // One size up or down. A step past either end wraps round to the other.
    public void cycleSize(boolean up) {
        int next = value.size() + (up ? SIZE_STEP : -SIZE_STEP);
        if (next > MAX_SIZE) {
            next = MIN_SIZE;
        } else if (next < MIN_SIZE) {
            next = MAX_SIZE;
        }
        value = value.sized(next);
    }

    public List<Cell> cellsOn() {
        return value.cellsOn();
    }

    // One word for each row. False when the words do not make an odd square that fits.
    public boolean setFromWords(String text) {
        Pattern parsed = parse(Arrays.asList(text.trim().split("\\s+")));
        if (parsed == null) {
            return false;
        }
        value = parsed;
        return true;
    }

    @Override
    public String getValueString() {
        List<String> rows = new ArrayList<>(value.size());
        for (int row = 0; row < value.size(); row++) {
            rows.add(value.row(row));
        }
        return String.join(" ", rows);
    }

    // The size in use and every row of the widest square. Cells hidden by a smaller size survive a restart.
    @Override
    public JsonElement toJson() {
        Pattern whole = value.sized(MAX_SIZE);
        JsonArray rows = new JsonArray();
        for (int row = 0; row < MAX_SIZE; row++) {
            rows.add(whole.row(row));
        }
        JsonObject json = new JsonObject();
        json.addProperty("size", value.size());
        json.add("rows", rows);
        return json;
    }

    @Override
    public void fromJson(JsonElement json) {
        if (!json.isJsonObject()) {
            return;
        }
        JsonObject object = json.getAsJsonObject();
        if (!(object.get("size") instanceof JsonPrimitive size) || !size.isNumber()
            || !(object.get("rows") instanceof JsonArray rows)) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (JsonElement row : rows) {
            if (!(row instanceof JsonPrimitive line) || !line.isString()) {
                return;
            }
            lines.add(line.getAsString());
        }
        Pattern whole = parse(lines);
        int wanted = size.getAsInt();
        if (whole != null && whole.size() == MAX_SIZE && wanted >= MIN_SIZE && wanted <= MAX_SIZE
            && wanted % 2 == 1) {
            value = whole.sized(wanted);
        }
    }
}
