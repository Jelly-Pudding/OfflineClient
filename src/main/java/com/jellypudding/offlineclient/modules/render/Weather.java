package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;

// LevelMixin hands these levels back in place of the real ones.
public final class Weather extends Module {

    private final NumberSetting rain = new NumberSetting("Rain",
        "How heavy the rain looks. Zero is a clear sky.", 0, 0, 1, 0.05).max(1);
    private final NumberSetting thunder = new NumberSetting("Thunder",
        "How dark a storm looks. Zero is no storm.", 0, 0, 1, 0.05).max(1);

    public Weather() {
        super("Weather", "Sets the rain and thunder you see. Clears them by default.", Category.RENDER);
        addSettings(rain, thunder);
        searchTags("clear skies", "no rain", "weather changer");
    }

    @Override
    public String getSuffix() {
        return rain.getValue() == 0 && thunder.getValue() == 0 ? "Clear" : null;
    }

    public float rainLevel() {
        return rain.getFloat();
    }

    public float thunderLevel() {
        return thunder.getFloat();
    }
}
