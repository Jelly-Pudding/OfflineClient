package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.gui.WindowGuiScreen;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class WatermarkElement extends HudElement {

    // Space between the name and the version.
    private static final int GAP = 6;

    // The windowed ClickGUI writes the client name near the top left. The watermark gives way
    // whilst it sits within this percent of the screen from that corner.
    private static final double CORNER = 25;

    private final ColorSetting color = new ColorSetting("Watermark colour",
        "Colour of the client name.", 200, false);
    private final BoolSetting showVersion = new BoolSetting("Show version",
        "Writes the version after the name.", true);
    private final ColorSetting versionColor = new ColorSetting("Version colour",
        "Colour of the version number.", 240, 0.08f, 0.75f, false);

    public WatermarkElement() {
        super("Watermark", "Client name and version.", false, 0, 0, 0.5);
        versionColor.under(showVersion);
        add(color, showVersion, versionColor);
    }

    @Override
    public boolean visible() {
        return isActive() && !(OfflineClient.MC.gui.screen() instanceof WindowGuiScreen
            && xPercent() < CORNER && yPercent() < CORNER);
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        writeName(context, font);
        if (showVersion.isOn()) {
            write(context, font, version(), font.width(OfflineClient.NAME) + GAP, 0, versionColor.getColor());
        }
    }

    // A rainbow runs along the name a letter at a time. Any other colour fills it whole.
    private void writeName(GuiGraphicsExtractor context, Font font) {
        if (!color.isRainbow()) {
            write(context, font, OfflineClient.NAME, 0, 0, color.getColor());
            return;
        }
        RenderUtil.colouredLetters(context, font, OfflineClient.NAME, 0, 0, color::rowColor, 1f, textShadow());
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
