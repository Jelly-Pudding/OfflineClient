package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.rig.duo.Duo;
import com.jellypudding.offlineclient.rig.duo.PaperServer;
import com.jellypudding.offlineclient.setting.ActionSetting;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// One case for each module that finds what other players left behind. The trace is built on the
// stage and the module must count it. A module that judges the land itself plays on grown land.
public final class HuntingSuite implements Suite {

    private static final String NAME = "Hunting";
    private static final String FEW_CONTAINERS = "StashFinder leaves a chunk with three containers alone";
    private static final String SLEEPING_SPAWNER = "SpawnerFinder leaves a far spawner alone that nobody woke";
    private static final String ORE_PROBE = "OreProbe uncovers an ore the server hides";
    private static final int PROBE_TICKS = 400;
    private static final int HEAP = 8;
    private static final WorldCreationUiState.SelectedGameMode CREATIVE =
        WorldCreationUiState.SelectedGameMode.CREATIVE;
    private static final String SEED = "offline one";
    private static final String NEW_CHUNKS = "NewChunks judges the chunks that load whilst it is on";
    private static final String ORE_SIGHT = "OreSight shows where the seed put ore";
    // The game works the ores out from the seed in the background.
    private static final int SEED_TICKS = 1200;
    private static final String STAYS = ",PickupDelay:32767,Age:-32768";

    // A finder searches a few chunks a tick and its first find can take some seconds.
    private static final int SCAN_TICKS = 300;
    private static final int SEEN_TICKS = 10;
    private static final int QUIET_TICKS = 60;
    private static final int SURVEY_TICKS = 140;
    // An eye of ender flies for eighty ticks.
    private static final int FLIGHT_TICKS = 160;
    private static final int FAR_OFF = 600;
    // Near enough for a plain server to send the true spot and high enough to break nothing.
    private static final int WITHER_ASIDE = 20;
    // Further than a spawner counts a player as near.
    private static final int SPAWNER_OFF = 30;

    private BlockPos home;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        Grounds.play(rig, NAME, plays(), stage -> home = stage.home(),
            Grounds.Limits.NONE.holdsBack(FEW_CONTAINERS, SLEEPING_SPAWNER));
        onGrownLand(rig);
        whereOresHide(rig);
    }

    // OreProbe is for a server that keeps buried ores out of the chunks it sends.
    private void whereOresHide(Rig rig) {
        List<Case> wanted = rig.wanted(NAME, List.of(Case.of(ORE_PROBE, this::oreProbe)));
        if (wanted.isEmpty()) {
            return;
        }
        if (!Duo.available()) {
            wanted.forEach(each -> rig.skip(NAME, each.name(), Duo.NO_SERVER));
            return;
        }
        Duo duo = Duo.alone(rig, PaperServer.Setup.hidingOres());
        try {
            Stage stage = new Stage(rig);
            home = stage.home();
            for (Case each : wanted) {
                stage.reset(false);
                rig.run(NAME, each);
            }
        } finally {
            rig.client().restoreDefaults();
            duo.close();
        }
    }

    // A block change reaches the game as it is. Only a chunk sent anew has its ores hidden and
    // the player goes far off and comes back for one.
    private void oreProbe(Rig rig) {
        World world = rig.world();
        BlockPos ore = home.offset(3, -2, 0);
        world.setBlock(ore, "diamond_ore");
        try {
            world.teleport(home.getX() + FAR_OFF, home.getY(), home.getZ() + 0.5, 0, 0);
            world.teleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0, 0);
            rig.ticks(SEEN_TICKS * 2);
            boolean hidden = rig.ask(mc -> !mc.level.getBlockState(ore).is(Blocks.DIAMOND_ORE));
            Props.choose(rig, "OreProbe", "Ores", "diamond");
            rig.client().enable("OreProbe");
            int took = rig.until("the hidden diamond ore to turn up", PROBE_TICKS,
                mc -> mc.level.getBlockState(ore).is(Blocks.DIAMOND_ORE));
            rig.note("The ore the server hid turned up after " + took + " ticks");
            rig.expect(hidden, "The server did not hide the ore. That proves nothing");
        } finally {
            world.setBlock(ore, "dirt");
        }
    }

    private Map<String, Case.Body> plays() {
        Map<String, Case.Body> plays = new LinkedHashMap<>();
        plays.put("StashFinder points out a chunk packed with containers", this::stashFinder);
        plays.put(FEW_CONTAINERS, this::fewContainers);
        plays.put("StashFinder counts storage minecarts heaped in one block", this::heapedCarts);
        plays.put("BaseFinder marks the chunk of a written sign", this::baseFinder);
        plays.put("BaseFinder marks the chunk an endermite crawls in", this::endermiteClue);
        plays.put("SpawnerFinder finds a far spawner whose count has run", this::spawnerFinder);
        plays.put(SLEEPING_SPAWNER, this::sleepingSpawner);
        plays.put("PotFinder finds a decorated pot a player placed", this::potFinder);
        plays.put("TunnelESP marks a tunnel dug through stone", this::tunnelEsp);
        plays.put("CaveTracks finds a block mined out of an old cave", this::caveTracks);
        plays.put("Earshot marks a far sound and leaves a near one", this::earshot);
        plays.put("ItemCoords marks where a lodestone compass leads", this::itemCoords);
        plays.put("BookReader reads a book that lies on the ground", this::bookReader);
        plays.put("MapArt counts an item frame that holds a map", this::mapArt);
        plays.put("EyeTracker takes a line from the flight of an eye of ender", this::eyeTracker);
        plays.put("Eavesdrop reports a wither that spawns", this::eavesdrop);
        return plays;
    }

    // NewChunks reads how the server wrote a chunk down. Flat land is written the same way new
    // or old.
    private void onGrownLand(Rig rig) {
        List<Case> wanted = rig.wanted(NAME, List.of(Case.of(NEW_CHUNKS, this::newChunks),
            Case.of(ORE_SIGHT, this::oreSight)));
        if (wanted.isEmpty()) {
            return;
        }
        rig.openLand(CREATIVE, SEED);
        try {
            for (Case each : wanted) {
                rig.client().restoreDefaults();
                rig.run(NAME, each);
            }
        } finally {
            // A finder left on would start on the world of the next suite.
            rig.client().restoreDefaults();
            rig.closeWorld();
        }
    }

    private static int finds(Module finder) {
        return Props.tally(finder);
    }

    private static int finds(Rig rig, String module) {
        Module finder = rig.client().module(module);
        return rig.ask(mc -> finds(finder));
    }

    // Wipes what the module found before. Finds are kept from one case to the next.
    private static void forget(Rig rig, String module) {
        Module finder = rig.client().module(module);
        rig.act(mc -> {
            if (finder.getSetting("Clear finds") instanceof ActionSetting clear) {
                clear.press();
                if (clear.isArmed()) {
                    clear.press();
                }
            }
        });
    }

    // Switches the module on and waits for its first find. Hands back what it said in chat.
    private static List<String> firstFind(Rig rig, String module) {
        Module finder = rig.client().module(module);
        forget(rig, module);
        int mark = rig.client().chatMark();
        rig.client().enable(module);
        rig.until(module + " to find it", SCAN_TICKS, mc -> finds(finder) > 0);
        rig.ticks(SEEN_TICKS);
        return rig.client().saidSince(mark);
    }

    private static boolean says(List<String> said, String words) {
        return said.stream().anyMatch(line -> line.toLowerCase(Locale.ROOT).contains(words.toLowerCase(Locale.ROOT)));
    }

    // Ten chests in one chunk.
    private void stashFinder(Rig rig) {
        rig.world().fill(home.offset(1, 0, -5), home.offset(5, 0, -4), "chest");
        List<String> said = firstFind(rig, "StashFinder");
        rig.note("StashFinder said " + said);
        rig.expect(!said.isEmpty(), "Nothing in chat told of the stash");
    }

    private void fewContainers(Rig rig) {
        rig.world().fill(home.offset(1, 0, -4), home.offset(3, 0, -4), "chest");
        forget(rig, "StashFinder");
        rig.client().enable("StashFinder");
        rig.ticks(QUIET_TICKS);
        int found = finds(rig, "StashFinder");
        rig.expect(found == 0, "Three chests counted as " + found + " stashes");
    }

    // Eight chest minecarts in one block hold as much as eight chests and stand on no block of
    // their own.
    private void heapedCarts(Rig rig) {
        forget(rig, "StashFinder");
        rig.client().set("StashFinder", "Storage carts", "off");
        for (int cart = 0; cart < HEAP; cart++) {
            Props.summon(rig, "chest_minecart", home.offset(3, 0, -4), "{}");
        }
        rig.client().enable("StashFinder");
        rig.ticks(QUIET_TICKS);
        int without = finds(rig, "StashFinder");
        rig.client().disable("StashFinder");
        rig.client().set("StashFinder", "Storage carts", "on");
        List<String> said = firstFind(rig, "StashFinder");
        rig.note("StashFinder said " + said);
        rig.expect(without == 0, "The carts counted with Storage carts off");
        rig.expect(said.stream().anyMatch(line -> line.contains("chest minecart")), "The find does not name the carts");
    }

    private void baseFinder(Rig rig) {
        rig.client().enable("BaseFinder");
        rig.ticks(QUIET_TICKS);
        int bare = finds(rig, "BaseFinder");
        rig.client().disable("BaseFinder");
        rig.world().command("setblock " + Props.at(home.offset(2, 0, -3))
            + " oak_sign[rotation=0]{front_text:{messages:[\"rig was here\",\"\",\"\",\"\"]}}");
        firstFind(rig, "BaseFinder");
        rig.expect(bare == 0, "The bare stage already counted as " + bare + " bases. That proves nothing");
    }

    // An endermite only ever comes out of an ender pearl that somebody threw. A peaceful world
    // would take it away again at once.
    private void endermiteClue(Rig rig) {
        World world = rig.world();
        boolean peaceful = world.count("difficulty") == 0;
        world.attempt("difficulty easy");
        try {
            forget(rig, "BaseFinder");
            rig.client().enable("BaseFinder");
            rig.ticks(QUIET_TICKS);
            int bare = finds(rig, "BaseFinder");
            rig.client().disable("BaseFinder");
            int mark = rig.client().chatMark();
            Props.summon(rig, "endermite", home.offset(2, 0, -3), "{NoAI:1b}");
            firstFind(rig, "BaseFinder");
            List<String> said = rig.client().saidSince(mark);
            rig.expect(bare == 0, "The bare stage already counted as " + bare + " bases. That proves nothing");
            rig.expect(says(said, "endermite"), "Chat does not tell of the endermite. It said " + said);
        } finally {
            world.attempt("kill @e[type=endermite]");
            if (peaceful) {
                world.attempt("difficulty peaceful");
            }
        }
    }

    // A spawner only counts down whilst a player is near. One that reaches the game part way
    // through its count with nobody near had a visitor before.
    private void spawnerFinder(Rig rig) {
        BlockPos spawner = farSpawner(rig, home.offset(0, 0, -SPAWNER_OFF), 137);
        try {
            int mark = rig.client().chatMark();
            firstFind(rig, "SpawnerFinder");
            // It looks the room round a spawner over before it speaks.
            rig.ticks(SURVEY_TICKS);
            List<String> said = rig.client().saidSince(mark);
            rig.note("SpawnerFinder said " + said);
            rig.expect(says(said, "spawner"), "Chat does not tell of the spawner");
        } finally {
            rig.world().attempt("setblock " + Props.at(spawner) + " air");
        }
    }

    private void sleepingSpawner(Rig rig) {
        BlockPos spawner = farSpawner(rig, home.offset(8, 0, -SPAWNER_OFF), 20);
        try {
            forget(rig, "SpawnerFinder");
            rig.client().enable("SpawnerFinder");
            rig.ticks(QUIET_TICKS * 2);
            int found = finds(rig, "SpawnerFinder");
            rig.expect(found == 0, "A spawner at the start of its count was reported " + found + " times");
        } finally {
            rig.world().attempt("setblock " + Props.at(spawner) + " air");
        }
    }

    // A zombie spawner out of the stage with that many ticks left on its count.
    private static BlockPos farSpawner(Rig rig, BlockPos spawner, int delay) {
        rig.world().command("setblock " + Props.at(spawner) + " spawner{Delay:" + delay
            + "s,SpawnData:{entity:{id:\"minecraft:zombie\"}}}");
        return spawner;
    }

    private void potFinder(Rig rig) {
        rig.world().setBlock(home.offset(2, 0, -3), "decorated_pot");
        List<String> said = firstFind(rig, "PotFinder");
        rig.note("PotFinder said " + said);
    }

    // A block of stone with a passage one wide and two high through its middle.
    private void tunnelEsp(Rig rig) {
        World world = rig.world();
        world.fill(home.offset(-6, 0, -6), home.offset(6, 4, -2), "stone");
        world.fill(home.offset(-5, 1, -4), home.offset(5, 2, -4), "air");
        firstFind(rig, "TunnelESP");
    }

    // Old caves are cave air and a block a player breaks leaves plain air. The cave lies deep
    // inside a block of stone with one block dug out of its wall.
    private void caveTracks(Rig rig) {
        World world = rig.world();
        world.fill(home.offset(-6, 0, -6), home.offset(6, 8, -2), "stone");
        world.fill(home.offset(-2, 3, -5), home.offset(0, 5, -3), "cave_air");
        world.setBlock(home.offset(1, 4, -4), "air");
        List<String> said = firstFind(rig, "CaveTracks");
        rig.note("CaveTracks said " + said);
    }

    // A chest opens twelve blocks off and another three blocks off.
    private void earshot(Rig rig) {
        rig.client().enable("Earshot");
        rig.ticks(SEEN_TICKS);
        sound(rig, home.north(3));
        int near = finds(rig, "Earshot");
        sound(rig, home.north(12));
        int far = finds(rig, "Earshot");
        rig.expect(near == 0, "A sound three blocks off was marked");
        rig.expect(far == 1, "The sound twelve blocks off left " + far + " marks");
    }

    private static void sound(Rig rig, BlockPos at) {
        World world = rig.world();
        world.command("playsound minecraft:block.chest.open master " + world.player() + " " + Props.at(at) + " 1");
        rig.ticks(SEEN_TICKS);
    }

    private void itemCoords(Rig rig) {
        World world = rig.world();
        world.command("item replace entity " + world.player() + " hotbar.3 with compass[lodestone_tracker={target:"
            + "{pos:[I;140,64,-220],dimension:\"minecraft:overworld\"}}]");
        List<String> said = firstFind(rig, "ItemCoords");
        rig.note("ItemCoords said " + said);
        rig.expect(says(said, "140") && says(said, "-220"), "Chat does not name where the compass leads");
    }

    private void bookReader(Rig rig) {
        Props.summon(rig, "item", home.offset(2, 0, -3), "{Item:{id:\"minecraft:written_book\",count:1,components:"
            + "{\"minecraft:written_book_content\":{title:\"Rig log\",author:\"Rig\",pages:[\"first page\"]}}}"
            + STAYS + "}");
        List<String> said = firstFind(rig, "BookReader");
        rig.note("BookReader said " + said);
        rig.expect(says(said, "Rig log"), "Chat does not name the book");
    }

    private void mapArt(Rig rig) {
        BlockPos frame = home.offset(2, 1, -3);
        rig.world().command("summon item_frame " + Props.at(frame) + " {Facing:3b,Fixed:1b,Item:{id:"
            + "\"minecraft:filled_map\",count:1,components:{\"minecraft:map_id\":7}}}");
        firstFind(rig, "MapArt");
    }

    // An eye a command throws flies straight for as long as one a player threw.
    private void eyeTracker(Rig rig) {
        Vec3 from = Vec3.atBottomCenterOf(home.offset(0, 3, -2));
        int mark = rig.client().chatMark();
        rig.client().enable("EyeTracker");
        rig.world().command(String.format(Locale.ROOT, "summon eye_of_ender %.1f %.1f %.1f {Motion:[0.0d,0.05d,-0.5d]}",
            from.x, from.y, from.z));
        Module tracker = rig.client().module("EyeTracker");
        rig.until("the eye to come down and give a line", FLIGHT_TICKS, mc -> finds(tracker) > 0);
        List<String> said = rig.client().saidSince(mark);
        rig.note("EyeTracker said " + said);
        rig.expect(!said.isEmpty(), "Nothing in chat told of the eye's flight");
    }

    // The wither wakes high over the stage where its blast breaks nothing. It leaves a player
    // in creative alone.
    private void eavesdrop(Rig rig) {
        World world = rig.world();
        world.gameMode("creative");
        int mark = rig.client().chatMark();
        rig.client().enable("Eavesdrop");
        BlockPos high = home.offset(WITHER_ASIDE, WITHER_ASIDE, 0);
        world.command("summon wither " + Props.at(high) + " {Invul:5}");
        try {
            Module ear = rig.client().module("Eavesdrop");
            rig.until("word of the wither", SCAN_TICKS, mc -> finds(ear) > 0);
        } finally {
            world.attempt("execute as @e[type=wither] at @s run tp @s ~ ~-300 ~");
            world.attempt("kill @e[type=wither]");
            world.attempt("kill @e[type=wither_skull]");
        }
        List<String> said = rig.client().saidSince(mark);
        rig.note("Eavesdrop said " + said);
        rig.expect(says(said, "wither"), "Chat does not tell of the wither");
    }

    private void newChunks(Rig rig) {
        rig.world().gameMode("creative");
        rig.client().enable("NewChunks");
        rig.ticks(SEEN_TICKS);
        Vec3 far = rig.local().ground(FAR_OFF, FAR_OFF);
        rig.world().teleport(far.x, far.y, far.z, 0, 0);
        Module judge = rig.client().module("NewChunks");
        rig.until("the chunks far off to be judged new", SCAN_TICKS, mc -> finds(judge) > 0);
        rig.note("NewChunks shows " + rig.ask(mc -> String.valueOf(judge.getSuffix())));
    }

    private void oreSight(Rig rig) {
        rig.world().gameMode("creative");
        Module sight = rig.client().module("OreSight");
        rig.client().enable("OreSight");
        int took = rig.until("ore to be shown under the ground", SEED_TICKS, mc -> finds(sight) > 0);
        rig.note("It showed " + rig.ask(mc -> finds(sight)) + " ore after " + took + " ticks");
    }
}
