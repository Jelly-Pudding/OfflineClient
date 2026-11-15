package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.gui.InspectorScreen;
import com.jellypudding.offlineclient.gui.WorldMapScreen;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

// One case for each module that changes what the game shows or says or does by itself. What
// the screen writes is read back and what the server holds is asked for.
public final class MiscSuite implements Suite {

    private static final String NAME = "Misc";
    private static final int FORWARD = InputConstants.KEY_W;
    private static final int USE = InputConstants.MOUSE_BUTTON_RIGHT;
    private static final String CROWD = "{NoGravity:1b,Marker:1b}";
    // One armour stand doubled this often is a crowd of 256.
    private static final int DOUBLINGS = 8;
    private static final int ENTITY_CAP = 100;
    private static final Pattern CLOCK = Pattern.compile("\\b\\d{1,2}:\\d{2}\\b");
    private static final String STILL_MOB = "{NoAI:1b,PersistenceRequired:1b,IsBaby:0b}";

    private static final int SEEN_TICKS = 10;
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
        plays.put("PacketCanceller drops the server's chat lines", this::packetCanceller);
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
}
