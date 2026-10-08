package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChatUtil;

// Leaves marks out below one height or above another. Whilst it is switched off every height
// counts. The top of the highest slider sets no ceiling and a player flying over the build limit
// is never hidden by a default.
public final class HeightBand {

    // The whole block heights a scan reads. Every height whilst the band is off.
    public record Rows(int from, int to) {
    }

    // No world reaches lower.
    private static final double DEEPEST = -2048;
    // The overworld from its floor to its roof.
    private static final double FLOOR = -64;
    private static final double ROOF = 320;

    private final BoolSetting band;
    private final NumberSetting lowest;
    private final NumberSetting highest;

    // The things marked such as blocks or entities.
    public HeightBand(String marked) {
        String sentence = ChatUtil.capitalised(marked);
        band = new BoolSetting("Height band", "Only marks " + marked + " between the two heights below.", false);
        lowest = new NumberSetting("Lowest height", sentence + " below this height are left unmarked.",
            FLOOR, FLOOR, ROOF, 1).min(DEEPEST).under(band);
        highest = new NumberSetting("Highest height",
            sentence + " above this height are left unmarked. The top of the slider sets no limit.",
            ROOF, FLOOR, ROOF, 1).min(DEEPEST).max(ROOF).under(band);
    }

    public Setting<?>[] settings() {
        return new Setting<?>[] {band, lowest, highest};
    }

    public boolean contains(double y) {
        return !band.isOn() || y >= lowest.getValue() && (noCeiling() || y <= highest.getValue());
    }

    public Rows rows() {
        if (!band.isOn()) {
            return new Rows(Integer.MIN_VALUE, Integer.MAX_VALUE);
        }
        return new Rows((int) Math.ceil(lowest.getValue()),
            noCeiling() ? Integer.MAX_VALUE : (int) Math.floor(highest.getValue()));
    }

    private boolean noCeiling() {
        return highest.getValue() >= ROOF;
    }
}
