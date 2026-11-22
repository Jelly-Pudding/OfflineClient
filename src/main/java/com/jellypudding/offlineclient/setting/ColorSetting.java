package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.util.ColorUtil;

import java.util.Locale;

// A colour stored as a hue from nought to 360 with saturation and brightness.
// Rainbow mode cycles the hue on its own.
public final class ColorSetting extends Setting<Float> {

    private static final float FULL = 1f;

    private static final float DEFAULT_SATURATION = 0.75f;

    public static final String HEX_MARK = "#";
    private static final int HEX_DIGITS = 6;
    private static final int HEX = 16;
    private static final int RGB = 0xFFFFFF;

    private final boolean defaultRainbow;
    private final float defaultSaturation;
    private final float defaultBrightness;

    private boolean rainbow;
    private float saturation;
    private float brightness;

    public ColorSetting(String name, String description, float defaultHue, boolean defaultRainbow) {
        this(name, description, defaultHue, DEFAULT_SATURATION, FULL, defaultRainbow);
    }

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

    public int getColor() {
        if (rainbow) {
            return ColorUtil.rainbow(0);
        }
        return ColorUtil.hsv(value, saturation, brightness);
    }

    // The colour of one row of a list. A rainbow runs down the rows and any other colour stays put.
    public int rowColor(int row) {
        return rainbow ? ColorUtil.rainbow(row) : getColor();
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
        if (Float.isFinite(hue)) {
            value = ((hue % 360f) + 360f) % 360f;
        }
    }

    public float getSaturation() {
        return saturation;
    }

    public void setSaturation(float saturation) {
        if (Float.isFinite(saturation)) {
            this.saturation = Math.clamp(saturation, 0f, 1f);
        }
    }

    public float getBrightness() {
        return brightness;
    }

    public void setBrightness(float brightness) {
        if (Float.isFinite(brightness)) {
            this.brightness = Math.clamp(brightness, 0f, 1f);
        }
    }

    public String hex() {
        return String.format(Locale.ROOT, HEX_MARK + "%06X", getColor() & RGB);
    }

    // Ends the rainbow. False for text that is no six hex digits.
    public boolean setHex(String text) {
        String digits = text.startsWith(HEX_MARK) ? text.substring(HEX_MARK.length()) : text;
        if (digits.length() != HEX_DIGITS || !digits.chars().allMatch(digit -> Character.digit(digit, HEX) >= 0)) {
            return false;
        }
        float[] shade = ColorUtil.hsvOf(Integer.parseInt(digits, HEX));
        rainbow = false;
        setHue(shade[0]);
        setSaturation(shade[1]);
        setBrightness(shade[2]);
        return true;
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

    // The hue alone is not the colour. Another shade or the rainbow differs too.
    @Override
    public boolean isDefault() {
        return super.isDefault() && rainbow == defaultRainbow && saturation == defaultSaturation
            && brightness == defaultBrightness;
    }

    // A fixed shade has no short name.
    @Override
    public String defaultText() {
        return defaultRainbow ? "rainbow" : null;
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
