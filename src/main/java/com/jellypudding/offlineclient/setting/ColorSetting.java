package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.util.ColorUtil;

// A colour stored as a hue from 0 to 360 plus an optional rainbow mode.
public final class ColorSetting extends Setting<Float> {

    private boolean rainbow;

    public ColorSetting(String name, String description, float defaultHue, boolean defaultRainbow) {
        super(name, description, defaultHue);
        this.rainbow = defaultRainbow;
    }

    // Current ARGB colour.
    public int getColor() {
        if (rainbow) {
            return ColorUtil.rainbow(0);
        }
        return ColorUtil.hsv(value, 0.75f, 1f);
    }

    // A phase offset for gradients and waves.
    public int getColor(int offset) {
        if (rainbow) {
            return ColorUtil.rainbow(offset);
        }
        return ColorUtil.hsv(value + offset * 0.5f, 0.75f, 1f);
    }

    public float getHue() {
        return value;
    }

    public void setHue(float hue) {
        value = ((hue % 360f) + 360f) % 360f;
    }

    public boolean isRainbow() {
        return rainbow;
    }

    public void setRainbow(boolean rainbow) {
        this.rainbow = rainbow;
    }

    @Override
    public JsonElement toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("hue", value);
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
        if (o.has("rainbow")) {
            rainbow = o.get("rainbow").getAsBoolean();
        }
    }
}
