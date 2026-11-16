package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;

// A number with a slider in the GUI. The slider covers the useful range
// but typed values can go past it up to any hard min and max.
public final class NumberSetting extends Setting<Double> {

    // Values closer than this count as the same. A size stepped in the HUD editor lands a
    // hair away from the round number it means.
    private static final double SAME = 1e-9;
    // A typed value is written to at least this many places whatever the step.
    private static final int TYPED_PLACES = 4;

    private final double sliderMin;
    private final double sliderMax;
    private final double step;
    private final int decimals;
    private final String suffix;
    private double hardMin;
    private double hardMax = Double.POSITIVE_INFINITY;

    // Set whilst a slider drag is running.
    private double activeSliderMax = -1;

    // The highest value typed this session. The slider keeps covering it after the value drops.
    private double sessionMax;

    public NumberSetting(String name, String description, double defaultValue,
                         double sliderMin, double sliderMax, double step) {
        this(name, description, defaultValue, sliderMin, sliderMax, step, "");
    }

    public NumberSetting(String name, String description, double defaultValue,
                         double sliderMin, double sliderMax, double step, String suffix) {
        super(name, description, defaultValue);
        this.sliderMin = sliderMin;
        this.sliderMax = sliderMax;
        this.step = step;
        this.suffix = suffix;
        this.decimals = decimalsOf(step);
        this.hardMin = sliderMin;
    }

    public NumberSetting min(double hardMin) {
        this.hardMin = hardMin;
        return this;
    }

    public NumberSetting max(double hardMax) {
        this.hardMax = hardMax;
        return this;
    }

    private static int decimalsOf(double step) {
        String s = BigDecimal.valueOf(step).stripTrailingZeros().toPlainString();
        int dot = s.indexOf('.');
        return dot == -1 ? 0 : s.length() - dot - 1;
    }

    public double getSliderMin() {
        return sliderMin;
    }

    public double getSliderMax() {
        return sliderMax;
    }

    public double getHardMin() {
        return hardMin;
    }

    public double getHardMax() {
        return hardMax;
    }

    public int getInt() {
        return (int) Math.round(value);
    }

    public float getFloat() {
        return value.floatValue();
    }

    // Typed values keep their exact number. Only the hard limits apply. A value that is no
    // number changes nothing. A save edited by hand can hold one.
    @Override
    public void setValue(Double newValue) {
        if (!Double.isFinite(newValue)) {
            return;
        }
        value = Math.clamp(newValue, hardMin, hardMax);
        if (value > sliderMax) {
            sessionMax = Math.max(sessionMax, value);
        }
    }

    // Moves by whole slider steps and rounds to them. The hard limits still hold.
    public void nudge(int steps) {
        double moved = BigDecimal.valueOf(value + steps * step).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
        setValue(moved);
    }

    // A typed value above the normal range stretches the slider.
    private double sliderTop() {
        if (activeSliderMax > 0) {
            return activeSliderMax;
        }
        return Math.max(sliderMax, Math.max(sessionMax, value));
    }

    // Locks the slider range for the length of one drag.
    public void beginSlider() {
        activeSliderMax = sliderTop();
    }

    public void endSlider() {
        activeSliderMax = -1;
    }

    // Snaps to the step and stays inside the slider range.
    public void setFromSlider(double fraction) {
        double top = sliderTop();
        double raw = sliderMin + (top - sliderMin) * Math.clamp(fraction, 0, 1);
        double stepped = Math.round(raw / step) * step;
        double clamped = Math.clamp(stepped, sliderMin, top);
        double rounded = BigDecimal.valueOf(clamped).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
        // The hard limits still win over the slider range.
        value = Math.clamp(rounded, hardMin, hardMax);
    }

    public double getSliderFraction() {
        return Math.clamp((value - sliderMin) / (sliderTop() - sliderMin), 0, 1);
    }

    @Override
    public boolean isDefault() {
        return Math.abs(value - defaultValue) < SAME;
    }

    @Override
    public String getValueString() {
        return format(value);
    }

    @Override
    public String defaultText() {
        return format(defaultValue);
    }

    // A number the way this setting writes its value but without the suffix.
    public String write(double number) {
        return ChatUtil.decimal(number, Math.max(decimals, TYPED_PLACES));
    }

    private String format(double number) {
        return write(number) + suffix;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
            setValue(json.getAsDouble());
        }
    }
}
