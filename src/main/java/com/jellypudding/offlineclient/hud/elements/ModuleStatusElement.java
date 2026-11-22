package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.RowCache;
import com.jellypudding.offlineclient.hud.RowsElement;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;

public final class ModuleStatusElement extends RowsElement {

    private static final List<String> WATCHED = List.of("KillAura", "CrystalAura", "AnchorAura", "BedAura",
        "Surround");

    private static final String ON = "On";
    private static final String OFF = "Off";

    private record Status(String name, boolean on, String doing) {
    }

    private static final List<Status> SAMPLE = List.of(new Status("KillAura", true, "Zombie"),
        new Status("Surround", false, null));

    private final ChoiceListSetting watched = new ChoiceListSetting("Watched modules",
        "The modules listed. Click to pick them.",
        () -> OfflineClient.INSTANCE.getModuleManager().togglableNames(Modules.get(HudModule.class)), WATCHED);
    private final BoolSetting details = new BoolSetting("Status details",
        "Writes what a module is doing in place of On.", true);
    private final ColorSetting nameColor = labelColor("Status name colour",
        "Colour of the module names.");
    private final ColorSetting onColor = new ColorSetting("Status on colour",
        "Colour of On and of what a module that is on is doing.", 120, false);
    private final ColorSetting offColor = new ColorSetting("Status off colour",
        "Colour of Off.", 0, false);
    private final RowCache<Status> statuses = new RowCache<>(this::build);

    public ModuleStatusElement() {
        super("Module status", "Whether the modules you pick are on or off.", false, 0, 20);
        add(watched, details, nameColor, onColor, offColor);
    }

    private List<Status> shown() {
        List<Status> built = statuses.get();
        return built.isEmpty() && editing() ? SAMPLE : built;
    }

    private List<Status> build() {
        List<Status> built = new ArrayList<>(watched.size());
        for (String name : watched.getValue()) {
            Module module = OfflineClient.INSTANCE.getModuleManager().get(name);
            if (module != null) {
                built.add(new Status(module.getName(), module.isEnabled(), module.getSuffix()));
            }
        }
        return built;
    }

    @Override
    protected List<TextRow> rows(Font font) {
        List<Status> shown = shown();
        List<TextRow> rows = new ArrayList<>(shown.size());
        for (int row = 0; row < shown.size(); row++) {
            Status status = shown.get(row);
            int tint = status.on() ? onColor.rowColor(row) : offColor.rowColor(row);
            rows.add(TextRow.of(font, List.of(status.name() + " ", stateOf(status)),
                List.of(nameColor.rowColor(row), tint)));
        }
        return rows;
    }

    private String stateOf(Status status) {
        if (!status.on()) {
            return OFF;
        }
        return details.isOn() && status.doing() != null ? status.doing() : ON;
    }
}
