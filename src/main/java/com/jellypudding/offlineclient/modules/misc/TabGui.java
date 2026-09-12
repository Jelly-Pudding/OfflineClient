package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

// A list you walk with the arrow keys without opening a screen. Handy mid fight.
public final class TabGui extends Module {

    private static final int ROW = 11;
    private static final int PAD = 3;
    private static final int MIN_WIDTH = 60;

    private final NumberSetting x = new NumberSetting("Left",
        "How far in from the left edge it sits.", 0, 0, 400, 1, " px").min(0);
    private final NumberSetting y = new NumberSetting("Top",
        "How far down from the top edge it sits.", 0, 0, 400, 1, " px").min(0);
    private final ColorSetting background = new ColorSetting("Background",
        "Colour of the panel.", 240, 0.35f, 0.09f, false);
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
        super("TabGUI", "A module list on screen you steer with the arrow keys. No menu to open.",
            Category.MISC);
        addSettings(x, y, background, text, picked, on);
        searchTags("tab gui", "arrow menu", "quick toggle");
    }

    @Override
    protected void onEnable() {
        open = null;
        row = 0;
    }

    private List<String> rows() {
        List<String> names = new ArrayList<>();
        if (open == null) {
            for (Category category : Category.values()) {
                names.add(category.getDisplayName());
            }
        } else {
            for (Module module : OfflineClient.INSTANCE.getModuleManager().getByCategory(open)) {
                names.add(module.getName());
            }
        }
        return names;
    }

    private List<Module> modules() {
        return open == null ? List.of()
            : OfflineClient.INSTANCE.getModuleManager().getByCategory(open);
    }

    // Up and down move the cursor. Right or enter opens a category or toggles a module. Left goes back.
    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.getAction() == GLFW.GLFW_RELEASE || OfflineClient.MC.gui.screen() != null) {
            return;
        }
        int count = rows().size();
        if (count == 0) {
            return;
        }
        switch (event.getKey()) {
            case GLFW.GLFW_KEY_UP -> row = Math.floorMod(row - 1, count);
            case GLFW.GLFW_KEY_DOWN -> row = Math.floorMod(row + 1, count);
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_ENTER -> choose();
            case GLFW.GLFW_KEY_LEFT -> back();
            default -> {
            }
        }
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
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
        }
    }

    private void back() {
        if (open == null) {
            return;
        }
        row = Math.max(0, List.of(Category.values()).indexOf(open));
        open = null;
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        GuiGraphicsExtractor context = event.getContext();
        Font font = mc.font;
        List<String> names = rows();
        if (names.isEmpty()) {
            return;
        }
        int left = x.getInt();
        int top = y.getInt();
        int wide = MIN_WIDTH;
        for (String name : names) {
            wide = Math.max(wide, font.width(name) + PAD * 2 + ROW);
        }
        int tall = names.size() * ROW + PAD * 2;
        context.fill(left, top, left + wide, top + tall, background.getColor());
        context.guiRenderState.up();

        List<Module> modules = modules();
        for (int i = 0; i < names.size(); i++) {
            int rowTop = top + PAD + i * ROW;
            int colour = i == row ? picked.getColor() : text.getColor();
            if (open != null && i < modules.size() && modules.get(i).isEnabled()) {
                colour = i == row ? picked.getColor() : on.getColor();
            }
            if (i == row) {
                context.fill(left, rowTop - 1, left + wide, rowTop + ROW - 1, 0x30FFFFFF);
            }
            context.text(font, names.get(i), left + PAD, rowTop, colour, true);
        }
    }
}
