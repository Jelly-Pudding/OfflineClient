package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class WatermarkElement extends HudElement {

    // Space between the name and the version.
    private static final int GAP = 6;

    private final ColorSetting color = new ColorSetting("Watermark colour",
        "Colour of the client name.", 200, false);
    private final BoolSetting showVersion = new BoolSetting("Show version",
        "Writes the version after the name.", true);
    private final ColorSetting versionColor = new ColorSetting("Version colour",
        "Colour of the version number.", 240, 0.08f, 0.75f, false);

    public WatermarkElement() {
        super("Watermark", "Client name and version.", false, 0, 0);
        versionColor.under(showVersion);
        add(color, showVersion, versionColor);
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        if (color.isRainbow()) {
            RenderUtil.rainbowText(context, font, OfflineClient.NAME, 0, 0);
        } else {
            context.text(font, OfflineClient.NAME, 0, 0, color.getColor(), true);
        }
        if (showVersion.isOn()) {
            context.text(font, version(), font.width(OfflineClient.NAME) + GAP, 0,
                versionColor.getColor(), true);
        }
    }

    private static String version() {
        return "v" + OfflineClient.VERSION;
    }

    @Override
    public int width(Font font) {
        int width = font.width(OfflineClient.NAME);
        return showVersion.isOn() ? width + GAP + font.width(version()) : width;
    }

    @Override
    public int height(Font font) {
        return font.lineHeight;
    }
}
