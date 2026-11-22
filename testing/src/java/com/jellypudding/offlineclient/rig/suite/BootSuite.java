package com.jellypudding.offlineclient.rig.suite;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.rig.RigConfig;
import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.LogFile;
import com.jellypudding.offlineclient.rig.core.LogRules;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigFailure;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.rig.duo.Duo;
import com.jellypudding.offlineclient.rig.duo.PaperServer;
import com.jellypudding.offlineclient.setting.Setting;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// The game starts with the client in it and nothing went wrong on the way to the title screen.
public final class BootSuite implements Suite {

    private static final String NAME = "Boot";
    private static final String CLIENT_MOD = "offlineclient";
    private static final String MIXIN_CONFIG = "offlineclient.mixins.json";
    private static final String LOG_FILE = "logs/latest.log";
    private static final int TITLE_TICKS = 600;
    private static final int FADE_TICKS = 40;
    private static final int MOST_TOLD = 6;
    private static final String HEARD_ALONE = "the game has heard its own server before a case looks";
    private static final String HEARD_SERVED = "the game has heard a Paper server before a case looks";
    private static final int HEARD_ROUNDS = 20;
    private static final List<String> HEARD_BLOCKS = List.of("stone", "glass");
    // No game draws this one out when it is taken away.
    private static final String HEARD_ENTITY = "block_display";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        List<Case> cases = new ArrayList<>(List.of(
            Case.of("the game reaches the title screen", BootSuite::title),
            Case.of("the game makes no sound", BootSuite::silent),
            Case.of("every mixin of the client applied", BootSuite::mixins),
            Case.of("the client registered its modules commands and HUD pieces", BootSuite::registered),
            Case.of("the log holds no fault of the client", BootSuite::cleanLog)));
        if (RigConfig.ownerData()) {
            cases.add(Case.of("the owner's settings load without an error", BootSuite::ownerSettings));
        }
        for (Case each : rig.wanted(NAME, cases)) {
            rig.run(NAME, each);
        }
        if (rig.wants(NAME, HEARD_ALONE)) {
            rig.openWorld(WorldCreationUiState.SelectedGameMode.CREATIVE);
            try {
                rig.run(NAME, Case.of(HEARD_ALONE, BootSuite::heard));
            } finally {
                rig.closeWorld();
            }
        }
        if (!rig.wants(NAME, HEARD_SERVED)) {
            return;
        }
        if (!Duo.available()) {
            rig.skip(NAME, HEARD_SERVED, Duo.NO_SERVER);
            return;
        }
        Duo duo = Duo.alone(rig, PaperServer.Setup.plain());
        try {
            rig.run(NAME, Case.of(HEARD_SERVED, BootSuite::heard));
        } finally {
            duo.close();
        }
    }

    // Every case leans on this. A server sends what it changed a tick later and a look that came
    // at once would see the world from before.
    private static void heard(Rig rig) {
        World world = rig.world();
        BlockPos spot = rig.ask(mc -> mc.player.blockPosition()).offset(2, 1, 0);
        for (int round = 0; round < HEARD_ROUNDS; round++) {
            String block = HEARD_BLOCKS.get(round % HEARD_BLOCKS.size());
            world.setBlock(spot, block);
            String seen = rig.ask(mc -> BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(spot).getBlock())
                .getPath());
            rig.expect(seen.equals(block), "The game held " + seen + " where the server had put " + block
                + " in round " + round);
            world.command("summon " + HEARD_ENTITY + " " + World.at(spot.above()));
            rig.expect(displays(rig) == 1, "The game did not hold what the server summoned in round " + round);
            world.command("kill @e[type=" + HEARD_ENTITY + "]");
            rig.expect(displays(rig) == 0, "The game still held what the server took away in round " + round);
        }
        world.setBlock(spot, "air");
        rig.note("The game had heard of " + HEARD_ROUNDS + " blocks and as many entities made and taken away");
    }

    private static int displays(Rig rig) {
        return rig.ask(mc -> {
            int found = 0;
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity.getType() == EntityTypes.BLOCK_DISPLAY) {
                    found++;
                }
            }
            return found;
        });
    }

    private static void title(Rig rig) {
        int waited = rig.until("the title screen", TITLE_TICKS, mc -> mc.gui.screen() instanceof TitleScreen);
        rig.ticks(FADE_TICKS);
        rig.controls().parkPointer();
        rig.frames().capture("title");
        rig.note("The title screen came " + waited + " ticks after the tests began");
    }

    private static void silent(Rig rig) {
        List<String> loud = rig.ask(mc -> {
            List<String> heard = new ArrayList<>();
            for (SoundSource source : SoundSource.values()) {
                double volume = mc.options.getSoundSourceOptionInstance(source).get();
                if (volume != 0) {
                    heard.add(source.getName() + " at " + volume);
                }
            }
            return heard;
        });
        rig.expect(loud.isEmpty(), "These sound sliders are not at nought " + loud);
    }

    // The mixin library keeps for every mixin the classes it went into. A mixin listed in the
    // client's config that went into none did not apply.
    private static void mixins(Rig rig) throws Exception {
        ModContainer mod = FabricLoader.getInstance().getModContainer(CLIENT_MOD).orElseThrow();
        Path file = mod.findPath(MIXIN_CONFIG).orElseThrow();
        JsonObject config = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        String home = config.get("package").getAsString();
        List<String> listed = new ArrayList<>();
        for (String list : List.of("mixins", "client")) {
            JsonArray names = config.has(list) ? config.getAsJsonArray(list) : new JsonArray();
            for (JsonElement name : names) {
                listed.add(name.getAsString());
            }
        }
        // Loads every class a mixin aims at. The mixins that can apply have applied after this.
        rig.act(mc -> MixinEnvironment.getCurrentEnvironment().audit());
        List<String> missing = new ArrayList<>();
        for (String name : listed) {
            ClassInfo info = ClassInfo.forName((home + "." + name).replace('.', '/'));
            if (info == null || info.getAppliedMixins().isEmpty()) {
                missing.add(name);
            }
        }
        rig.note(listed.size() + " mixins are listed and " + (listed.size() - missing.size()) + " applied");
        if (missing.isEmpty()) {
            return;
        }
        throw new RigFailure("These mixins did not apply " + missing + reasons(rig, missing));
    }

    private static String reasons(Rig rig, List<String> missing) {
        StringBuilder reasons = new StringBuilder();
        for (LogFile.Entry entry : wholeLog(rig)) {
            if (entry.isError() || entry.isWarning()) {
                for (String name : missing) {
                    if (entry.text().contains(name)) {
                        reasons.append(" | ").append(entry.firstLine());
                        break;
                    }
                }
            }
        }
        return reasons.toString();
    }

    private static List<LogFile.Entry> wholeLog(Rig rig) {
        Path folder = rig.ask(mc -> mc.gameDirectory.toPath());
        return new LogFile(folder.resolve(LOG_FILE)).readNew();
    }

    private static void registered(Rig rig) {
        List<String> problems = rig.ask(mc -> {
            List<String> found = new ArrayList<>();
            List<Module> modules = OfflineClient.INSTANCE.getModuleManager().getAll();
            Set<String> names = new HashSet<>();
            for (Module module : modules) {
                if (!names.add(Setting.idFor(module.getName()))) {
                    found.add("two modules are called " + module.getName());
                }
                if (OfflineClient.INSTANCE.getModuleManager().get(module.getName()) != module) {
                    found.add("the module " + module.getName() + " is not found by its name");
                }
                if (module.getDescription() == null || module.getDescription().isBlank()) {
                    found.add("the module " + module.getName() + " has no description");
                }
                Set<String> settings = new HashSet<>();
                for (Setting<?> setting : module.playerSettings()) {
                    if (!settings.add(setting.id())) {
                        found.add(module.getName() + " has two settings typed as " + setting.id());
                    }
                }
            }
            Set<String> commandNames = new HashSet<>();
            List<Command> commands = OfflineClient.INSTANCE.getCommandManager().getCommands();
            for (Command command : commands) {
                if (!commandNames.add(command.getName())) {
                    found.add("two commands are called " + command.getName());
                }
                if (OfflineClient.INSTANCE.getCommandManager().find(command.getName()) != command) {
                    found.add("the command " + command.getName() + " is not found by its name");
                }
            }
            List<HudElement> pieces = OfflineClient.INSTANCE.getModuleManager().get(HudModule.class).getManager().all();
            Set<String> pieceNames = new HashSet<>();
            for (HudElement piece : pieces) {
                if (!pieceNames.add(piece.getName())) {
                    found.add("two HUD pieces are called " + piece.getName());
                }
            }
            if (modules.isEmpty() || commands.isEmpty() || pieces.isEmpty()) {
                found.add("a list is empty");
            }
            found.addFirst(modules.size() + " modules and " + commands.size() + " commands and " + pieces.size()
                + " HUD pieces");
            return found;
        });
        rig.note(problems.removeFirst());
        rig.expect(problems.isEmpty(), "The lists of the client are not sound " + problems);
    }

    private static void cleanLog(Rig rig) {
        List<LogFile.Entry> entries = wholeLog(rig);
        List<LogRules.Fault> faults = rig.rules().faults(entries);
        long warnings = entries.stream().filter(LogRules::clientWarning).count();
        rig.note("The log holds " + entries.size() + " entries up to here and " + warnings
            + " warnings of the client");
        if (faults.isEmpty()) {
            return;
        }
        List<String> told = faults.stream().limit(MOST_TOLD).map(LogRules.Fault::brief).toList();
        throw new RigFailure(faults.size() + " faults in the log " + told);
    }

    // The run began with a copy of the owner's own data folder. Loading it may log nothing bad.
    private static void ownerSettings(Rig rig) {
        List<String> bad = new ArrayList<>();
        for (LogFile.Entry entry : wholeLog(rig)) {
            if (rig.rules().judge(entry) != null || LogRules.clientWarning(entry)) {
                bad.add(entry.brief());
            }
        }
        int changed = rig.ask(mc -> {
            int count = 0;
            for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
                for (Setting<?> setting : module.getSettings()) {
                    count += setting.isChanged() ? 1 : 0;
                }
            }
            return count;
        });
        int switchedOn = rig.ask(mc -> OfflineClient.INSTANCE.getModuleManager().getEnabled().size());
        rig.note(changed + " settings differ from their defaults and " + switchedOn + " modules are on");
        rig.expect(changed > 0, "No setting differs from its default. The copy of the settings was not read");
        rig.expect(bad.isEmpty(), "Loading the settings logged " + bad);
    }
}
