package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.util.ColorUtil;

// A colour stored as a hue from 0 to 360 with saturation and brightness plus an optional rainbow mode.
public final class ColorSetting extends Setting<Float> {

    // Full saturation and full brightness.
    private static final float FULL = 1f;

    // Saturation for a colour saved without one.
    private static final float DEFAULT_SATURATION = 0.75f;

    private final boolean defaultRainbow;

    private boolean rainbow;
    private float saturation = DEFAULT_SATURATION;
    private float brightness = FULL;

    public ColorSetting(String name, String description, float defaultHue, boolean defaultRainbow) {
        super(name, description, defaultHue);
        this.defaultRainbow = defaultRainbow;
        this.rainbow = defaultRainbow;
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
        saturation = DEFAULT_SATURATION;
        brightness = FULL;
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
        setSaturation(o.has("saturation") ? o.get("saturation").getAsFloat() : DEFAULT_SATURATION);
        setBrightness(o.has("brightness") ? o.get("brightness").getAsFloat() : FULL);
        if (o.has("rainbow")) {
            rainbow = o.get("rainbow").getAsBoolean();
        }
    }
}
