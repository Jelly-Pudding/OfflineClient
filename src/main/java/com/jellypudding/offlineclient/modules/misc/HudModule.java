package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.gui.HudEditorScreen;
import com.jellypudding.offlineclient.hud.Families;
import com.jellypudding.offlineclient.hud.GamePart;
import com.jellypudding.offlineclient.hud.GamePartElement;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.HudManager;
import com.jellypudding.offlineclient.hud.elements.ArmourElement;
import com.jellypudding.offlineclient.hud.elements.ClientLinesElement;
import com.jellypudding.offlineclient.hud.elements.CombatElement;
import com.jellypudding.offlineclient.hud.elements.CompassElement;
import com.jellypudding.offlineclient.hud.elements.ContainerCountElement;
import com.jellypudding.offlineclient.hud.elements.CoverElement;
import com.jellypudding.offlineclient.hud.elements.EntityListElement;
import com.jellypudding.offlineclient.hud.elements.HoleElement;
import com.jellypudding.offlineclient.hud.elements.InfoBarElement;
import com.jellypudding.offlineclient.hud.elements.InventoryElement;
import com.jellypudding.offlineclient.hud.elements.ItemCounterElement;
import com.jellypudding.offlineclient.hud.elements.KeystrokesElement;
import com.jellypudding.offlineclient.hud.elements.KillFeedElement;
import com.jellypudding.offlineclient.hud.elements.KillStatsElement;
import com.jellypudding.offlineclient.hud.elements.LagNotifierElement;
import com.jellypudding.offlineclient.hud.elements.LavaWarningElement;
import com.jellypudding.offlineclient.hud.elements.LeaksElement;
import com.jellypudding.offlineclient.hud.elements.MapElement;
import com.jellypudding.offlineclient.hud.elements.MinimapElement;
import com.jellypudding.offlineclient.hud.elements.ModuleListElement;
import com.jellypudding.offlineclient.hud.elements.ModuleStatusElement;
import com.jellypudding.offlineclient.hud.elements.MountElement;
import com.jellypudding.offlineclient.hud.elements.PictureElement;
import com.jellypudding.offlineclient.hud.elements.PlayerListElement;
import com.jellypudding.offlineclient.hud.elements.PlayerModelElement;
import com.jellypudding.offlineclient.hud.elements.PotionTimersElement;
import com.jellypudding.offlineclient.hud.elements.RadarElement;
import com.jellypudding.offlineclient.hud.elements.ServerInfoElement;
import com.jellypudding.offlineclient.hud.elements.StatsElement;
import com.jellypudding.offlineclient.hud.elements.TabGuiElement;
import com.jellypudding.offlineclient.hud.elements.TextElement;
import com.jellypudding.offlineclient.hud.elements.WatermarkElement;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.ClientFonts;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.Defaults;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// The overlay itself lives in the hud package. The elements belong to this module.
// Their settings save and show in the ClickGUI like any other.
public final class HudModule extends Module {

    private final HudManager manager = new HudManager();
    private final Families families = new Families(manager, new Teller());
    private final BoolSetting textShadow = new BoolSetting("Text shadow",
        "Draws a shadow behind all overlay text.", true);
    private final BoolSetting smoothFont = new BoolSetting("Smooth font",
        "Draws the overlay text in a smooth font. Put a font.ttf in the offlineclient folder to use your own.",
        false);
    private final BoolSetting hideInMenus = new BoolSetting("Hide in menus",
        "Hides the overlay whilst a menu or a container is open.", false);
    private final BoolSetting hideInDebug = new BoolSetting("Hide in debug screen",
        "Hides the overlay whilst the debug screen is open.", false);
    private final KeybindSetting overlayKey = new KeybindSetting("Overlay key",
        "Switches the whole overlay off and on again. It does what the switch of this module does.",
        KeybindSetting.UNBOUND).heardWhilstOff();

    // Every row the module shows and the revision of the pieces it was gathered at.
    private List<Setting<?>> rows;
    private int rowsAt;
    // True from the draw with the game's own HUD until the debug text is down in a frame that
    // keeps the overlay back for that text.
    private boolean waitsForDebugText;

    // Listened for whilst the HUD is off too. The key could never bring it back otherwise.
    private final Object keys = new Object() {
        @Subscribe
        private void onKeyPress(KeyPressEvent event) {
            if (mc.gui.screen() == null && inGame() && event.presses(overlayKey)) {
                toggle();
                ChatUtil.toggled(HudModule.this);
            }
        }
    };

    // The editor says it in its foot. Anywhere else chat does with a link that takes it back.
    private final class Teller implements Families.Teller {

        @Override
        public void done(String verb, String name, Defaults.Change change) {
            if (mc.gui.screen() instanceof HudEditorScreen editor) {
                editor.piecesChanged(verb + " " + name + ".", change);
            } else {
                ChatUtil.undoable("§7" + verb + " §b" + name + "§7.", change);
            }
        }

        @Override
        public void refused(String why) {
            if (mc.gui.screen() instanceof HudEditorScreen editor) {
                editor.refuse(why);
            } else {
                ChatUtil.error(why);
            }
        }
    }

    public HudModule() {
        super("HUD", "The overlay you see whilst playing which you lay out with .hud edit along with the game's"
            + " own bars.", Category.MISC);
        addSettings(textShadow, hideInMenus, hideInDebug, overlayKey);
        addSettings(smoothFont);
        watch(keys);
        add(new WatermarkElement(), new ModuleListElement(), new ModuleStatusElement(), new InfoBarElement(),
            new ArmourElement(), new PotionTimersElement());
        add(families.kind("Item counter", 1, ItemCounterElement::new));
        add(new CombatElement(), new KeystrokesElement(), new KillStatsElement(), new KillFeedElement(),
            new StatsElement(), new LagNotifierElement(), new CompassElement());
        add(families.kind("Map", 1, MapElement::new));
        add(new RadarElement(), new MinimapElement(), new TabGuiElement(), new PlayerListElement(),
            new EntityListElement(), new ContainerCountElement(), new LeaksElement(), new ClientLinesElement(),
            new ServerInfoElement(), new InventoryElement(), new PlayerModelElement(), new HoleElement(),
            new MountElement(), new LavaWarningElement());
        add(families.kind("Picture", 1, PictureElement::new));
        add(families.kind("Custom text", TextElement.USUAL, TextElement::new));
        // Covers come after every other element and sit over all of them.
        for (int number = 1; number <= CoverElement.COUNT; number++) {
            add(new CoverElement(number));
        }
        for (GamePart part : GamePart.values()) {
            add(new GamePartElement(part));
        }
        searchTags("overlay", "watermark", "module list", "info", "hud editor", "hotbar", "hearts",
            "hunger", "armour", "experience", "statistics", "stats", "play time", "cover", "censor",
            "streamer", "hide coordinates", "horse", "mount", "elytra", "coordinate leaks", "module status",
            "keystrokes", "cps", "clicks per second", "map", "radar", "minimap", "client messages",
            "overlay key", "hide hud", "custom text", "live words", "kill feed", "picture", "image", "logo",
            "entity list", "container count", "lava warning", "tab gui");
        // A reset or an undo may flip the switch of a piece. The player did not switch it on.
        Defaults.listen(manager::takeAsTheyStand);
    }

    private void add(HudElement... elements) {
        add(List.of(elements));
    }

    private void add(List<HudElement> elements) {
        elements.forEach(manager::add);
    }

    // The rows of every piece follow the module's own in the order the pieces are listed. Pieces
    // come and go as the player adds and removes them and the rows are gathered afresh then. The
    // store of the added pieces leads. A load makes those pieces before any of their rows is
    // looked for and a bad value further down cannot lose them.
    @Override
    public List<Setting<?>> getSettings() {
        if (rows == null || rowsAt != manager.revision()) {
            List<Setting<?>> all = new ArrayList<>();
            all.add(families.store());
            all.addAll(super.getSettings());
            for (HudElement element : manager.all()) {
                all.addAll(element.getSettings());
            }
            rows = Collections.unmodifiableList(all);
            rowsAt = manager.revision();
        }
        return rows;
    }

    public Families families() {
        return families;
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    public HudManager getManager() {
        return manager;
    }

    public boolean textShadow() {
        return textShadow.isOn();
    }

    public boolean smoothFont() {
        return smoothFont.isOn();
    }

    // The game's own bars that are switched on follow their elements whilst the HUD is on
    // and always in the editor where they are being placed.
    public boolean placesGameParts() {
        return isEnabled() || HudElement.editing();
    }

    // True whilst the overlay drawn with the game's own HUD steps aside. Hide in menus hides it
    // under any screen but the chat box and Hide in debug screen whilst the debug screen is open.
    // A menu with the debug screen open hides it whatever the two say. The game draws the debug
    // text over the menu and the overlay goes over that text or not at all.
    public boolean overlayHidden() {
        Screen screen = mc.gui.screen();
        boolean menu = screen != null && !(screen instanceof ChatScreen);
        boolean debug = mc.debugEntries.isOverlayVisible();
        return debug && hideInDebug.isOn() || menu && (debug || hideInMenus.isOn());
    }

    @Override
    public boolean keySwitches() {
        return false;
    }

    // A key on the HUD opens the editor. The switch in the GUI still turns it all off.
    @Override
    public void onKeybind() {
        mc.schedule(() -> mc.gui.setScreen(new HudEditorScreen(manager)));
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        renderOverlay(event.getContext());
    }

    // The overlay drawn along with the game's own HUD. Whilst it steps aside only the elements
    // that stay are drawn. Whilst the debug screen is open they are all that is drawn here as
    // well and the rest waits for renderOverDebugText. The editor draws all of it itself.
    public void renderOverlay(GuiGraphicsExtractor context) {
        if (HudElement.editing()) {
            return;
        }
        boolean hidden = overlayHidden();
        waitsForDebugText = !hidden && mc.debugEntries.isOverlayVisible();
        manager.render(context, ClientFonts.hud(), hidden, waitsForDebugText);
    }

    // Called once the game has drawn the debug text. What the frame kept back goes on over it.
    public void renderOverDebugText(GuiGraphicsExtractor context) {
        if (waitsForDebugText) {
            waitsForDebugText = false;
            manager.renderOverDebugText(context, ClientFonts.hud());
        }
    }

    // The last draw of each frame. The debug text still draws over a hidden HUD whilst a
    // screen is open and the covers stay with it. Hide in menus leaves them alone. The editor
    // draws them itself.
    public void renderOnTop(GuiGraphicsExtractor context) {
        if (!isEnabled() || mc.level == null || HudElement.editing()) {
            return;
        }
        if (mc.gui.hud.isHidden() && mc.gui.screen() == null) {
            return;
        }
        manager.renderOnTop(context, ClientFonts.hud());
    }
}
