package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

// BossHealthOverlayMixin hands the bars through here before they are drawn.
public final class BossStack extends Module {

    private static final int VANILLA_GAP = 10;

    private final BoolSetting stack = new BoolSetting("Stack",
        "Merge boss bars that share a name into one bar with a count after it.", true);
    private final BoolSetting hideName = new BoolSetting("Hide names",
        "Leave every boss bar without its name.", false);
    private final NumberSetting gap = new NumberSetting("Bar gap",
        "Pixels of space between one bar and the next.", 10, 0, 20, 1, " px").min(0).max(40);

    // How many bars each drawn name stands for this frame.
    private final Map<String, Integer> counts = new HashMap<>();

    public BossStack() {
        super("BossStack", "Stacks boss bars that share a name and tightens the gap between them.", Category.RENDER);
        addSettings(stack, hideName, gap);
        searchTags("boss bar", "wither", "dragon");
    }

    // The bars to draw with the copies of a name folded into the first one.
    public Iterator<LerpingBossEvent> bars(Iterator<LerpingBossEvent> all) {
        counts.clear();
        if (!isEnabled() || !stack.isOn()) {
            return all;
        }
        Map<String, LerpingBossEvent> first = new LinkedHashMap<>();
        while (all.hasNext()) {
            LerpingBossEvent bar = all.next();
            String name = bar.getName().getString();
            first.putIfAbsent(name, bar);
            counts.merge(name, 1, Integer::sum);
        }
        return first.values().iterator();
    }

    public Component name(Component original) {
        if (!isEnabled()) {
            return original;
        }
        if (hideName.isOn()) {
            return Component.empty();
        }
        int count = counts.getOrDefault(original.getString(), 1);
        return count > 1 ? original.copy().append(" x" + count) : original;
    }

    public int gap() {
        return isEnabled() ? gap.getInt() : VANILLA_GAP;
    }
}
