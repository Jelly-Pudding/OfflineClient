package com.jellypudding.offlineclient.rig.suite;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.ConfigManager;
import com.jellypudding.offlineclient.config.DataFiles;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Controls;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigClock;
import com.jellypudding.offlineclient.rig.core.RigFailure;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// What a player types and never meant to. Every command is given words it has never heard of
// and numbers below nought and far past what a number holds and each of its own words with
// nothing after it and with too much. The tab key is asked what comes next after each of
// them. Every setting is typed the same nonsense. Then every module runs with its numbers
// typed at ten times the top of the slider and at the least it takes. A typed value has no
// cap of its own and the game has to live with whatever goes in. A command may refuse and say
// why. Nothing may throw or leave the world or bring the game to a halt. The save file gets
// the same. It is cut in half and filled with values of the wrong kind and with numbers that
// are none and has letters changed at random. Whatever loads has to leave every setting sound.
// Last of all the click GUI and the HUD editor get a hand that knows nothing. It clicks and
// drags and scrolls and types wherever a seed sends it.
public final class TypedSuite implements Suite {

    private static final String NAME = "Typed";
    private static final String SURVIVAL = "survival";
    private static final WorldCreationUiState.SelectedGameMode CREATIVE =
        WorldCreationUiState.SelectedGameMode.CREATIVE;
    private static final String WORD = "zzqx";
    private static final String PAST_ANY_NUMBER = "99999999999999999999";
    private static final String MANY_WORDS = "zzqx zzqx zzqx zzqx zzqx zzqx";
    private static final List<String> NONSENSE = List.of(WORD, "-1", PAST_ANY_NUMBER, "1.5.3", "NaN", "1/0", "((((",
        "§k", MANY_WORDS, WORD.repeat(80));
    // What follows each of a command's own words.
    private static final List<String> TAILS = List.of("", WORD, "-1", PAST_ANY_NUMBER, MANY_WORDS);
    private static final List<String> SETTING_NONSENSE = List.of(WORD, "NaN", "Infinity", "-Infinity", "1e309",
        "-" + PAST_ANY_NUMBER, "§k", "0x10", "1.5.3");
    private static final Pattern USAGE_WORD = Pattern.compile("[a-z]+");
    // Left behind by a command that was typed with a word of its own. Each is put right after.
    private static final List<String> PUT_RIGHT = List.of("input clear", "fov reset", "gamemode real");

    private static final String SAVE = "config.json";
    private static final long FLIP_SEED = 20261115;
    private static final int FLIPPED_SAVES = 60;
    private static final int MOST_FLIPS = 3;
    private static final int MOST_TOLD = 6;
    private static final String HELD_FILE = "rig-held.json";
    private static final long HELD_MILLIS = 45;
    private static final int DRAWN_TICKS = 3;
    private static final String GUI_CODE = "com.jellypudding.offlineclient.gui.";
    // How a value that is no number shows in a row.
    private static final Pattern NO_NUMBER = Pattern.compile("\\bNaN\\b|Infinity");
    // A number that follows a key in the save as the game writes it.
    private static final String SAVED_NUMBER = "(?<=\":\\s{0,2})-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?";

    private static final List<String> GUI_STYLES = List.of("panels", "window");
    private static final long BLIND_SEED = 20261116;
    private static final int BLIND_STEPS = 600;
    private static final int BLIND_TOLD = 12;
    private static final List<Integer> BLIND_KEYS = List.of(InputConstants.KEY_A, InputConstants.KEY_Z,
        InputConstants.KEY_1, InputConstants.KEY_9, InputConstants.KEY_RETURN, InputConstants.KEY_BACKSPACE,
        InputConstants.KEY_TAB, InputConstants.KEY_DELETE, InputConstants.KEY_UP, InputConstants.KEY_DOWN,
        InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT, InputConstants.KEY_HOME, InputConstants.KEY_END,
        InputConstants.KEY_PAGEUP, InputConstants.KEY_PAGEDOWN, InputConstants.KEY_SPACE, InputConstants.KEY_ESCAPE);
    private static final List<String> BLIND_WORDS = List.of(WORD, "-1", PAST_ANY_NUMBER, "fly", "a", " ");
    private static final float FACING = 180;
    // It leaves the world once your health is under its line. Ten times the line is over any health.
    private static final Map<String, String> LEAVES_ON_PURPOSE = Map.of("AutoLog",
        "it leaves the world once your health is under its line and ten times the line is over any health");
    // Every hit is two packets and it sends as many as it is told to.
    private static final String FLOODS = "Kaboom";
    private static final float HURRY = 5;
    private static final int TIMES = 10;
    private static final int ON_TICKS = 10;
    // Ten ticks are a tenth of a second at the pace the suite runs at.
    private static final long HELD_UP_MILLIS = 2000;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        List<Module> modules = rig.client().modules();
        List<Command> commands = rig.ask(mc -> OfflineClient.INSTANCE.getCommandManager().getCommands());
        List<Case> cases = new ArrayList<>();
        for (Command command : commands) {
            String name = "The command " + command.getName() + " takes nonsense without a fault";
            String helpOnly = SmokeSuite.helpOnly(command.getName());
            if (helpOnly == null) {
                cases.add(Case.of(name, each -> nonsense(each, command)));
            } else if (rig.wants(NAME, name)) {
                rig.skip(NAME, name, "Left out because " + helpOnly);
            }
        }
        for (Category category : Category.values()) {
            List<Module> inCategory = modules.stream().filter(module -> module.getCategory() == category).toList();
            cases.add(Case.of("Every " + category.getDisplayName() + " setting takes nonsense typed at it",
                each -> settingsNonsense(each, inCategory)));
        }
        saves().forEach((name, spoils) -> cases.add(Case.of(name, each -> spoiltSave(each, spoils))));
        cases.add(Case.of("A save still lands whilst another program holds the old file for a moment",
            TypedSuite::heldSave));
        Stage[] standsOn = new Stage[1];
        for (Module module : modules) {
            boolean numbered = module.allSettings().stream().anyMatch(setting -> setting instanceof NumberSetting);
            if (!numbered || !module.isTogglable() || SmokeSuite.leftOut(module.getName(), false) != null) {
                continue;
            }
            String name = module.getName() + " runs with its numbers typed far past the slider";
            String leaves = LEAVES_ON_PURPOSE.get(module.getName());
            if (leaves == null) {
                cases.add(Case.of(name, each -> pastTheSlider(each, module, standsOn[0])));
            } else if (rig.wants(NAME, name)) {
                rig.skip(NAME, name, "Left out because " + leaves);
            }
        }
        // A fault in the code that draws a screen ends the game and with it the run. These go last.
        for (String style : GUI_STYLES) {
            cases.add(Case.of("The click GUI laid out as " + style + " lives through " + BLIND_STEPS
                + " clicks and keys at random", each -> blindHand(each, "gui", style)));
        }
        cases.add(Case.of("The HUD editor lives through " + BLIND_STEPS + " clicks and keys at random",
            each -> blindHand(each, "hud edit", GUI_STYLES.getFirst())));
        List<Case> wanted = rig.wanted(NAME, cases);
        if (wanted.isEmpty()) {
            return;
        }
        rig.openWorld(CREATIVE);
        rig.local().hurry(HURRY);
        Stage stage = new Stage(rig);
        standsOn[0] = stage;
        try {
            rig.world().gameMode(SURVIVAL);
            for (Case each : wanted) {
                stage.reset(true);
                rig.run(NAME, each);
                if (!stage.worldStands()) {
                    stage.reopen(CREATIVE);
                    rig.local().hurry(HURRY);
                    rig.world().gameMode(SURVIVAL);
                }
            }
        } finally {
            RigClock.set(RigClock.NORMAL);
            rig.closeWorld();
        }
    }

    private static void nonsense(Rig rig, Command command) {
        String name = command.getName();
        List<String> lines = lines(command);
        List<String> unanswered = new ArrayList<>();
        try {
            for (String line : lines) {
                String typed = (name + " " + line).trim();
                List<String> reply = rig.client().command(typed);
                Screen opened = rig.ask(mc -> mc.gui.screen());
                rig.act(mc -> mc.gui.setScreen(null));
                if (rig.ask(mc -> mc.player == null)) {
                    throw new RigFailure("The world was left after " + typed);
                }
                if (reply.isEmpty() && opened == null) {
                    unanswered.add(line);
                }
                // What the tab key offers in the middle of the last word and after it.
                rig.act(mc -> {
                    CommandManager manager = OfflineClient.INSTANCE.getCommandManager();
                    manager.complete(manager.getPrefix() + typed);
                    manager.complete(manager.getPrefix() + typed + " ");
                });
            }
        } finally {
            rig.act(mc -> OfflineClient.INSTANCE.getCommandManager().setPrefix(CommandManager.DEFAULT_PREFIX));
            if (rig.ask(mc -> mc.player != null)) {
                PUT_RIGHT.forEach(line -> rig.client().command(line));
            }
        }
        rig.note(lines.size() + " lines were typed and " + unanswered.size() + " got no answer " + unanswered);
        Stage.expectAlive(rig);
    }

    // The nonsense by itself and then behind each word the usage line of the command names.
    private static List<String> lines(Command command) {
        List<String> lines = new ArrayList<>(NONSENSE);
        Set<String> own = new LinkedHashSet<>();
        Matcher words = USAGE_WORD.matcher(command.getUsage());
        while (words.find()) {
            own.add(words.group());
        }
        own.remove(command.getName());
        for (String word : own) {
            for (String tail : TAILS) {
                lines.add((word + " " + tail).trim());
            }
        }
        return lines;
    }

    // The set command with nonsense for every setting of every module. A number may take a
    // number however far out. It may never end up as no number at all.
    private static void settingsNonsense(Rig rig, List<Module> modules) {
        List<String> broken = new ArrayList<>();
        int typed = 0;
        for (Module module : modules) {
            String id = Setting.idFor(module.getName());
            List<Setting<?>> settings = rig.ask(mc -> List.copyOf(module.allSettings()));
            typed += settings.size() * SETTING_NONSENSE.size();
            rig.act(mc -> {
                CommandManager manager = OfflineClient.INSTANCE.getCommandManager();
                for (Setting<?> setting : settings) {
                    for (String value : SETTING_NONSENSE) {
                        String line = "set " + id + " " + setting.id() + " " + value;
                        manager.run(manager.getPrefix() + line);
                        manager.complete(manager.getPrefix() + line);
                    }
                    if (setting instanceof NumberSetting number && !Double.isFinite(number.getValue())) {
                        broken.add(module.getName() + " " + setting.getName() + " is " + number.getValue());
                    }
                }
            });
            rig.ticks(1);
            rig.act(mc -> mc.gui.setScreen(null));
        }
        rig.client().restoreDefaults();
        rig.note(typed + " values were typed at " + modules.size() + " modules");
        rig.expect(broken.isEmpty(), "A number was left as no number. " + broken);
    }

    // The ways a save goes bad. A crash cuts one short and a hand that edits one leaves the
    // wrong kind of value behind or a number that is none.
    private static Map<String, List<UnaryOperator<String>>> saves() {
        Map<String, List<UnaryOperator<String>>> saves = new LinkedHashMap<>();
        saves.put("A save cut short or emptied or holding the wrong thing leaves every setting sound", List.of(
            text -> text.substring(0, text.length() / 2), text -> text.substring(0, text.length() - 2), text -> "",
            text -> "[]", text -> "null", text -> "{}", text -> "\"" + WORD + "\"", text -> "{\"modules\":7}"));
        saves.put("A save with every number turned into no number leaves every number sound", List.of(
            text -> text.replaceAll(SAVED_NUMBER, "NaN"), text -> text.replaceAll(SAVED_NUMBER, "Infinity"),
            text -> text.replaceAll(SAVED_NUMBER, "-Infinity"), text -> text.replaceAll(SAVED_NUMBER, "1e999"),
            text -> text.replaceAll(SAVED_NUMBER, "-" + PAST_ANY_NUMBER)));
        saves.put("A save with every value of the wrong kind leaves every setting sound", List.of(
            text -> everyValue(text, TypedSuite::wrongKind), text -> everyValue(text, value -> new JsonArray()),
            text -> everyValue(text, value -> new JsonObject()), text -> everyValue(text, value -> JsonNull.INSTANCE)));
        List<UnaryOperator<String>> flips = new ArrayList<>();
        Random random = new Random(FLIP_SEED);
        for (int save = 0; save < FLIPPED_SAVES; save++) {
            long seed = random.nextLong();
            flips.add(text -> flipped(text, seed));
        }
        saves.put("A save with letters changed here and there leaves every setting sound", flips);
        return saves;
    }

    // A number becomes a word and a switch a number and a word a switch.
    private static JsonElement wrongKind(JsonPrimitive value) {
        if (value.isNumber()) {
            return new JsonPrimitive(WORD);
        }
        return value.isBoolean() ? new JsonPrimitive(12345) : new JsonPrimitive(false);
    }

    // The save with every value that is no list and no object swapped for another.
    private static String everyValue(String text, Function<JsonPrimitive, JsonElement> swap) {
        return swapped(JsonParser.parseString(text), swap).toString();
    }

    private static JsonElement swapped(JsonElement element, Function<JsonPrimitive, JsonElement> swap) {
        if (element.isJsonObject()) {
            JsonObject object = new JsonObject();
            element.getAsJsonObject().asMap().forEach((key, value) -> object.add(key, swapped(value, swap)));
            return object;
        }
        if (element.isJsonArray()) {
            JsonArray array = new JsonArray();
            element.getAsJsonArray().forEach(value -> array.add(swapped(value, swap)));
            return array;
        }
        return element.isJsonPrimitive() ? swap.apply(element.getAsJsonPrimitive()) : element;
    }

    // Up to three letters of the save changed for others. The seed picks which.
    private static String flipped(String text, long seed) {
        Random random = new Random(seed);
        StringBuilder flipped = new StringBuilder(text);
        for (int flip = 1 + random.nextInt(MOST_FLIPS); flip > 0; flip--) {
            flipped.setCharAt(random.nextInt(flipped.length()), (char) (' ' + random.nextInt('~' - ' ')));
        }
        return flipped.toString();
    }

    // Writes each spoilt save where the good one lay and loads it. The good one goes back at
    // the end. What a spoilt save logs is its own doing and no fault of the client.
    private static void spoiltSave(Rig rig, List<UnaryOperator<String>> spoils) {
        ConfigManager config = rig.ask(mc -> OfflineClient.INSTANCE.getConfigManager());
        Path file = rig.ask(mc -> DataFiles.path(SAVE));
        List<Module> modules = rig.client().modules();
        rig.client().restoreDefaults();
        String good = rig.ask(mc -> {
            config.saveNow();
            return read(file);
        });
        rig.expect(good.length() > 1000, "The save holds next to nothing. That proves nothing");
        // A row that is a button holds nothing by design. Only what a load made unsound counts.
        List<String> asBuilt = rig.ask(mc -> unsound(modules));
        List<String> unsound = new ArrayList<>();
        try {
            for (int number = 0; number < spoils.size(); number++) {
                String spoilt = spoils.get(number).apply(good);
                int which = number + 1;
                rig.act(mc -> {
                    write(file, spoilt);
                    config.load();
                    unsound(modules).stream().filter(found -> !asBuilt.contains(found))
                        .forEach(found -> unsound.add("spoilt save " + which + " left " + found));
                });
                // The layout of the click GUI comes out of the same file and is read as it opens.
                rig.client().command("gui");
                rig.ticks(DRAWN_TICKS);
                boolean opened = rig.ask(mc -> mc.gui.screen() != null
                    && mc.gui.screen().getClass().getName().startsWith(GUI_CODE));
                rig.act(mc -> mc.gui.setScreen(null));
                if (!opened) {
                    unsound.add("spoilt save " + which + " left a click GUI that would not open");
                }
                rig.client().restoreDefaults();
            }
        } finally {
            rig.act(mc -> {
                write(file, good);
                config.load();
            });
            rig.client().restoreDefaults();
            rig.ticks(1);
            rig.log().skipToEnd();
        }
        rig.note(spoils.size() + " spoilt saves were loaded");
        rig.expect(unsound.isEmpty(), unsound.size() + " settings were left unsound. "
            + unsound.subList(0, Math.min(MOST_TOLD, unsound.size())));
    }

    // Windows lets nothing take the place of a file another program has open and a virus scanner
    // opens every file that was just written. The old file is held the same way here for a
    // moment and the save has to wait it out.
    private static void heldSave(Rig rig) {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            rig.note("Only Windows holds a file like that");
            return;
        }
        Path file = rig.ask(mc -> DataFiles.path(HELD_FILE));
        rig.expect(rig.ask(mc -> DataFiles.writeSafely(file, temp -> Files.writeString(temp, "first"))),
            "The file could not be saved even with nobody holding it");
        CountDownLatch held = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            // The old kind of stream holds a file on Windows the way another program does.
            try (InputStream open = new FileInputStream(file.toFile())) {
                held.countDown();
                Thread.sleep(HELD_MILLIS);
            } catch (IOException | InterruptedException e) {
                held.countDown();
            }
        }, "Rig file holder");
        holder.start();
        boolean saved;
        try {
            held.await();
            saved = rig.ask(mc -> DataFiles.writeSafely(file, temp -> Files.writeString(temp, "second")));
            holder.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RigFailure("The wait for the held file was cut short");
        }
        String holds = read(file);
        write(file, "");
        rig.expect(saved && holds.equals("second"), "The save did not land. The file holds " + holds);
    }

    // Settings that hold nothing or a number that is none or one past its own limits.
    private static List<String> unsound(List<Module> modules) {
        List<String> unsound = new ArrayList<>();
        for (Module module : modules) {
            for (Setting<?> setting : module.allSettings()) {
                String name = module.getName() + " " + setting.getName();
                if (setting.getValue() == null) {
                    unsound.add(name + " with nothing in it");
                } else if (setting instanceof NumberSetting number && !(Double.isFinite(number.getValue())
                    && number.getValue() >= number.getHardMin() && number.getValue() <= number.getHardMax())) {
                    unsound.add(name + " at " + number.getValue());
                } else if (NO_NUMBER.matcher(setting.getValueString()).find()) {
                    unsound.add(name + " showing " + setting.getValueString());
                }
            }
        }
        return unsound;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Opens a screen of the client with its own command and lets the seed loose on it. A screen
    // the hand closed is opened again and one that is not the client's own is shut. The last
    // things it did go with a failure and the seed plays the same hand every run.
    private static void blindHand(Rig rig, String opens, String style) {
        World world = rig.world();
        world.command("effect give " + world.player() + " resistance 600 4 true");
        rig.client().set("ClickGUI", "Style", style);
        Random random = new Random(BLIND_SEED);
        Deque<String> lately = new ArrayDeque<>();
        Controls hand = rig.controls();
        List<Module> modules = rig.client().modules();
        int[] touched = new int[1];
        try {
            for (int step = 0; step < BLIND_STEPS; step++) {
                if (rig.ask(mc -> mc.player == null)) {
                    throw new RigFailure("The world was left after " + lately);
                }
                boolean own = rig.ask(mc -> mc.gui.screen() != null
                    && mc.gui.screen().getClass().getName().startsWith(GUI_CODE));
                if (!own) {
                    rig.act(mc -> mc.gui.setScreen(null));
                    rig.client().command(opens);
                }
                int[] view = rig.ask(mc -> new int[] {mc.getWindow().getGuiScaledWidth(),
                    mc.getWindow().getGuiScaledHeight()});
                double x = random.nextDouble() * view[0];
                double y = random.nextDouble() * view[1];
                String did;
                switch (random.nextInt(8)) {
                    case 0, 1, 2 -> {
                        hand.pointer(x, y);
                        hand.click(InputConstants.MOUSE_BUTTON_LEFT);
                        did = String.format(Locale.ROOT, "left click at %.0f %.0f", x, y);
                    }
                    case 3 -> {
                        hand.pointer(x, y);
                        hand.click(InputConstants.MOUSE_BUTTON_RIGHT);
                        did = String.format(Locale.ROOT, "right click at %.0f %.0f", x, y);
                    }
                    case 4 -> {
                        hand.pointer(x, y);
                        double turn = random.nextInt(7) - 3;
                        hand.scroll(turn);
                        did = String.format(Locale.ROOT, "scroll by %.0f at %.0f %.0f", turn, x, y);
                    }
                    case 5 -> {
                        double toX = random.nextDouble() * view[0];
                        double toY = random.nextDouble() * view[1];
                        hand.pointer(x, y);
                        hand.holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
                        rig.ticks(1);
                        hand.pointer(toX, toY);
                        rig.ticks(1);
                        hand.releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
                        did = String.format(Locale.ROOT, "drag from %.0f %.0f to %.0f %.0f", x, y, toX, toY);
                    }
                    case 6 -> {
                        int key = BLIND_KEYS.get(random.nextInt(BLIND_KEYS.size()));
                        hand.press(key);
                        did = "key " + key;
                    }
                    default -> {
                        String text = BLIND_WORDS.get(random.nextInt(BLIND_WORDS.size()));
                        hand.type(text);
                        did = "typed " + text;
                    }
                }
                lately.addLast(did);
                if (lately.size() > BLIND_TOLD) {
                    lately.removeFirst();
                }
                rig.ticks(1);
            }
        } finally {
            if (rig.ask(mc -> mc.player != null)) {
                rig.act(mc -> {
                    mc.gui.setScreen(null);
                    touched[0] = touched(modules);
                });
                rig.client().restoreDefaults();
                rig.act(mc -> {
                    OfflineClient.INSTANCE.getConfigManager().resetGuiLayout();
                    OfflineClient.INSTANCE.getCommandManager().setPrefix(CommandManager.DEFAULT_PREFIX);
                });
                PUT_RIGHT.forEach(line -> rig.client().command(line));
            }
        }
        Stage.expectAlive(rig);
        rig.note("The hand left " + touched[0] + " switches and settings changed. The last things it did were "
            + lately);
        rig.expect(touched[0] > 0, "The hand changed nothing at all. That proves nothing");
    }

    // Modules that are not as they start and settings that are not at their defaults.
    private static int touched(List<Module> modules) {
        int touched = 0;
        for (Module module : modules) {
            touched += module.isTogglable() && module.isEnabled() != module.enabledByDefault() ? 1 : 0;
            for (Setting<?> setting : module.allSettings()) {
                touched += setting.hasDefault() && !setting.isDefault() ? 1 : 0;
            }
        }
        return touched;
    }

    // Every number of the module at ten times the top of its slider and then at the least it
    // takes. The player cannot be hurt meanwhile. A single player world takes every move its
    // host sends. A module that counts on a move being refused can leave the player far from
    // home and each go ends with the way back.
    private static void pastTheSlider(Rig rig, Module module, Stage stage) {
        World world = rig.world();
        BlockPos home = stage.home();
        Vec3 start = Vec3.atBottomCenterOf(home);
        world.command("effect give " + world.player() + " resistance 600 4 true");
        List<String> heldUp = new ArrayList<>();
        for (boolean high : List.of(true, false)) {
            String where = high ? TIMES + " times the slider" : "the least they take";
            rig.act(mc -> {
                for (Setting<?> setting : module.allSettings()) {
                    if (setting instanceof NumberSetting number) {
                        number.setValue(high ? Math.min(number.getHardMax(), number.getSliderMax() * TIMES)
                            : number.getHardMin());
                    }
                }
            });
            long began = System.nanoTime();
            rig.client().switchTo(module, true);
            rig.ticks(ON_TICKS);
            rig.client().switchTo(module, false);
            rig.act(mc -> mc.gui.setScreen(null));
            long millis = (System.nanoTime() - began) / 1_000_000;
            rig.note("With its numbers at " + where + " ten ticks took " + millis + " ms");
            if (rig.ask(mc -> mc.player == null)) {
                throw new RigFailure("The world was left with the numbers at " + where);
            }
            if (millis > HELD_UP_MILLIS) {
                heldUp.add(where + " for " + millis + " ms");
            }
            Stage.expectAlive(rig);
            if (world.where().distanceTo(start) > 1) {
                world.teleport(start.x, start.y, start.z, FACING, 0);
            }
        }
        rig.expect(heldUp.isEmpty() || module.getName().equals(FLOODS), module.getName()
            + " held the game up with its numbers at " + heldUp);
    }
}
