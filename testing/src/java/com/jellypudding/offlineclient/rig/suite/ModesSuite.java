package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigFailure;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.rig.suite.Motion.Moved;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

// A module is played through every option of a choice that decides how it does its work.
// Whatever one option promises the others promise too. A case names each option that fell
// short and what it did instead.
public final class ModesSuite implements Suite {

    private static final String NAME = "Modes";
    private static final String SURVIVAL = "survival";
    private static final int FORWARD = InputConstants.KEY_W;
    private static final int SPRINT = InputConstants.KEY_LCONTROL;
    private static final int JUMP = InputConstants.KEY_SPACE;
    private static final String STONE = "stone";
    private static final String OBSIDIAN = "obsidian";
    private static final String SWORD = "diamond_sword";
    private static final String HUSK = "husk";
    private static final String TOUGH_HUSK = "{NoAI:1b,PersistenceRequired:1b,IsBaby:0b,Health:200f,"
        + "attributes:[{id:\"minecraft:max_health\",base:200}]}";
    private static final int TOUGH_HEALTH = 200;
    private static final float FULL_HEALTH = 20;
    private static final int RUN_TICKS = 30;
    private static final int SEEN_TICKS = 10;
    private static final int FIGHT_TICKS = 50;
    private static final int DIG_TICKS = 60;
    private static final int BUILD_TICKS = 200;
    private static final int DEADLY_DROP = 30;
    private static final int FLOOR_REACH = 6;
    private static final int VOID_TICKS = 160;
    private static final int GLIDE_HEIGHT = 60;
    private static final int GLIDE_TICKS = 40;
    private static final int ATTACK = InputConstants.MOUSE_BUTTON_LEFT;
    private static final int FLOOR_DEPTH = 3;

    private Stage stage;
    private BlockPos home;
    private double walked;
    private double sprinted;
    private int bareBreak;
    private double bareGlide;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        Grounds.play(rig, NAME, plays(), ground -> {
            stage = ground;
            home = ground.home();
            walked = 0;
            sprinted = 0;
            bareBreak = 0;
            bareGlide = 0;
        });
    }

    private Map<String, Case.Body> plays() {
        Map<String, Case.Body> plays = new LinkedHashMap<>();
        plays.put("Speed outruns a walk in every mode", rig -> sweep(rig, "Speed", "Mode", Set.of(), this::outruns));
        plays.put("Flight lifts you and holds you there in every mode",
            rig -> sweep(rig, "Flight", "Mode", Set.of(), this::liftsAndHolds));
        plays.put("Step takes a block in its stride in every mode",
            rig -> sweep(rig, "Step", "Mode", Set.of(), this::stepsUp));
        plays.put("Jesus carries you over water in every mode",
            rig -> sweep(rig, "Jesus", "Mode", Set.of(), this::crossesWater));
        plays.put("Sneak is seen by the server in every mode",
            rig -> sweep(rig, "Sneak", "Mode", Set.of(), this::seenSneaking));
        plays.put("Sprint keeps a run at a sprint in every mode",
            rig -> sweep(rig, "Sprint", "Mode", Set.of(), this::sprintsUnasked));
        plays.put("AutoJump jumps in every mode", rig -> sweep(rig, "AutoJump", "Mode", Set.of(), this::jumps));
        plays.put("AutoJump jumps on a sprint whenever it is told to jump",
            rig -> sweep(rig, "AutoJump", "Jump when", Set.of(), this::jumps));
        plays.put("Jesus carries you over water with either answer to the server",
            rig -> sweep(rig, "Jesus", "Server bypass", Set.of(), this::crossesWater));
        // Cruise flies a wave and Bounce hops along the ground. Neither holds a height.
        plays.put("ElytraFly holds its height on wings in every mode that holds one",
            rig -> sweep(rig, "ElytraFly", "Mode", Set.of("cruise", "bounce"), this::holdsHeightOnWings));
        // Place and Air place need an item of their own and have cases of their own.
        plays.put("NoFall takes the hurt out of a deadly fall in every mode that needs no item",
            rig -> sweep(rig, "NoFall", "Mode", Set.of("place", "air_place"), this::fallsUnhurt));
        plays.put("Nuker digs in every shape", rig -> sweep(rig, "Nuker", "Shape", Set.of(), this::digs));
        plays.put("Nuker digs at every speed", rig -> sweep(rig, "Nuker", "Speed", Set.of(), this::digs));
        plays.put("Nuker digs in every order", rig -> sweep(rig, "Nuker", "Order", Set.of(), this::digs));
        plays.put("KillAura hits whichever way it turns",
            rig -> sweep(rig, "KillAura", "Rotate", Set.of(), option -> hits(rig, "KillAura")));
        plays.put("KillAura hits whichever target it puts first",
            rig -> sweep(rig, "KillAura", "Priority", Set.of(), option -> hits(rig, "KillAura")));
        plays.put("TriggerBot hits whichever way it faces its target",
            rig -> sweep(rig, "TriggerBot", "Face target", Set.of(), option -> hits(rig, "TriggerBot")));
        plays.put("Surround builds its walls however it centres you",
            rig -> sweep(rig, "Surround", "Centre", Set.of(), this::wallsIn));
        plays.put("AntiVoid stops a fall into the void in every mode",
            rig -> sweep(rig, "AntiVoid", "Mode", Set.of(), this::caughtOverTheVoid));
        plays.put("FastBreak breaks a log sooner than the bare hand in every mode",
            rig -> sweep(rig, "FastBreak", "Mode", Set.of(), this::breaksSooner));
        return plays;
    }

    // Plays the body once for every option of the choice on a fresh stage with the option set
    // and the module still off.
    private void sweep(Rig rig, String module, String setting, Set<String> without, Consumer<Rig> play) {
        List<String> fellShort = new ArrayList<>();
        List<String> played = new ArrayList<>();
        for (String option : options(rig, module, setting)) {
            if (without.contains(option)) {
                continue;
            }
            stage.reset(false);
            rig.world().gameMode(SURVIVAL);
            rig.client().set(module, setting, option);
            try {
                play.accept(rig);
                played.add(option);
            } catch (RigFailure failure) {
                fellShort.add(option + " (" + failure.getMessage() + ")");
            } finally {
                rig.controls().release(FORWARD);
                rig.controls().release(SPRINT);
                rig.controls().release(JUMP);
            }
        }
        rig.note(setting + " passed at " + played);
        rig.expect(!played.isEmpty() || !fellShort.isEmpty(), module + " has no options for " + setting);
        rig.expect(fellShort.isEmpty(), module + " fell short with " + setting + " at " + fellShort);
    }

    private static List<String> options(Rig rig, String module, String setting) {
        Module owner = rig.client().module(module);
        return rig.ask(mc -> {
            if (!(owner.getSetting(setting) instanceof EnumSetting<?> choice)) {
                throw new RigFailure(module + " has no choice called " + setting);
            }
            List<String> names = new ArrayList<>();
            for (Enum<?> option : choice.getValue().getDeclaringClass().getEnumConstants()) {
                names.add(option.name().toLowerCase(Locale.ROOT));
            }
            return names;
        });
    }

    private void goHome(Rig rig) {
        rig.world().teleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 180, 0);
    }

    private double walked(Rig rig) {
        if (walked == 0) {
            walked = Motion.hold(rig, RUN_TICKS, FORWARD).across();
            goHome(rig);
        }
        return walked;
    }

    private double sprinted(Rig rig) {
        if (sprinted == 0) {
            sprinted = Motion.hold(rig, RUN_TICKS, FORWARD, SPRINT).across();
            goHome(rig);
        }
        return sprinted;
    }

    private void outruns(Rig rig) {
        double plain = walked(rig);
        rig.client().enable("Speed");
        Moved moved = Motion.hold(rig, RUN_TICKS, FORWARD);
        rig.expect(moved.across() >= plain * 1.2, String.format(Locale.ROOT, "it ran %.1f blocks against %.1f at a walk",
            moved.across(), plain));
        rig.expect(moved.setbacks() == 0, "the server put the run back " + moved.setbacks() + " times");
    }

    private void liftsAndHolds(Rig rig) {
        rig.client().enable("Flight");
        Moved up = Motion.hold(rig, RUN_TICKS / 2, JUMP);
        Moved hover = Motion.hold(rig, RUN_TICKS);
        rig.client().disable("Flight");
        rig.expect(up.rise() >= 1.5, String.format(Locale.ROOT, "it rose %.1f blocks", up.rise()));
        rig.expect(Math.abs(hover.rise()) < 1.5, String.format(Locale.ROOT, "it drifted %.1f blocks in a hover",
            hover.rise()));
        rig.expect(up.setbacks() + hover.setbacks() == 0, "the server put the flight back");
    }

    private void stepsUp(Rig rig) {
        rig.world().fill(home.offset(-1, 0, -6), home.offset(1, 0, -2), STONE);
        rig.client().enable("Step");
        Moved moved = Motion.hold(rig, RUN_TICKS, FORWARD);
        rig.expect(moved.rise() >= 0.99, String.format(Locale.ROOT, "it rose %.2f blocks", moved.rise()));
    }

    // The pool is two deep. A walk across one a single block deep keeps to its floor and sinks
    // no further than Jesus may.
    private void crossesWater(Rig rig) {
        rig.world().fill(home.offset(-1, -2, -6), home.offset(1, -1, -2), "water");
        rig.ticks(SEEN_TICKS);
        rig.client().enable("Jesus");
        Moved moved = Motion.hold(rig, RUN_TICKS + RUN_TICKS / 2, FORWARD);
        rig.expect(moved.across() >= 5, String.format(Locale.ROOT, "it got %.1f blocks", moved.across()));
        rig.expect(moved.lowest() >= -1, String.format(Locale.ROOT, "it sank %.1f blocks", -moved.lowest()));
    }

    private void seenSneaking(Rig rig) {
        rig.client().enable("Sneak");
        rig.ticks(SEEN_TICKS);
        boolean held = rig.world().holds("sneaking");
        rig.client().disable("Sneak");
        rig.ticks(SEEN_TICKS);
        rig.expect(held, "the server did not hold the player as sneaking");
        rig.expect(!rig.world().holds("sneaking"), "the server still held the player as sneaking once it was off");
    }

    private void sprintsUnasked(Rig rig) {
        double plain = sprinted(rig);
        rig.client().enable("Sprint");
        Moved moved = Motion.hold(rig, RUN_TICKS, FORWARD);
        rig.expect(moved.across() >= plain * 0.9, String.format(Locale.ROOT, "it ran %.1f blocks against %.1f at a "
            + "sprint", moved.across(), plain));
    }

    private void jumps(Rig rig) {
        rig.client().enable("AutoJump");
        Moved moved = Motion.hold(rig, RUN_TICKS + RUN_TICKS / 2, FORWARD, SPRINT);
        rig.expect(moved.takeOffs() >= 2, "it left the ground " + moved.takeOffs() + " times");
    }

    private void holdsHeightOnWings(Rig rig) {
        if (bareGlide == 0) {
            bareGlide = glideLoss(rig);
        }
        rig.client().enable("ElytraFly");
        double lost = glideLoss(rig);
        rig.client().disable("ElytraFly");
        rig.expect(bareGlide >= 2, "wings alone lost no height. That proves nothing");
        rig.expect(lost <= 1.5 && lost <= bareGlide - 2, String.format(Locale.ROOT,
            "it lost %.1f blocks of height against %.1f on wings alone", lost, bareGlide));
    }

    // The glide starts high up in creative where no landing hurts.
    private double glideLoss(Rig rig) {
        World world = rig.world();
        Props.wear(rig, "armor.chest", "elytra");
        world.gameMode("creative");
        world.command("tp " + world.player() + " " + (home.getX() + 0.5) + " " + (home.getY() + GLIDE_HEIGHT) + " "
            + (home.getZ() + 0.5));
        rig.until("the fall to begin", SEEN_TICKS * 2, mc -> !mc.player.onGround());
        rig.ticks(SEEN_TICKS / 2);
        rig.controls().hold(JUMP);
        rig.ticks(2);
        rig.controls().release(JUMP);
        rig.until("the wings to open", RUN_TICKS, mc -> mc.player.isFallFlying());
        double from = rig.ask(mc -> mc.player.getY());
        Motion.hold(rig, GLIDE_TICKS, FORWARD);
        double lost = from - rig.ask(mc -> mc.player.getY());
        goHome(rig);
        world.gameMode(SURVIVAL);
        return lost;
    }

    private void fallsUnhurt(Rig rig) {
        World world = rig.world();
        rig.client().enable("NoFall");
        world.command("tp " + world.player() + " " + (home.getX() + 0.5) + " " + (home.getY() + DEADLY_DROP) + " "
            + (home.getZ() + 0.5));
        Motion.untilLanded(rig);
        rig.ticks(SEEN_TICKS);
        Stage.expectAlive(rig);
        float health = world.health();
        rig.expect(health == FULL_HEALTH, "the fall cost " + (FULL_HEALTH - health) + " health");
    }

    // Eight blocks of stone stand round the player at the feet and at the head. Every shape
    // reaches those. Nothing else in reach can be broken at all.
    private void digs(Rig rig) {
        World world = rig.world();
        world.refill(home.offset(-FLOOR_REACH, -FLOOR_DEPTH, -FLOOR_REACH), home.offset(FLOOR_REACH, -1, FLOOR_REACH),
            "bedrock");
        List<BlockPos> stones = new ArrayList<>();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            stones.add(home.relative(side));
            stones.add(home.relative(side).above());
        }
        stones.forEach(stone -> world.setBlock(stone, STONE));
        Props.hold(rig, 0, "diamond_pickaxe");
        rig.ticks(SEEN_TICKS);
        rig.client().enable("Nuker");
        rig.ticks(DIG_TICKS);
        rig.client().disable("Nuker");
        long broken = stones.stream().filter(stone -> !Props.is(rig, stone, STONE)).count();
        rig.expect(broken >= stones.size() / 2, "it broke " + broken + " of " + stones.size() + " blocks of stone in "
            + DIG_TICKS + " ticks");
    }

    private void hits(Rig rig, String module) {
        Props.hold(rig, 0, SWORD);
        Props.summon(rig, HUSK, home.north(2), TOUGH_HUSK);
        if (module.equals("KillAura")) {
            rig.client().set(module, "Hostile mobs", "on");
        }
        rig.ticks(SEEN_TICKS);
        rig.client().enable(module);
        rig.ticks(FIGHT_TICKS);
        int dealt = TOUGH_HEALTH - Props.health(rig, HUSK);
        rig.expect(dealt > 0, "it took nothing off the husk in " + FIGHT_TICKS + " ticks");
    }

    // The blocks in the hand are for the mode that builds.
    private void caughtOverTheVoid(Rig rig) {
        World world = rig.world();
        BlockPos shaft = home.north(2);
        world.fill(shaft.below(FLOOR_DEPTH + 1), shaft.below(), "air");
        Props.hold(rig, 0, "cobblestone 64");
        rig.client().enable("AntiVoid");
        Motion.hold(rig, RUN_TICKS / 2, FORWARD);
        rig.ticks(VOID_TICKS);
        Stage.expectAlive(rig);
        double depth = home.getY() - world.where().y;
        rig.expect(depth < DEADLY_DROP, String.format(Locale.ROOT, "it let the player fall %.0f blocks", depth));
    }

    private void breaksSooner(Rig rig) {
        if (bareBreak == 0) {
            bareBreak = logBroken(rig);
        }
        rig.client().set("FastBreak", "Speed", "1.3");
        rig.client().enable("FastBreak");
        int fast = logBroken(rig);
        rig.expect(fast <= bareBreak * 0.9, "the log took " + fast + " ticks against " + bareBreak
            + " by the bare hand");
    }

    private int logBroken(Rig rig) {
        BlockPos log = home.north(2);
        rig.world().setBlock(log, "oak_log");
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAtBlock(log, Direction.SOUTH);
        rig.controls().holdMouse(ATTACK);
        int took;
        try {
            took = rig.until("the log to break", BUILD_TICKS, mc -> mc.level.getBlockState(log).isAir());
        } finally {
            rig.controls().releaseMouse(ATTACK);
        }
        rig.ticks(SEEN_TICKS);
        rig.expect(Props.is(rig, log, "air"), "the server still has the log the game broke");
        return took;
    }

    private void wallsIn(Rig rig) {
        Props.hold(rig, 0, OBSIDIAN + " 64");
        rig.client().enable("Surround");
        Props.await(rig, "the four walls", BUILD_TICKS, () -> Direction.Plane.HORIZONTAL.stream()
            .allMatch(side -> Props.is(rig, home.relative(side), OBSIDIAN)));
    }
}
