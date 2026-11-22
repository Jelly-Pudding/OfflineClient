package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.World;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// One case for each module that builds or digs a longer job by itself. The player is handed
// the blocks and tools and the server says what stands afterwards.
public final class BuildersSuite implements Suite {

    private static final String NAME = "Builders";
    private static final String KABOOM = "Kaboom blows the blocks round you away";
    private static final String NO_GHOSTS = "NoGhostBlocks leaves a block you place to the server";
    private static final int PEACEFUL = 0;
    private static final int USE = InputConstants.MOUSE_BUTTON_RIGHT;
    private static final String COBBLESTONE = "cobblestone";
    private static final String PICKAXE = "diamond_pickaxe";
    private static final String TEMPLATE = "rigbuild";
    private static final float NORTH = 180;

    private static final int SEEN_TICKS = 10;
    private static final int WORK_TICKS = 400;
    // Lava runs a block every thirty ticks.
    private static final int CAST_TICKS = 1500;
    private static final int PATTERN = 25;
    private static final int STACK = 64;
    private static final int CLIMBED = 3;
    private static final int SPARE_PICKAXES = 4;

    private BlockPos home;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        // A single player server answers a click within the tick and NoGhostBlocks stands aside
        // there. A real server throws a player out for what Kaboom sends.
        Grounds.play(rig, NAME, plays(), stage -> home = stage.home(),
            Grounds.Limits.NONE.aloneOnly(KABOOM).serverOnly(NO_GHOSTS));
    }

    private Map<String, Case.Body> plays() {
        Map<String, Case.Body> plays = new LinkedHashMap<>();
        plays.put("Staircase builds steps and walks you up them", this::staircase);
        plays.put(NO_GHOSTS, this::noGhostBlocks);
        plays.put("AutoBuild puts its pattern up where you click", this::autoBuild);
        plays.put("TemplateTool saves a build and AutoBuild puts it up again", this::templateTool);
        plays.put("AutoWither builds a wither", this::autoWither);
        plays.put("HighwayBuilder lays its floor ahead of you", this::highwayBuilder);
        plays.put("InfinityMiner walks to its ore and mines it", this::infinityMiner);
        plays.put("Salvage mines a block that nature never made", this::salvage);
        plays.put("AxolotlHunter tells of a blue axolotl and scoops it up", this::axolotlHunter);
        plays.put("AutoLibrarian reads what a fresh librarian sells", this::autoLibrarian);
        plays.put(KABOOM, this::kaboom);
        plays.put("LavaCast casts stone from lava and water", this::lavaCast);
        return plays;
    }

    private void staircase(Rig rig) {
        Props.hold(rig, 0, COBBLESTONE + " " + STACK);
        rig.client().set("Staircase", "Walk for you", "on");
        rig.client().set("Staircase", "Start paused", "off");
        rig.client().enable("Staircase");
        int took = rig.until("the player to stand three blocks higher", WORK_TICKS,
            mc -> mc.player.getY() >= home.getY() + CLIMBED);
        rig.client().disable("Staircase");
        rig.ticks(SEEN_TICKS);
        double held = rig.world().where().y;
        rig.note("Three steps up took " + took + " ticks");
        rig.expect(held >= home.getY() + CLIMBED - 0.5, "The server has the player at height " + held);
    }

    // The game shows a block the moment it is placed and takes it back if the server says no.
    private void noGhostBlocks(Rig rig) {
        BlockPos spot = home.north(2);
        Props.hold(rig, 0, COBBLESTONE + " 8");
        rig.controls().aimAtBlock(spot.below(), Direction.UP);
        rig.ticks(SEEN_TICKS);
        boolean guessed = shownAtOnce(rig, spot);
        rig.ticks(SEEN_TICKS);
        rig.world().refill(spot, spot, "air");
        rig.ticks(SEEN_TICKS);
        rig.client().enable("NoGhostBlocks");
        boolean waited = !shownAtOnce(rig, spot);
        rig.ticks(SEEN_TICKS);
        rig.expect(guessed, "The game did not show the block at once without NoGhostBlocks. That proves nothing");
        rig.expect(waited, "The game showed the block before the server had placed it");
        rig.expect(Props.is(rig, spot, COBBLESTONE), "The block never came once the server had placed it");
    }

    // Uses the held block on the face under the crosshair. True when the game has it standing
    // before the server could answer.
    private static boolean shownAtOnce(Rig rig, BlockPos spot) {
        return rig.ask(mc -> {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, (BlockHitResult) mc.hitResult);
            return !mc.level.getBlockState(spot).isAir();
        });
    }

    private void autoBuild(Rig rig) {
        Props.hold(rig, 0, COBBLESTONE + " " + STACK);
        rig.client().enable("AutoBuild");
        click(rig, home.offset(0, -1, -3));
        Props.await(rig, "the whole pattern to stand", WORK_TICKS,
            () -> Props.carried(rig, COBBLESTONE) <= STACK - PATTERN);
    }

    private static void click(Rig rig, BlockPos floor) {
        rig.controls().aimAtBlock(floor, Direction.UP);
        rig.ticks(SEEN_TICKS);
        rig.controls().click(USE);
        rig.ticks(SEEN_TICKS);
    }

    private void templateTool(Rig rig) {
        World world = rig.world();
        BlockPos left = home.offset(-1, 0, -3);
        BlockPos right = home.offset(1, 0, -3);
        world.fill(left, right, COBBLESTONE);
        Props.hold(rig, 0);
        rig.client().set("TemplateTool", "Name", TEMPLATE);
        int mark = rig.client().chatMark();
        rig.client().enable("TemplateTool");
        for (BlockPos corner : List.of(left, right, home.offset(0, 0, -3))) {
            rig.controls().aimAtBlock(corner, Direction.SOUTH);
            rig.ticks(SEEN_TICKS);
            rig.controls().click(USE);
            rig.ticks(SEEN_TICKS);
        }
        List<String> said = rig.client().saidSince(mark);
        rig.note("TemplateTool said " + said);
        rig.expect(said.stream().anyMatch(line -> line.contains("saved") && line.contains(TEMPLATE)),
            "Chat does not say the build was saved");
        rig.client().disable("TemplateTool");
        world.fill(left, right, "air");
        rig.client().set("AutoBuild", "Source", "template");
        Props.choose(rig, "AutoBuild", "Template", TEMPLATE);
        Props.hold(rig, 0, COBBLESTONE + " " + STACK);
        rig.client().enable("AutoBuild");
        click(rig, home.offset(0, -1, -4));
        Props.await(rig, "the three blocks to stand again", WORK_TICKS,
            () -> Props.carried(rig, COBBLESTONE) <= STACK - 3);
    }

    // The wither is taken away whilst it still gathers itself. It never gets to blast the stage.
    private void autoWither(Rig rig) {
        World world = rig.world();
        world.gameMode("creative");
        // No wither wakes on a peaceful world.
        boolean peaceful = world.count("difficulty") == PEACEFUL;
        world.attempt("difficulty easy");
        Props.hold(rig, 0, "soul_sand 4");
        world.hotbar(1, "wither_skeleton_skull 3");
        rig.ticks(SEEN_TICKS);
        rig.client().enable("AutoWither");
        try {
            int took = Props.await(rig, "a wither to stand", WORK_TICKS, () -> Props.living(rig, "wither") > 0);
            rig.note("The wither stood after about " + took + " ticks");
        } finally {
            world.attempt("execute as @e[type=wither] at @s run tp @s ~ ~-300 ~");
            world.attempt("kill @e[type=wither]");
            if (peaceful) {
                world.attempt("difficulty peaceful");
            }
        }
    }

    private void highwayBuilder(Rig rig) {
        rig.world().fill(home.offset(-2, -1, -4), home.offset(2, -1, -3), "air");
        Props.hold(rig, 0, "obsidian " + STACK);
        // It stops for want of tools long before the last one breaks.
        for (int spare = 1; spare <= SPARE_PICKAXES; spare++) {
            rig.world().hotbar(spare, PICKAXE);
        }
        rig.controls().look(NORTH, 0);
        rig.ticks(SEEN_TICKS);
        rig.client().enable("HighwayBuilder");
        try {
            int took = Props.await(rig, "four blocks of floor to be laid", WORK_TICKS,
                () -> Props.carried(rig, "obsidian") <= STACK - 4);
            rig.note("Four blocks were laid after about " + took + " ticks");
        } finally {
            rig.client().disable("HighwayBuilder");
        }
    }

    private void infinityMiner(Rig rig) {
        rig.world().setBlock(home.offset(2, 0, -3), "diamond_ore");
        Props.hold(rig, 0, PICKAXE + "[enchantments={\"minecraft:mending\":1}]");
        rig.client().enable("InfinityMiner");
        int took = Props.await(rig, "a diamond in the backpack", WORK_TICKS, () -> Props.carried(rig, "diamond") > 0);
        rig.note("The diamond was there after about " + took + " ticks");
    }

    // Trial chambers hold white and red concrete. The world makes no blue.
    private void salvage(Rig rig) {
        rig.world().setBlock(home.offset(2, 0, -3), "blue_concrete");
        Props.hold(rig, 0, PICKAXE);
        rig.ticks(SEEN_TICKS);
        rig.client().enable("Salvage");
        int took = Props.await(rig, "the block in the backpack", WORK_TICKS,
            () -> Props.carried(rig, "blue_concrete") > 0);
        rig.note("The block was there after about " + took + " ticks");
    }

    private void axolotlHunter(Rig rig) {
        BlockPos pool = home.offset(1, -1, -2);
        rig.world().setBlock(pool, "water");
        Props.summon(rig, "axolotl", pool, "{NoAI:1b,PersistenceRequired:1b,Variant:4}");
        Props.hold(rig, 0, "water_bucket");
        int mark = rig.client().chatMark();
        rig.client().set("AxolotlHunter", "Catch", "on");
        rig.client().enable("AxolotlHunter");
        Props.await(rig, "the axolotl in the bucket", WORK_TICKS,
            () -> Props.holds(rig, "weapon.mainhand", "axolotl_bucket"));
        List<String> said = rig.client().saidSince(mark);
        rig.note("AxolotlHunter said " + said);
        rig.expect(said.stream().anyMatch(line -> line.toLowerCase(Locale.ROOT).contains("axolotl")),
            "Chat does not tell of the axolotl");
    }

    private void autoLibrarian(Rig rig) {
        BlockPos stall = home.north(2);
        rig.world().setBlock(stall.east(), "lectern");
        Props.summon(rig, "villager", stall, "{NoAI:1b,PersistenceRequired:1b,VillagerData:{profession:"
            + "\"minecraft:librarian\",level:1,type:\"minecraft:plains\"},Offers:{Recipes:[{buy:{id:"
            + "\"minecraft:emerald\","
            + "count:20},buyB:{id:\"minecraft:book\",count:1},sell:{id:\"minecraft:enchanted_book\",count:1,components:"
            + "{\"minecraft:stored_enchantments\":{\"minecraft:mending\":1}}},maxUses:12}]}}");
        rig.ticks(SEEN_TICKS * 2);
        int mark = rig.client().chatMark();
        rig.client().enable("AutoLibrarian");
        rig.until("AutoLibrarian to speak", WORK_TICKS, mc -> !rig.client().saidSince(mark).isEmpty());
        rig.ticks(SEEN_TICKS * 2);
        List<String> said = rig.client().saidSince(mark);
        rig.act(mc -> mc.gui.setScreen(null));
        rig.note("AutoLibrarian said " + said);
        rig.expect(said.stream().anyMatch(line -> line.contains("found")), "Chat does not say the book was found");
    }

    // Dirt stands all round the player in creative where one hit breaks a block.
    private void kaboom(Rig rig) {
        World world = rig.world();
        world.gameMode("creative");
        world.fill(home.offset(-2, 0, -2), home.offset(2, 1, 2), "dirt");
        world.fill(home, home.above(), "air");
        rig.ticks(SEEN_TICKS);
        rig.client().enable("Kaboom");
        rig.until("Kaboom to finish", WORK_TICKS, mc -> !rig.client().module("Kaboom").isEnabled());
        rig.ticks(SEEN_TICKS);
        for (BlockPos beside : List.of(home.north(), home.east(), home.south(), home.west())) {
            rig.expect(world.isAir(beside), "A block right beside the player still stands");
        }
    }

    // The tower is dirt and whatever stone is found afterwards was cast.
    private void lavaCast(Rig rig) {
        World world = rig.world();
        world.command("effect give " + world.player() + " fire_resistance 600 0 true");
        Props.hold(rig, 0, "dirt " + STACK);
        world.hotbar(1, "lava_bucket");
        world.hotbar(2, "water_bucket");
        rig.client().set("LavaCast", "Height", "3");
        rig.client().set("LavaCast", "Layers", "1");
        rig.client().set("LavaCast", "Climb", "off");
        rig.controls().look(NORTH, 0);
        rig.client().enable("LavaCast");
        rig.until("LavaCast to finish", CAST_TICKS, mc -> !rig.client().module("LavaCast").isEnabled());
        rig.ticks(SEEN_TICKS);
        Vec3 stood = world.where();
        int cast = world.count("fill " + Props.at(home.offset(-6, 0, -6)) + " " + Props.at(home.offset(6, 8, 6))
            + " air replace #minecraft:base_stone_overworld") + world.count("fill " + Props.at(home.offset(-6, 0, -6))
            + " " + Props.at(home.offset(6, 8, 6)) + " air replace cobblestone");
        rig.note("The player ended " + Math.round(stood.y - home.getY()) + " blocks up and " + cast
            + " blocks of stone were cast");
        rig.expect(cast > 0, "No stone was cast");
    }
}
