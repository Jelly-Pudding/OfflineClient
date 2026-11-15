package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.DataFiles;
import com.jellypudding.offlineclient.gui.InspectorScreen;
import com.jellypudding.offlineclient.gui.WorldMapScreen;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.Privacy;
import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BeaconScreen;
import net.minecraft.client.telemetry.TelemetryEventSender;
import net.minecraft.client.telemetry.TelemetryEventType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

// One case for each module that changes what the game shows or says or does by itself. What
// the screen writes is read back and what the server holds is asked for.
public final class MiscSuite implements Suite {

    private static final String NAME = "Misc";
    private static final int FORWARD = InputConstants.KEY_W;
    private static final int USE = InputConstants.MOUSE_BUTTON_RIGHT;
    private static final int ATTACK = InputConstants.MOUSE_BUTTON_LEFT;
    private static final float NORTH = 180;
    private static final String CROWD = "{NoGravity:1b,Marker:1b}";
    // One armour stand doubled this often is a crowd of 256.
    private static final int DOUBLINGS = 8;
    private static final int ENTITY_CAP = 100;
    // TNT this high over the stage breaks nothing.
    private static final int BLAST_HEIGHT = 10;
    private static final int ACT_TICKS = 100;
    // A beacon counts its pyramid every four seconds.
    private static final int BEACON_TICKS = 100;
    // Twice what AntiPacketKick lets through in a second.
    private static final int BURST = 600;
    private static final int WALK_TICKS = 30;
    private static final int HELD_BACK = 20;
    private static final int NO_CONTENT = 204;
    private static final Pattern CLOCK = Pattern.compile("\\b\\d{1,2}:\\d{2}\\b");
    private static final String STILL_MOB = "{NoAI:1b,PersistenceRequired:1b,IsBaby:0b}";

    private static final int SEEN_TICKS = 10;
    private static final String SONG = "rigsong";
    private static final List<Integer> SONG_NOTES = List.of(3, 7, 12);
    private static final int SONG_GAP = 2;
    private static final int SONG_TICKS = 600;
    private static final int CHAT_TICKS = 15;
    private static final int IDLE_TICKS = 300;
    private static final int BOOK_TICKS = 300;
    private static final int RAIN_TICKS = 300;

    private BlockPos home;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        Grounds.play(rig, NAME, plays(), stage -> home = stage.home());
    }

    private Map<String, Case.Body> plays() {
        Map<String, Case.Body> plays = new LinkedHashMap<>();
        plays.put("FakePlayer puts a copy of you in the world", this::fakePlayer);
        plays.put("Panic switches every module off", this::panic);
        plays.put("ModuleLock keeps a locked module off", this::moduleLock);
        plays.put("Conceal hides what the client draws", this::conceal);
        plays.put("NameProtect shows another name for you in chat", this::nameProtect);
        plays.put("BetterChat stamps the time on chat", this::betterChat);
        plays.put("Inspector opens its sheet on the mob you look at", this::inspector);
        plays.put("PacketLogger writes the packets you pick to chat", this::packetLogger);
        plays.put("BookBot writes and signs the book in your hand", this::bookBot);
        plays.put("AntiAFK jumps whilst you stand idle", this::antiAfk);
        plays.put("NoStatusEffects hides an effect the server keeps", this::noStatusEffects);
        plays.put("Weather clears the rain you see", this::weather);
        plays.put("WorldMap opens its map on the Map key", this::worldMap);
        plays.put("BossStack folds two boss bars of one name into one", this::bossStack);
        plays.put("TabGUI switches a module on with the arrow keys", this::tabGui);
        plays.put("SoundBlocker mutes the sound on its list", this::soundBlocker);
        plays.put("ColourCodes colours the sign you write", this::colourCodes);
        plays.put("AntiCrash keeps a crowd of entities under its cap", this::antiCrash);
        plays.put("AntiCrash lets a plain blast pass without a word", this::plainBlast);
        plays.put("PacketCanceller drops the server's chat lines", this::packetCanceller);
        plays.put("Triggers switches a module on once you are badly hurt and off again", this::triggers);
        plays.put("Webhook posts its test line and a line when you are hurt", this::webhook);
        plays.put("BetterBeacons offers every effect on a small pyramid", this::betterBeacons);
        plays.put("AntiPacketKick holds a burst of packets back", this::antiPacketKick);
        plays.put("Latency keeps the server behind your steps", this::latency);
        plays.put("Playlist says why it stays quiet with the sound off", this::playlist);
        plays.put("Confetti strikes your kill with a bolt only you see", this::confetti);
        plays.put("Privacy stops a usage report at its gate and lets it by once switched off", this::privacy);
        plays.put("Notebot tunes the note blocks round you and plays its song to the end", this::notebot);
        return plays;
    }

    private void fakePlayer(Rig rig) {
        int alone = players(rig);
        rig.client().enable("FakePlayer");
        rig.ticks(SEEN_TICKS);
        int copied = players(rig);
        rig.client().disable("FakePlayer");
        rig.ticks(SEEN_TICKS);
        rig.expect(copied == alone + 1, "The world holds " + copied + " players with FakePlayer on and held " + alone);
        rig.expect(players(rig) == alone, "The copy stayed after FakePlayer went off");
    }

    private static int players(Rig rig) {
        return rig.ask(mc -> mc.level.players().size());
    }

    private void panic(Rig rig) {
        rig.client().enable("Fullbright");
        rig.client().enable("Speed");
        rig.client().enable("Panic");
        rig.ticks(SEEN_TICKS);
        rig.expect(!rig.client().isOn("Fullbright") && !rig.client().isOn("Speed"), "A module stayed on after Panic");
    }

    private void moduleLock(Rig rig) {
        Module lock = rig.client().module("ModuleLock");
        rig.act(mc -> ((ChoiceListSetting) lock.getSetting("Locked modules")).add("Speed"));
        rig.client().enable("ModuleLock");
        rig.client().enable("Speed");
        rig.ticks(SEEN_TICKS);
        boolean locked = !rig.client().isOn("Speed");
        rig.client().disable("ModuleLock");
        rig.client().enable("Speed");
        rig.ticks(SEEN_TICKS);
        rig.expect(locked, "A locked module went on");
        rig.expect(rig.client().isOn("Speed"), "The module stayed locked after ModuleLock went off");
    }

    // The module list on the screen names Speed once it is on.
    private void conceal(Rig rig) {
        rig.client().enable("Speed");
        rig.ticks(SEEN_TICKS);
        // A line of chat from an earlier case may name Speed too.
        rig.act(mc -> mc.gui.hud.getChat().clearMessages(true));
        boolean shown = writes(rig, "conceal off", "Speed");
        rig.client().enable("Conceal");
        rig.ticks(SEEN_TICKS);
        boolean hidden = !writes(rig, "conceal on", "Speed");
        rig.expect(shown, "The screen did not name Speed without Conceal. That proves nothing");
        rig.expect(hidden, "The screen still names Speed");
    }

    private static boolean writes(Rig rig, String shot, String words) {
        return rig.frames().capture(shot).texts().stream().anyMatch(text -> text.contains(words));
    }

    private void nameProtect(Rig rig) {
        String me = rig.world().player();
        String plain = chatLine(rig, "first hello");
        rig.client().enable("NameProtect");
        String guarded = chatLine(rig, "second hello");
        rig.note("The chat showed " + plain + " and then " + guarded);
        rig.expect(plain.contains(me), "The chat did not show the real name without NameProtect. That proves nothing");
        rig.expect(!guarded.isEmpty() && !guarded.contains(me), "The chat still shows the real name");
    }

    // Says the words and hands back the line of chat the screen then shows them in.
    private static String chatLine(Rig rig, String words) {
        said(rig, words);
        for (String text : rig.frames().capture("chat " + words).texts()) {
            if (text.contains(words)) {
                return text;
            }
        }
        return "";
    }

    private static void said(Rig rig, String words) {
        rig.act(mc -> mc.gui.hud.getChat().clearMessages(true));
        rig.client().say(words);
        rig.ticks(CHAT_TICKS);
    }

    private void betterChat(Rig rig) {
        rig.client().disable("BetterChat");
        said(rig, "stamp one");
        boolean bare = clockShown(rig, "chat bare");
        rig.client().enable("BetterChat");
        said(rig, "stamp two");
        boolean stamped = clockShown(rig, "chat stamped");
        rig.expect(!bare, "A time of day was on the screen without BetterChat. That proves nothing");
        rig.expect(stamped, "No time of day stands by the chat line");
    }

    private static boolean clockShown(Rig rig, String shot) {
        return rig.frames().capture(shot).texts().stream().anyMatch(text -> CLOCK.matcher(text).find());
    }

    private void inspector(Rig rig) {
        Props.summon(rig, "husk", home.north(3), STILL_MOB);
        rig.ticks(SEEN_TICKS);
        rig.client().press("Inspector");
        rig.ticks(SEEN_TICKS);
        boolean opened = rig.ask(mc -> mc.gui.screen() instanceof InspectorScreen);
        rig.act(mc -> mc.gui.setScreen(null));
        rig.expect(opened, "No sheet opened for the mob under the crosshair");
    }

    private void packetLogger(Rig rig) {
        Module logger = rig.client().module("PacketLogger");
        rig.act(mc -> {
            ChoiceListSetting outgoing = (ChoiceListSetting) logger.getSetting("Outgoing");
            outgoing.addAll(outgoing.options());
        });
        int mark = rig.client().chatMark();
        rig.client().enable("PacketLogger");
        Motion.hold(rig, SEEN_TICKS, FORWARD);
        rig.client().disable("PacketLogger");
        List<String> said = rig.client().saidSince(mark);
        rig.note("It wrote " + said.size() + " lines");
        rig.expect(!said.isEmpty(), "No packet was written to chat");
    }

    private void bookBot(Rig rig) {
        Props.hold(rig, 0, "writable_book");
        rig.client().enable("BookBot");
        int took = Props.await(rig, "a signed book in the hand", BOOK_TICKS,
            () -> Props.holds(rig, "weapon.mainhand", "written_book"));
        rig.note("The book was signed after about " + took + " ticks");
    }

    private void antiAfk(Rig rig) {
        rig.client().enable("AntiAFK");
        int took = rig.until("a jump with no key held", IDLE_TICKS, mc -> mc.player.getY() > home.getY() + 0.2);
        rig.ticks(SEEN_TICKS);
        rig.note("It jumped after " + took + " ticks of standing idle");
    }

    private void noStatusEffects(Rig rig) {
        World world = rig.world();
        String player = world.player();
        rig.client().enable("NoStatusEffects");
        world.command("effect give " + player + " jump_boost 60 5 true");
        rig.ticks(SEEN_TICKS);
        boolean onServer = world.attempt("execute if entity @a[name=" + player
            + ",nbt={active_effects:[{id:\"minecraft:jump_boost\"}]}]");
        boolean inGame = rig.ask(mc -> mc.player.hasEffect(MobEffects.JUMP_BOOST));
        rig.expect(onServer, "The server does not hold the effect. That proves nothing");
        rig.expect(!inGame, "The game still has the effect on the player");
    }

    private void weather(Rig rig) {
        World world = rig.world();
        world.command("weather rain");
        try {
            rig.until("the rain to come", RAIN_TICKS, mc -> mc.level.getRainLevel(1) > 0.5f);
            rig.client().enable("Weather");
            rig.ticks(SEEN_TICKS * 2);
            float rain = rig.ask(mc -> mc.level.getRainLevel(1));
            rig.expect(rain == 0, "The game still shows rain at a strength of " + rain);
        } finally {
            world.attempt("weather clear");
        }
    }

    private void worldMap(Rig rig) {
        rig.client().enable("WorldMap");
        rig.ticks(SEEN_TICKS);
        rig.controls().press(InputConstants.KEY_M);
        rig.ticks(SEEN_TICKS);
        boolean opened = rig.ask(mc -> mc.gui.screen() instanceof WorldMapScreen);
        rig.act(mc -> mc.gui.setScreen(null));
        rig.expect(opened, "The Map key opened no map");
    }

    private void bossStack(Rig rig) {
        World world = rig.world();
        String player = world.player();
        for (String bar : List.of("rig:one", "rig:two")) {
            world.command("bossbar add " + bar + " \"Same bar\"");
            world.command("bossbar set " + bar + " players " + player);
        }
        try {
            rig.ticks(SEEN_TICKS);
            long plain = named(rig, "boss bars plain");
            rig.client().enable("BossStack");
            rig.ticks(SEEN_TICKS);
            long stacked = named(rig, "boss bars stacked");
            rig.expect(plain == 2, "The screen named " + plain + " bars without BossStack. That proves nothing");
            rig.expect(stacked == 1, "The screen still names " + stacked + " bars");
        } finally {
            world.attempt("bossbar remove rig:one");
            world.attempt("bossbar remove rig:two");
        }
    }

    private static long named(Rig rig, String shot) {
        return rig.frames().capture(shot).texts().stream().filter(text -> text.startsWith("Same bar")).count();
    }

    private void tabGui(Rig rig) {
        Module target = rig.client().module("Fullbright");
        int category = target.getCategory().ordinal();
        int row = rig.ask(mc -> OfflineClient.INSTANCE.getModuleManager().getByCategory(target.getCategory())
            .indexOf(target));
        rig.client().enable("TabGUI");
        rig.ticks(SEEN_TICKS);
        boolean listed = rig.frames().capture("tab gui").shows(target.getCategory().getDisplayName());
        tap(rig, InputConstants.KEY_DOWN, category);
        tap(rig, InputConstants.KEY_RIGHT, 1);
        tap(rig, InputConstants.KEY_DOWN, row);
        tap(rig, InputConstants.KEY_RIGHT, 1);
        rig.expect(listed, "The list of categories is not on the screen");
        rig.expect(rig.client().isOn("Fullbright"), "The arrow keys did not switch Fullbright on");
    }

    private static void tap(Rig rig, int key, int times) {
        for (int i = 0; i < times; i++) {
            rig.controls().press(key);
            rig.ticks(1);
        }
    }

    // The module counts what it mutes beside its name.
    private void soundBlocker(Rig rig) {
        World world = rig.world();
        rig.client().enable("SoundBlocker");
        world.command("playsound minecraft:entity.pig.ambient master " + world.player());
        rig.ticks(SEEN_TICKS);
        String spared = muted(rig);
        world.command("playsound minecraft:entity.generic.explode master " + world.player());
        rig.ticks(SEEN_TICKS);
        String blocked = muted(rig);
        rig.note("It says " + spared + " after a sound it leaves alone and " + blocked + " after one on its list");
        rig.expect(!spared.startsWith("1"), "A sound that is not on the list was muted");
        rig.expect(blocked.startsWith("1"), "The sound on the list was not muted");
    }

    private static String muted(Rig rig) {
        Module blocker = rig.client().module("SoundBlocker");
        return rig.ask(mc -> String.valueOf(blocker.getSuffix()));
    }

    // The server takes every code out of a sign line. The module writes them to outlast that.
    private void colourCodes(Rig rig) {
        BlockPos spot = home.north(2);
        rig.client().enable("ColourCodes");
        Props.hold(rig, 0, "oak_sign");
        rig.controls().aimAtBlock(spot.below(), Direction.UP);
        rig.ticks(2);
        rig.controls().click(USE);
        rig.until("the sign's screen to open", SEEN_TICKS * 4, mc -> mc.gui.screen() instanceof AbstractSignEditScreen);
        rig.controls().type("&cred");
        rig.ticks(2);
        rig.controls().press(InputConstants.KEY_ESCAPE);
        rig.ticks(SEEN_TICKS * 2);
        String line = rig.ask(mc -> mc.level.getBlockEntity(spot) instanceof SignBlockEntity sign
            ? sign.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString() : "no sign");
        rig.note("The sign reads " + line.replace(ChatFormatting.PREFIX_CODE, '&'));
        rig.expect(line.contains(ChatFormatting.RED.toString()), "The sign holds no colour code for red");
        rig.expect(!line.contains("&"), "The sign still holds the code as it was typed");
    }

    private void antiCrash(Rig rig) {
        World world = rig.world();
        rig.client().set("AntiCrash", "Entity cap", String.valueOf(ENTITY_CAP));
        rig.client().set("AntiCrash", "Notify", "on");
        rig.client().enable("AntiCrash");
        int mark = rig.client().chatMark();
        Props.summon(rig, "armor_stand", home.north(4), CROWD);
        for (int doubling = 0; doubling < DOUBLINGS; doubling++) {
            world.command("execute as @e[type=armor_stand] at @s run summon armor_stand ~ ~ ~ " + CROWD);
        }
        rig.ticks(SEEN_TICKS * 2);
        int held = Props.living(rig, "armor_stand");
        int seen = rig.ask(mc -> mc.level.getEntityCount());
        List<String> said = rig.client().saidSince(mark);
        rig.note("The server holds " + held + " armour stands and the game " + seen + " entities");
        rig.expect(held == 1 << DOUBLINGS, "The server holds " + held + " armour stands. That proves nothing");
        rig.expect(seen <= ENTITY_CAP, "The game holds " + seen + " entities past a cap of " + ENTITY_CAP);
        rig.expect(said.stream().anyMatch(line -> line.contains("cap")), "Nothing in chat said the cap dropped "
            + "anything");
    }

    private void packetCanceller(Rig rig) {
        Module canceller = rig.client().module("PacketCanceller");
        rig.act(mc -> {
            ChoiceListSetting incoming = (ChoiceListSetting) canceller.getSetting("Incoming");
            incoming.add(incoming.options().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "").contains("systemchat"))
                .findFirst().orElseThrow());
        });
        boolean heard = hears(rig, "first line");
        rig.client().enable("PacketCanceller");
        boolean dropped = !hears(rig, "second line");
        rig.expect(heard, "A line from the server did not reach chat without PacketCanceller. That proves nothing");
        rig.expect(dropped, "A line from the server still reached chat");
    }

    // Has the server say the words to the player and says whether they reached chat.
    private static boolean hears(Rig rig, String words) {
        World world = rig.world();
        rig.act(mc -> mc.gui.hud.getChat().clearMessages(true));
        world.command("tellraw " + world.player() + " \"" + words + "\"");
        rig.ticks(CHAT_TICKS);
        return writes(rig, "heard " + words, words);
    }

    // A plain server counts the air a blast went through and sends thousands of blocks for one.
    private void plainBlast(Rig rig) {
        World world = rig.world();
        world.gameMode("creative");
        rig.client().set("AntiCrash", "Notify", "on");
        rig.client().enable("AntiCrash");
        int mark = rig.client().chatMark();
        world.command("summon tnt " + Props.at(home.above(BLAST_HEIGHT)) + " {fuse:0}");
        rig.ticks(CHAT_TICKS * 2);
        List<String> said = rig.client().saidSince(mark);
        rig.expect(said.isEmpty(), "AntiCrash raised an alarm over a stick of TNT and said " + said);
    }

    // Two hearts are left after the blow and the rule asks for fewer than three.
    private void triggers(Rig rig) {
        World world = rig.world();
        Module fullbright = rig.client().module("Fullbright");
        Props.choose(rig, "Triggers", "Rule 1", "health");
        Props.choose(rig, "Triggers", "Modules 1", "fullbright");
        rig.client().enable("Triggers");
        rig.ticks(SEEN_TICKS);
        boolean waited = !rig.client().isOn("Fullbright");
        world.command("damage " + world.player() + " 16");
        rig.until("Fullbright to come on", ACT_TICKS, mc -> fullbright.isEnabled());
        world.command("effect give " + world.player() + " instant_health 1 20 true");
        rig.until("Fullbright to go off once the hearts are back", ACT_TICKS, mc -> !fullbright.isEnabled());
        rig.expect(waited, "Fullbright came on before the player was hurt. That proves nothing");
    }

    // A game that is not signed in sends no report at all. The gate every report goes through
    // is asked straight.
    private void privacy(Rig rig) {
        Module privacy = rig.client().module("Privacy");
        int[] passed = new int[1];
        TelemetryEventSender counting = (type, properties) -> passed[0]++;
        rig.client().enable("Privacy");
        int before = Props.tally(privacy);
        rig.act(mc -> Privacy.gate(counting).send(TelemetryEventType.WORLD_LOADED, properties -> { }));
        int stopped = Props.tally(privacy) - before;
        int leaked = passed[0];
        rig.client().disable("Privacy");
        rig.act(mc -> Privacy.gate(counting).send(TelemetryEventType.WORLD_LOADED, properties -> { }));
        rig.note("Privacy stopped " + stopped + " report and let " + leaked + " by and " + passed[0]
            + " went out once it was off");
        rig.expect(stopped == 1 && leaked == 0, "A report got past Privacy");
        rig.expect(passed[0] == 1, "No report went out with Privacy off");
    }

    // Three notes on the harp a few ticks apart. Grass under a note block makes it a harp.
    private void notebot(Rig rig) throws IOException {
        World world = rig.world();
        Module notebot = rig.client().module("Notebot");
        Path song = rig.ask(mc -> DataFiles.path("songs")).resolve(SONG + ".txt");
        Files.createDirectories(song.getParent());
        Files.write(song, SONG_NOTES.stream().map(note -> note * SONG_GAP + ":" + note + ":0").toList());
        List<BlockPos> blocks = List.of(home.north(), home.east(), home.west());
        try {
            for (BlockPos block : blocks) {
                world.setBlock(block, "note_block");
            }
            Props.choose(rig, "Notebot", "Songs", SONG);
            // Notebot looks for its note blocks once as it goes on.
            rig.ticks(SEEN_TICKS);
            int mark = rig.client().chatMark();
            rig.client().enable("Notebot");
            rig.until("the song to be played to its end", SONG_TICKS, mc -> !notebot.isEnabled());
            Set<Integer> tuned = new TreeSet<>();
            for (BlockPos block : blocks) {
                for (int note : SONG_NOTES) {
                    if (world.attempt("execute if block " + Props.at(block) + " note_block[note=" + note + "]")) {
                        tuned.add(note);
                    }
                }
            }
            List<String> said = rig.client().saidSince(mark);
            rig.note("The note blocks stand at " + tuned + " and the client said " + said);
            rig.expect(tuned.equals(new TreeSet<>(SONG_NOTES)), "The note blocks were not tuned to the song");
            rig.expect(said.stream().anyMatch(line -> line.contains("finished")), "Notebot did not say it finished");
        } finally {
            Files.deleteIfExists(song);
        }
    }

    // A listener on this machine stands in for the web service.
    private void webhook(Rig rig) throws IOException {
        World world = rig.world();
        List<String> posted = new CopyOnWriteArrayList<>();
        HttpServer listener = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        listener.createContext("/hook", exchange -> {
            posted.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(NO_CONTENT, -1);
            exchange.close();
        });
        listener.start();
        try {
            Module webhook = rig.client().module("Webhook");
            Props.text(rig, "Webhook", "Address", "http://127.0.0.1:" + listener.getAddress().getPort() + "/hook");
            rig.client().set("Webhook", "Damage", "on");
            rig.client().enable("Webhook");
            int mark = rig.client().chatMark();
            rig.act(mc -> ((ActionSetting) webhook.getSetting("Send test")).press());
            rig.until("the test line to arrive", ACT_TICKS, mc -> !posted.isEmpty());
            rig.ticks(SEEN_TICKS);
            boolean told = rig.client().saidSince(mark).stream().anyMatch(line -> line.contains("took the test line"));
            world.command("damage " + world.player() + " 2");
            rig.until("a line about the blow to arrive", ACT_TICKS * 4, mc -> posted.size() >= 2);
            rig.note("The listener got " + posted);
            rig.expect(posted.getFirst().contains("test line"), "The first post was not the test line");
            rig.expect(told, "Chat does not say the address took the test line");
        } finally {
            listener.stop(0);
        }
    }

    // One layer of iron lets a beacon give speed or haste and nothing more.
    private void betterBeacons(Rig rig) {
        World world = rig.world();
        world.fill(home.offset(-1, -1, -4), home.offset(1, -1, -2), "iron_block");
        BlockPos beacon = home.offset(0, 0, -3);
        world.setBlock(beacon, "beacon");
        rig.ticks(BEACON_TICKS);
        int plain = effectsOffered(rig, beacon);
        rig.client().enable("BetterBeacons");
        int all = effectsOffered(rig, beacon);
        rig.note("The beacon offered " + plain + " effects and with BetterBeacons " + all);
        rig.expect(plain > 0, "The beacon offered nothing without BetterBeacons. That proves nothing");
        rig.expect(all > plain, "The beacon offers no more effects than its pyramid is worth");
    }

    // Opens the beacon and counts the effect buttons that can be pressed.
    private static int effectsOffered(Rig rig, BlockPos beacon) {
        rig.controls().aimAt(Vec3.atCenterOf(beacon));
        rig.ticks(2);
        rig.controls().click(USE);
        rig.until("the beacon to open", ACT_TICKS, mc -> mc.gui.screen() instanceof BeaconScreen);
        rig.ticks(SEEN_TICKS);
        int offered = rig.ask(mc -> (int) mc.gui.screen().children().stream()
            .filter(child -> child.getClass().getSimpleName().contains("PowerButton")
                && child instanceof AbstractWidget button && button.active).count());
        rig.act(mc -> mc.player.closeContainer());
        rig.ticks(SEEN_TICKS);
        return offered;
    }

    private void antiPacketKick(Rig rig) {
        rig.client().enable("AntiPacketKick");
        int mark = rig.client().chatMark();
        rig.act(mc -> {
            for (int sent = 0; sent < BURST; sent++) {
                mc.player.connection.send(ServerboundPunchPacket.INSTANCE);
            }
        });
        rig.ticks(ACT_TICKS);
        rig.expect(rig.client().saidSince(mark).stream().anyMatch(line -> line.contains("Holding packets back")),
            "Nothing in chat said the burst was held back");
        rig.expect(rig.inWorld(), "The server dropped the player");
    }

    // The server learns where the player is a second late.
    private void latency(Rig rig) {
        double plain = behind(rig);
        rig.world().teleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, NORTH, 0);
        Props.chooseAll(rig, "Latency", "Outgoing", "move_player");
        rig.client().set("Latency", "Outgoing delay", String.valueOf(HELD_BACK));
        rig.client().enable("Latency");
        double held = behind(rig);
        rig.note(String.format(Locale.ROOT, "The server was %.1f blocks behind and with Latency %.1f", plain, held));
        rig.expect(held >= plain + 2, "The server was no further behind the player");
    }

    // How far the server has the player behind where the game has them at the end of a walk.
    private static double behind(Rig rig) {
        rig.controls().hold(FORWARD);
        rig.ticks(WALK_TICKS);
        Vec3 held = rig.world().where();
        Vec3 stood = rig.ask(mc -> mc.player.position());
        rig.controls().release(FORWARD);
        rig.ticks(SEEN_TICKS);
        return Math.abs(stood.z - held.z);
    }

    // The rig runs with the sound off and what Playlist plays cannot be heard. It must say why it
    // stays quiet.
    private void playlist(Rig rig) {
        int mark = rig.client().chatMark();
        rig.client().enable("Playlist");
        rig.ticks(ACT_TICKS);
        List<String> said = rig.client().saidSince(mark);
        rig.note("Playlist said " + said);
        rig.expect(said.stream().anyMatch(line -> line.contains("volume")), "Playlist does not say that the volume "
            + "keeps it quiet");
    }

    // A husk with half a heart left dies of one blow of a sword.
    private void confetti(Rig rig) {
        rig.client().set("Confetti", "Kills", "on");
        rig.client().enable("Confetti");
        Props.summon(rig, "husk", home.north(2), "{NoAI:1b,PersistenceRequired:1b,IsBaby:0b,Health:1f}");
        Props.hold(rig, 0, "diamond_sword");
        // The sword is ready a second after it comes to hand.
        rig.ticks(SEEN_TICKS * 3);
        rig.controls().aimAt(Vec3.atBottomCenterOf(home.north(2)).add(0, 1, 0));
        rig.ticks(2);
        rig.controls().click(ATTACK);
        rig.until("a bolt on the spot of the kill", ACT_TICKS, mc -> {
            for (var entity : mc.level.entitiesForRendering()) {
                if (entity instanceof LightningBolt) {
                    return true;
                }
            }
            return false;
        });
        rig.expect(Props.living(rig, "lightning_bolt") == 0, "The server holds a real bolt of lightning");
    }
}
