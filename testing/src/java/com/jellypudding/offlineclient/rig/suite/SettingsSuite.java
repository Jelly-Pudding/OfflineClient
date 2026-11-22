package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.ConfigManager;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigClock;
import com.jellypudding.offlineclient.rig.core.RigFailure;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.duo.Duo;
import com.jellypudding.offlineclient.rig.duo.PaperServer;
import com.jellypudding.offlineclient.rig.suite.Tweaks.Tweak;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Every setting of every module is put through its values whilst the module is on. In single
// player a switch is flipped and a choice takes each of its options and a number goes to both
// ends of its slider. On the Paper server beside a second player the switches and the choices go
// round again and a kick fails the case. Nothing may throw or log a fault and every value must
// take. At the end every setting is changed and saved and loaded and must come back as saved.
public final class SettingsSuite implements Suite {

    private static final String NAME = "Settings";
    private static final WorldCreationUiState.SelectedGameMode CREATIVE =
        WorldCreationUiState.SelectedGameMode.CREATIVE;
    private static final String SURVIVAL = "survival";
    private static final String ALONE = "single player";
    private static final String SERVED = "server";
    private static final String ROUND_TRIP = "every setting survives a save and a load";
    // Modules whose work it is to leave the world. Leaving is no fault of theirs.
    private static final Set<String> LEAVERS = Set.of("AutoLog");
    private static final float HURRY = 5;
    private static final int HELD_TICKS = 2;
    private static final int SERVED_TICKS = 4;
    private static final int SMALL_WIDTH = 480;
    private static final int SMALL_HEIGHT = 270;
    private static final int MOST_TOLD = 6;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        List<Module> modules = rig.client().modules();
        List<Case> alone = cases(rig, modules, ALONE, null);
        boolean roundTrip = rig.wants(NAME, ROUND_TRIP);
        if (!alone.isEmpty() || roundTrip) {
            inSinglePlayer(rig, alone, roundTrip ? modules : null);
        }
        Duo[] server = new Duo[1];
        List<Case> served = cases(rig, modules, SERVED, server);
        if (!served.isEmpty()) {
            onServer(rig, served, server);
        }
    }

    private static List<Case> cases(Rig rig, List<Module> modules, String where, Duo[] server) {
        List<Case> cases = new ArrayList<>();
        for (Module module : modules) {
            String name = where + " " + module.getName();
            if (!rig.wants(NAME, name)) {
                continue;
            }
            String leftOut = SmokeSuite.leftOut(module.getName(), server != null);
            if (leftOut != null) {
                rig.skip(NAME, name, leftOut);
            } else if (server != null && !Duo.available()) {
                rig.skip(NAME, name, Duo.NO_SERVER);
            } else {
                cases.add(Case.of(name, each -> sweep(each, module, server == null ? null : server[0])));
            }
        }
        return cases;
    }

    private static void inSinglePlayer(Rig rig, List<Case> cases, List<Module> roundTrip) {
        int[] size = rig.ask(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        rig.openWorld(CREATIVE);
        // Nothing here is looked at. A small frame keeps the load on the machine low.
        rig.frames().drawAt(SMALL_WIDTH, SMALL_HEIGHT);
        rig.local().hurry(HURRY);
        Stage stage = new Stage(rig);
        try {
            for (Case each : cases) {
                if (!stage.worldStands()) {
                    stage.reopen(CREATIVE);
                    rig.local().hurry(HURRY);
                }
                stage.reset(true);
                rig.world().gameMode(SURVIVAL);
                rig.run(NAME, each);
            }
            if (roundTrip != null) {
                if (!stage.worldStands()) {
                    stage.reopen(CREATIVE);
                }
                stage.reset(false);
                rig.run(NAME, Case.of(ROUND_TRIP, each -> roundTrip(each, roundTrip)));
            }
        } finally {
            RigClock.set(RigClock.NORMAL);
            rig.frames().drawAt(size[0], size[1]);
            rig.closeWorld();
        }
    }

    private static void onServer(Rig rig, List<Case> cases, Duo[] server) {
        Duo duo = Duo.open(rig, PaperServer.Setup.plain());
        server[0] = duo;
        try {
            Stage stage = new Stage(rig);
            for (Case each : cases) {
                if (!stage.worldStands()) {
                    duo.rejoin();
                }
                stage.reset(true);
                duo.partnerHome();
                duo.server().resetTallies();
                rig.run(NAME, each);
            }
        } finally {
            duo.close();
        }
    }

    private static void sweep(Rig rig, Module module, Duo duo) {
        int mark = duo == null ? 0 : duo.server().logMark();
        boolean wasOn = rig.ask(mc -> module.isEnabled());
        if (module.isTogglable()) {
            rig.client().switchTo(module, true);
        }
        List<String> refused = new ArrayList<>();
        int tried = 0;
        List<Setting<?>> settings = rig.ask(mc -> List.copyOf(module.allSettings()));
        for (Setting<?> setting : settings) {
            for (Tweak tweak : Tweaks.of(setting, duo == null)) {
                String problem = rig.ask(mc -> tweak.apply().get());
                rig.ticks(duo == null ? HELD_TICKS : SERVED_TICKS);
                tried++;
                if (problem != null) {
                    refused.add(problem);
                }
                if (left(rig)) {
                    leftWith(rig, module, duo, mark, tweak.label());
                    return;
                }
            }
            rig.act(mc -> {
                if (setting.hasDefault()) {
                    setting.reset();
                }
            });
        }
        if (module.isTogglable()) {
            rig.client().switchTo(module, wasOn);
        }
        rig.ticks(HELD_TICKS);
        rig.note(tried + " values over " + settings.size() + " settings");
        rig.expect(refused.isEmpty(), "Some values did not take. " + refused);
        if (duo != null) {
            List<String> kicks = duo.server().allHeard("kick", mark);
            rig.expect(kicks.isEmpty(), "The server kicked " + kicks);
        }
    }

    private static boolean left(Rig rig) {
        return rig.ask(mc -> mc.player == null || mc.level == null);
    }

    private static void leftWith(Rig rig, Module module, Duo duo, int mark, String label) {
        rig.client().restoreDefaults();
        if (LEAVERS.contains(module.getName())) {
            rig.note("It left the world with " + label + " as it is made to");
            return;
        }
        String kicks = duo == null ? "" : " The server said " + duo.server().allHeard("kick", mark);
        throw new RigFailure("The world was lost with " + label + "." + kicks);
    }

    private static void roundTrip(Rig rig, List<Module> modules) {
        ConfigManager config = rig.ask(mc -> OfflineClient.INSTANCE.getConfigManager());
        Map<String, String> saved = rig.ask(mc -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (Module module : modules) {
                for (Setting<?> setting : module.allSettings()) {
                    Tweak change = setting.saves() ? Tweaks.change(setting) : null;
                    if (change != null) {
                        change.apply().get();
                        values.put(module.getName() + " " + setting.getName(), setting.getValueString());
                    }
                }
            }
            config.saveNow();
            return values;
        });
        rig.client().restoreDefaults();
        rig.ticks(HELD_TICKS);
        List<String> lost = rig.ask(mc -> {
            config.load();
            List<String> changed = new ArrayList<>();
            for (Module module : modules) {
                for (Setting<?> setting : module.allSettings()) {
                    String was = saved.get(module.getName() + " " + setting.getName());
                    if (was != null && !was.equals(setting.getValueString())) {
                        changed.add(module.getName() + " " + setting.getName() + " was saved as " + was
                            + " and came back as " + setting.getValueString());
                    }
                }
            }
            return changed;
        });
        rig.client().restoreDefaults();
        rig.act(mc -> config.saveNow());
        rig.note(saved.size() + " settings were changed and saved and loaded");
        rig.expect(lost.isEmpty(), lost.size() + " settings came back changed. "
            + lost.subList(0, Math.min(MOST_TOLD, lost.size())));
    }
}
