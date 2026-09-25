package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ModuleListElement extends HudElement {

    public enum Sort { LENGTH, ALPHABETICAL }

    private static final int LINE = 10;

    // How quickly a row closes on its new place. Most of the way in a tenth of a second.
    private static final double GLIDE_RATE = 20;

    private final EnumSetting<Sort> sort = new EnumSetting<>("List order",
        "How the rows are ordered.", Sort.LENGTH)
        .describe(Sort.LENGTH, "Longest row first.")
        .describe(Sort.ALPHABETICAL, "By name from A to Z.");
    private final ColorSetting color = new ColorSetting("List colour",
        "Colour of the module names.", 200, false);
    private final ColorSetting suffixColor = new ColorSetting("Suffix colour",
        "Colour of the extra word after a name such as the mode it is in.", 0, 0, 0.67f, false);
    private final ColorSetting bracketColor = new ColorSetting("Bracket colour",
        "Colour of the brackets round that word.", 0, 0, 0.67f, false);
    private final BoolSetting background = new BoolSetting("List background",
        "Draws a panel behind the rows.", false);
    private final ColorSetting backgroundColor = new ColorSetting("List background colour",
        "Colour of that panel.", 240, 0.3f, 0.1f, false);

    public ModuleListElement() {
        super("Module list", "Enabled modules listed one to a line.", false, 100, 0, 0.5);
        backgroundColor.under(background);
        add(sort, color, suffixColor, bracketColor, background, backgroundColor);
    }

    // Where each row is drawn right now. A row that changes place glides there.
    private final Map<Module, Double> rowY = new HashMap<>();
    private long lastFrame;

    @Override
    public boolean visible() {
        return isActive() && !shown().isEmpty();
    }

    // The enabled modules in the order they are drawn. The HUD itself never lists itself.
    private List<Module> shown() {
        List<Module> enabled =
            new ArrayList<>(OfflineClient.INSTANCE.getModuleManager().getEnabled());
        enabled.removeIf(module -> module instanceof HudModule);
        return enabled;
    }

    private List<Module> sorted(Font font) {
        List<Module> enabled = shown();
        if (sort.is(Sort.ALPHABETICAL)) {
            enabled.sort(Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER));
        } else {
            enabled.sort(Comparator.comparingInt((Module module) -> rowWidth(font, module)).reversed()
                .thenComparing(Module::getName, String.CASE_INSENSITIVE_ORDER));
        }
        return enabled;
    }

    // The width of a row with its suffix and brackets.
    private static int rowWidth(Font font, Module module) {
        String suffix = module.getSuffix();
        return font.width(suffix == null ? module.getName()
            : module.getName() + " [" + suffix + "]");
    }

    // Rows are right aligned inside the block. The ragged edge faces inward.
    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        List<Module> enabled = sorted(font);
        int widest = width(font);
        if (background.isOn()) {
            context.fill(-2, -1, widest + 2, enabled.size() * LINE + 1, backgroundColor.getColor());
        }
        int tint = color.getColor();
        int suffixTint = suffixColor.getColor();
        int bracketTint = bracketColor.getColor();
        double keep = glideKeep();
        rowY.keySet().retainAll(enabled);
        for (int row = 0; row < enabled.size(); row++) {
            Module module = enabled.get(row);
            int target = row * LINE;
            double drawn = target + (rowY.getOrDefault(module, (double) target) - target) * keep;
            rowY.put(module, drawn);
            int y = (int) Math.round(drawn);
            int x = widest - rowWidth(font, module);
            String name = module.getName();
            String suffix = module.getSuffix();
            context.text(font, name, x, y, tint, true);
            if (suffix != null) {
                // Each part takes its own colour. The space keeps the vanilla gap.
                x += font.width(name + " ");
                context.text(font, "[", x, y, bracketTint, true);
                x += font.width("[");
                context.text(font, suffix, x, y, suffixTint, true);
                x += font.width(suffix);
                context.text(font, "]", x, y, bracketTint, true);
            }
        }
    }

    // The share of the gap to its place a row still has left after this frame.
    private double glideKeep() {
        long now = System.nanoTime();
        double seconds = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1.0E9, 1);
        lastFrame = now;
        return Math.exp(-GLIDE_RATE * seconds);
    }

    @Override
    public int width(Font font) {
        int widest = 0;
        for (Module module : shown()) {
            widest = Math.max(widest, rowWidth(font, module));
        }
        return widest;
    }

    @Override
    public int height(Font font) {
        return Math.max(LINE, shown().size() * LINE);
    }
}
