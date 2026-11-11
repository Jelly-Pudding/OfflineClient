package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.hud.elements.TabGuiElement;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;

// A list you walk with the arrow keys without opening a screen. Handy mid fight. The TabGUI
// element of the HUD draws it whilst this is on.
public final class TabGui extends Module {

    // One line of the list in the colour it is written in.
    public record Row(String name, int colour) {
    }

    private final ColorSetting text = new ColorSetting("Row colour",
        "Colour of a row you are not on.", 0, 0f, 0.85f, false);
    private final ColorSetting picked = new ColorSetting("Picked colour",
        "Colour of the row you are on.", 190, false);
    private final ColorSetting on = new ColorSetting("Enabled colour",
        "Colour of a module that is switched on.", 120, 0.7f, 0.9f, false);

    // Null whilst the list shows the categories themselves.
    private Category open;
    private int row;

    public TabGui() {
        super("TabGUI", "A module list on screen you steer with the arrow keys and place with .hud edit.",
            Category.MISC);
        addSettings(text, picked, on);
        searchTags("tab gui", "arrow menu", "quick toggle");
    }

    @Override
    protected void onEnable() {
        open = null;
        row = 0;
        Modules.tellHudOff(this);
    }

    // The list as it stands. The row you are on takes the picked colour and in a category a
    // module that is switched on takes the enabled colour.
    public List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (open == null) {
            Category[] categories = Category.values();
            for (int i = 0; i < categories.length; i++) {
                rows.add(new Row(categories[i].getDisplayName(), i == row ? picked.getColor() : text.getColor()));
            }
            return rows;
        }
        List<Module> modules = modules();
        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            int colour = i == row ? picked.getColor() : module.isEnabled() ? on.getColor() : text.getColor();
            rows.add(new Row(module.getName(), colour));
        }
        return rows;
    }

    // The row the cursor is on.
    public int cursor() {
        return row;
    }

    private List<Module> modules() {
        return open == null ? List.of()
            : OfflineClient.INSTANCE.getModuleManager().getByCategory(open);
    }

    // Up and down move the cursor. Right or enter opens a category or toggles a module. Left goes back.
    // Nothing moves whilst the list is out of sight.
    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.getAction() == InputConstants.RELEASE || mc.gui.screen() != null || !inSight()) {
            return;
        }
        int count = open == null ? Category.values().length : modules().size();
        if (count == 0) {
            return;
        }
        switch (event.getKey()) {
            case InputConstants.KEY_UP -> row = Math.floorMod(row - 1, count);
            case InputConstants.KEY_DOWN -> row = Math.floorMod(row + 1, count);
            case InputConstants.KEY_RIGHT, InputConstants.KEY_RETURN -> choose();
            case InputConstants.KEY_LEFT -> back();
            default -> {
            }
        }
    }

    // The HUD draws the list whilst its piece is on. It goes when the game's own HUD is hidden and
    // steps aside for the debug screen. Conceal only hides it and the keys keep working.
    private boolean inSight() {
        HudModule hud = Modules.active(HudModule.class);
        return hud != null && !hud.overlayHidden() && !mc.gui.hud.isHidden()
            && Modules.hudShows(TabGuiElement.class);
    }

    private void choose() {
        if (open == null) {
            open = Category.values()[row];
            row = 0;
            return;
        }
        List<Module> modules = modules();
        if (row < modules.size() && modules.get(row).isTogglable()) {
            modules.get(row).toggle();
        }
    }

    private void back() {
        if (open == null) {
            return;
        }
        row = Math.max(0, List.of(Category.values()).indexOf(open));
        open = null;
    }
}
