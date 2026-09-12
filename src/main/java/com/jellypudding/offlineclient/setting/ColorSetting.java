package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.util.ColorUtil;

// A colour stored as a hue from nought to 360 with saturation and brightness.
// Rainbow mode cycles the hue on its own.
public final class ColorSetting extends Setting<Float> {

    // Full saturation and full brightness.
    private static final float FULL = 1f;

    // Saturation for a colour saved without one.
    private static final float DEFAULT_SATURATION = 0.75f;

    private final boolean defaultRainbow;
    private final float defaultSaturation;
    private final float defaultBrightness;

    private boolean rainbow;
    private float saturation;
    private float brightness;

    public ColorSetting(String name, String description, float defaultHue, boolean defaultRainbow) {
        this(name, description, defaultHue, DEFAULT_SATURATION, FULL, defaultRainbow);
    }

    // A default of any shade. Zero saturation gives white and grey.
    public ColorSetting(String name, String description, float defaultHue, float defaultSaturation,
                        float defaultBrightness, boolean defaultRainbow) {
        super(name, description, defaultHue);
        this.defaultRainbow = defaultRainbow;
        this.defaultSaturation = defaultSaturation;
        this.defaultBrightness = defaultBrightness;
        this.rainbow = defaultRainbow;
        this.saturation = defaultSaturation;
        this.brightness = defaultBrightness;
    }

    // Current ARGB colour.
    public int getColor() {
        if (rainbow) {
            return ColorUtil.rainbow(0);
        }
        return ColorUtil.hsv(value, saturation, brightness);
    }

    // A phase offset for gradients and waves.
    public int getColor(int offset) {
        if (rainbow) {
            return ColorUtil.rainbow(offset);
        }
        return ColorUtil.hsv(value + offset * 0.5f, saturation, brightness);
    }

    public float getHue() {
        return value;
    }

    public void setHue(float hue) {
        value = ((hue % 360f) + 360f) % 360f;
    }

    // Zero is grey. One is the pure hue.
    public float getSaturation() {
        return saturation;
    }

    public void setSaturation(float saturation) {
        this.saturation = Math.clamp(saturation, 0f, 1f);
    }

    // Zero is black. One is as bright as the hue goes.
    public float getBrightness() {
        return brightness;
    }

    public void setBrightness(float brightness) {
        this.brightness = Math.clamp(brightness, 0f, 1f);
    }

    public boolean isRainbow() {
        return rainbow;
    }

    public void setRainbow(boolean rainbow) {
        this.rainbow = rainbow;
    }

    @Override
    public void reset() {
        super.reset();
        rainbow = defaultRainbow;
        saturation = defaultSaturation;
        brightness = defaultBrightness;
    }

    @Override
    public JsonElement toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("hue", value);
        o.addProperty("saturation", saturation);
        o.addProperty("brightness", brightness);
        o.addProperty("rainbow", rainbow);
        return o;
    }

    @Override
    public void fromJson(JsonElement json) {
        if (!json.isJsonObject()) {
            return;
        }
        JsonObject o = json.getAsJsonObject();
        if (o.has("hue")) {
            setHue(o.get("hue").getAsFloat());
        }
        setSaturation(o.has("saturation") ? o.get("saturation").getAsFloat() : defaultSaturation);
        setBrightness(o.has("brightness") ? o.get("brightness").getAsFloat() : defaultBrightness);
        if (o.has("rainbow")) {
            rainbow = o.get("rainbow").getAsBoolean();
        }
    }
}
