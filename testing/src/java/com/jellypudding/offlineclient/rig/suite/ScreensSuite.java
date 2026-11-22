package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.gui.ClickGuiScreen;
import com.jellypudding.offlineclient.gui.HudEditorScreen;
import com.jellypudding.offlineclient.gui.TabStrip;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.rig.RigConfig;
import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Drawn;
import com.jellypudding.offlineclient.rig.core.Frames;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Every screen of the client is opened in a world with first run settings and drawn at each GUI
// scale. A picture of each is kept and each is held to the rules a frame can be held to
// without eyes.
public final class ScreensSuite implements Suite {

    private static final String NAME = "Screens";
    private static final int SETTLE_TICKS = 10;
    private static final int MOST_TOLD = 8;
    private static final String CHOOSE_TAB = "chooseTab";
    private static final String HUD = "HUD";
    private static final String HIDE_IN_DEBUG = "Hide in debug screen";
    private static final String IN_LIST = "In module list";
    private static final int SEAT_TICKS = 2;
    // Pieces a player might well have on together. Each has something to show in an empty world
    // or shows a sample of itself in the editor.
    private static final List<String> HANDFUL = List.of("Watermark", "Info bar", "Compass", "Armour",
        "Potion timers", "Keystrokes", "Statistics", "Kill stats");
    // A module with nothing to do in an empty world. The Module list has it to write.
    private static final String LISTED = "Sprint";
    // What ships switched on and stays out of the Module list until its own switch says otherwise.
    private static final List<String> UNLISTED = List.of("ServerSpoof", "AntiCrash", "Privacy");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        List<Case> cases = new ArrayList<>();
        for (int scale : RigConfig.scales()) {
            String at = " at GUI scale " + scale;
            cases.add(Case.of("the HUD in the world" + at, each -> hud(each, scale)));
            cases.add(Case.of("a handful of HUD pieces keep clear of each other" + at,
                each -> handful(each, scale)));
            cases.add(Case.of("the HUD over the debug screen" + at, each -> overDebug(each, scale)));
            cases.add(Case.of("the Module list leaves out what ships switched on" + at,
                each -> moduleList(each, scale)));
            cases.add(Case.of("the click GUI" + at, each -> clickGui(each, scale, null)));
            for (TabStrip.Tab tab : TabStrip.Tab.values()) {
                // The HUD tab leaves the click GUI for the editor which has a case of its own.
                if (tab != TabStrip.Tab.HUD) {
                    cases.add(Case.of("the " + tab.getLabel() + " tab" + at, each -> clickGui(each, scale, tab)));
                }
            }
            cases.add(Case.of("the HUD editor" + at, each -> hudEditor(each, scale)));
        }
        List<Case> wanted = rig.wanted(NAME, cases);
        if (wanted.isEmpty()) {
            return;
        }
        int scaleBefore = rig.ask(mc -> mc.options.guiScale().get());
        rig.openWorld(WorldCreationUiState.SelectedGameMode.CREATIVE);
        try {
            for (Case each : wanted) {
                rig.client().restoreDefaults();
                closeScreen(rig);
                rig.run(NAME, each);
            }
        } finally {
            closeScreen(rig);
            rig.act(mc -> mc.debugEntries.setOverlayVisible(false));
            rig.frames().guiScale(scaleBefore);
        }
    }

    private static void hud(Rig rig, int scale) {
        rig.frames().guiScale(scale);
        Frames.Frame frame = picture(rig, "hud scale " + scale);
        rig.note("The HUD wrote " + String.join(" / ", frame.texts()));
        audit(rig, frame);
    }

    // Each piece is switched on in the editor one after another the way a player does it. None
    // may come to lie over another in the world afterwards. The editor writes a name over every
    // piece and its picture is kept for eyes alone.
    private static void handful(Rig rig, int scale) {
        rig.frames().guiScale(scale);
        piecesOn(rig);
        picture(rig, "hud editor handful scale " + scale);
        closeScreen(rig);
        rig.client().enable(LISTED);
        audit(rig, picture(rig, "hud handful scale " + scale));
    }

    // Leaves the editor open. A piece switched on there takes the nearest free place.
    private static void piecesOn(Rig rig) {
        rig.act(mc -> mc.gui.setScreen(HudEditorScreen.open()));
        rig.ticks(SETTLE_TICKS);
        for (String name : HANDFUL) {
            boolean found = rig.ask(mc -> {
                for (HudElement piece : Modules.get(HudModule.class).getManager().all()) {
                    if (piece.getName().equals(name)) {
                        piece.setActive(true);
                        return true;
                    }
                }
                return false;
            });
            rig.expect(found, "The overlay has no piece called " + name);
            rig.ticks(SEAT_TICKS);
        }
    }

    private static void moduleList(Rig rig, int scale) {
        rig.frames().guiScale(scale);
        rig.client().enable(LISTED);
        Frames.Frame first = picture(rig, "module list scale " + scale);
        rig.expect(first.shows(LISTED), "The Module list does not write " + LISTED + " which is on");
        for (String module : UNLISTED) {
            rig.expect(rig.client().isOn(module), module + " does not ship switched on");
            rig.expect(!first.shows(module), "The Module list writes " + module + " on a first run");
        }
        String asked = UNLISTED.getLast();
        rig.client().set(asked, IN_LIST, "on");
        Frames.Frame second = picture(rig, "module list with " + asked + " scale " + scale);
        rig.expect(second.shows(asked), "The Module list leaves " + asked + " out with its own switch on");
    }

    // The overlay goes on after the debug text and its own switch takes it off that screen.
    private static void overDebug(Rig rig, int scale) {
        rig.frames().guiScale(scale);
        piecesOn(rig);
        closeScreen(rig);
        rig.client().enable(LISTED);
        Set<String> withoutDebug = places(picture(rig, "hud before debug scale " + scale));
        rig.act(mc -> mc.debugEntries.setOverlayVisible(true));
        try {
            Frames.Frame shown = picture(rig, "hud over debug scale " + scale);
            rig.client().set(HUD, HIDE_IN_DEBUG, "on");
            Set<String> debugAlone = places(picture(rig, "debug with the hud hidden scale " + scale));
            int lastOfDebug = -1;
            int firstOfOverlay = -1;
            int ofOverlay = 0;
            for (int i = 0; i < shown.drawn().size(); i++) {
                Drawn each = shown.drawn().get(i);
                if (!written(each)) {
                    continue;
                }
                // A line of the debug screen that comes and goes between two frames is neither.
                boolean ofTheOverlay = withoutDebug.contains(place(each));
                boolean ofTheDebugScreen = debugAlone.contains(place(each));
                if (ofTheOverlay && !ofTheDebugScreen) {
                    ofOverlay++;
                    firstOfOverlay = firstOfOverlay < 0 ? i : firstOfOverlay;
                } else if (ofTheDebugScreen && !ofTheOverlay) {
                    lastOfDebug = i;
                }
            }
            rig.note(ofOverlay + " texts of the overlay show with the debug screen open");
            rig.expect(lastOfDebug >= 0, "The debug screen wrote nothing");
            rig.expect(ofOverlay > 0, "The overlay wrote nothing whilst the debug screen was open");
            rig.expect(firstOfOverlay > lastOfDebug, "The overlay is drawn before the debug text and lies under it");
        } finally {
            rig.act(mc -> mc.debugEntries.setOverlayVisible(false));
        }
    }

    private static void clickGui(Rig rig, int scale, TabStrip.Tab tab) {
        rig.frames().guiScale(scale);
        rig.act(mc -> mc.gui.setScreen(new ClickGuiScreen()));
        rig.ticks(SETTLE_TICKS);
        if (tab != null) {
            choose(rig, tab);
        }
        String name = (tab == null ? "click gui" : "tab " + tab.getLabel()) + " scale " + scale;
        audit(rig, picture(rig, name));
    }

    private static void hudEditor(Rig rig, int scale) {
        rig.frames().guiScale(scale);
        rig.act(mc -> mc.gui.setScreen(HudEditorScreen.open()));
        Frames.Frame frame = picture(rig, "hud editor scale " + scale);
        rig.expect(rig.ask(mc -> mc.gui.screen() instanceof HudEditorScreen), "The HUD editor did not open");
        audit(rig, frame);
    }

    // The click GUI keeps the choice of a tab to itself. A click on the strip ends in this call.
    private static void choose(Rig rig, TabStrip.Tab tab) {
        rig.act(mc -> {
            try {
                Method choose = ClickGuiScreen.class.getDeclaredMethod(CHOOSE_TAB, TabStrip.Tab.class);
                choose.setAccessible(true);
                choose.invoke(mc.gui.screen(), tab);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("The click GUI has no " + CHOOSE_TAB + " any more", e);
            }
        });
    }

    // Chat lines of an earlier step would lie under the overlay and fade as they please.
    private static Frames.Frame picture(Rig rig, String name) {
        rig.act(mc -> mc.gui.hud.getChat().clearMessages(true));
        rig.ticks(SETTLE_TICKS);
        rig.controls().parkPointer();
        rig.ticks(1);
        return rig.frames().capture(name);
    }

    private static void audit(Rig rig, Frames.Frame frame) {
        List<String> problems = frame.problems();
        rig.note("The frame holds " + frame.drawn().size() + " texts and boxes");
        if (!problems.isEmpty()) {
            List<String> told = problems.subList(0, Math.min(MOST_TOLD, problems.size()));
            rig.expect(false, problems.size() + " texts break a rule. " + String.join(" ", told));
        }
    }

    // Where every text of a frame starts. A text that changes from frame to frame keeps its place.
    private static Set<String> places(Frames.Frame frame) {
        Set<String> places = new HashSet<>();
        for (Drawn each : frame.drawn()) {
            if (written(each)) {
                places.add(place(each));
            }
        }
        return places;
    }

    private static boolean written(Drawn drawn) {
        return drawn.isText() && !drawn.text().isBlank() && !drawn.shown().isEmpty();
    }

    private static String place(Drawn text) {
        return text.box().left() + " " + text.box().top();
    }

    private static void closeScreen(Rig rig) {
        rig.act(mc -> mc.gui.setScreen(null));
        rig.ticks(1);
    }
}
