package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.rig.suite.Motion.Moved;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

// One case for each movement module. The test player moves by held keys and the server says where
// it ended up. Each case plays in single player and again on a Paper server that checks every
// move and kicks anybody who hangs in the air. A module passes against the plain move measured on
// the same ground a moment before.
public final class MovementSuite implements Suite {

    private static final String NAME = "Movement";
    private static final String SURVIVAL = "survival";

    private static final int FORWARD = InputConstants.KEY_W;
    private static final int JUMP = InputConstants.KEY_SPACE;
    private static final int SNEAK = InputConstants.KEY_LSHIFT;
    private static final int SPRINT = InputConstants.KEY_LCONTROL;
    private static final int USE = InputConstants.MOUSE_BUTTON_RIGHT;

    // A second and a half of holding a key. Long enough to tell one pace from another.
    private static final int RUN_TICKS = 30;
    private static final int TAP_TICKS = 2;
    private static final int JUMP_TICKS = 30;
    private static final int HIGH = 60;
    private static final int DROP_TICKS = 20;
    private static final int DEADLY_DROP = 30;
    private static final float FULL_HEALTH = 20;
    private static final int HOVER_TICKS = 110;
    private static final int DESCENT_LIMIT = 200;
    private static final int GLIDE_TICKS = 40;
    private static final int FLOOD_REACH = 9;

    // The plain moves of the ground a case stands on. Every module is held against these.
    private record Paces(double walked, double sprinted, double sprintJumped, double jumped, double dropped) {
    }

    private BlockPos home;
    private Paces paces;

    @Override
    public String name() {
        return NAME;
    }

    // The ground the cases stand on. Its plain paces are measured anew.
    void standOn(BlockPos stageHome) {
        home = stageHome;
        paces = null;
    }

    Map<String, Case.Body> plays() {
        Map<String, Case.Body> plays = new LinkedHashMap<>();
        plays.put("Sprint runs without the sprint key", this::sprint);
        plays.put("Speed outruns a sprint", this::speed);
        plays.put("Speed in BHop mode hops and outruns a walk", this::bhop);
        plays.put("Flight rises and hovers and flies on and lands unhurt with NoFall", this::flight);
        plays.put("NoFall takes the hurt out of a deadly fall off a ledge", this::noFallLedge);
        plays.put("NoFall takes the hurt out of a deadly fall that a teleport began", this::noFallTeleport);
        plays.put("Step walks up a block without a jump", this::step);
        plays.put("HighJump jumps higher", this::highJump);
        plays.put("LongJump jumps further", this::longJump);
        plays.put("Jesus walks over water", this::jesus);
        plays.put("Spider climbs a wall", this::spider);
        plays.put("AirJump jumps again in the air", this::airJump);
        plays.put("Glide falls slower", this::glide);
        plays.put("FastFall falls faster", this::fastFall);
        plays.put("NoSlowdown keeps the pace whilst eating", this::noSlowdown);
        plays.put("Sneak keeps the server told that you sneak", this::sneak);
        plays.put("EdgeGuard stops at a drop", this::edgeGuard);
        plays.put("AutoJump keeps a sprint hopping", this::autoJump);
        plays.put("Parkour jumps a gap", this::parkour);
        plays.put("AutoWalk walks with no key held", this::autoWalk);
        plays.put("Blink holds the server's view still", this::blink);
        plays.put("QuickClimb climbs a ladder faster", this::quickClimb);
        plays.put("NoJumpDelay jumps more often", this::noJumpDelay);
        plays.put("ElytraFly holds its height on wings", this::elytraFly);
        plays.put("Boost dashes forward on its key", this::boost);
        plays.put("Slippy slides on after the keys are let go", this::slippy);
        plays.put("VehicleFly lifts the boat you sit in", this::vehicleFly);
        plays.put("AntiPush stands firm in a current", this::antiPush);
        plays.put("NoKnockback holds you still when a mob hits you", this::noKnockback);
        plays.put("Freecam flies the camera and leaves the body where it stands", this::freecam);
        plays.put("WindJump jumps on a wind charge", this::windJump);
        return plays;
    }

    @Override
    public void run(Rig rig) {
        Grounds.play(rig, NAME, plays(), stage -> standOn(stage.home()));
    }

    // Measured once on each ground with no module on.
    private Paces paces(Rig rig) {
        if (paces == null) {
            double walked = Motion.hold(rig, RUN_TICKS, FORWARD).across();
            goHome(rig);
            double sprinted = Motion.hold(rig, RUN_TICKS, FORWARD, SPRINT).across();
            goHome(rig);
            double sprintJumped = Motion.hold(rig, RUN_TICKS, FORWARD, SPRINT, JUMP).across();
            goHome(rig);
            double jumped = jump(rig).highest();
            goHome(rig);
            paces = new Paces(walked, sprinted, sprintJumped, jumped, drop(rig));
            goHome(rig);
        }
        return paces;
    }

    private void goHome(Rig rig) {
        rig.world().teleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 180, 0);
    }

    private static Moved jump(Rig rig) {
        return Motion.watch(rig, JUMP_TICKS, () -> {
            rig.controls().hold(JUMP);
            rig.ticks(TAP_TICKS);
            rig.controls().release(JUMP);
        }, () -> { });
    }

    // Blocks the player drops in a second from rest high in the air. It is put there in
    // creative where no fall hurts.
    private double drop(Rig rig) {
        World world = rig.world();
        world.gameMode("creative");
        lift(rig, HIGH);
        rig.until("the fall to begin", TAP_TICKS * 10, mc -> !mc.player.onGround());
        double from = rig.ask(mc -> mc.player.getY());
        rig.ticks(DROP_TICKS);
        double dropped = from - rig.ask(mc -> mc.player.getY());
        goHome(rig);
        world.gameMode(SURVIVAL);
        return dropped;
    }

    // Puts the player that many blocks straight above its home with nothing under it.
    private void lift(Rig rig, int height) {
        World world = rig.world();
        world.command("tp " + world.player() + " " + (home.getX() + 0.5) + " " + (home.getY() + height) + " "
            + (home.getZ() + 0.5));
    }

    private static String told(double blocks) {
        return String.format(Locale.ROOT, "%.1f", blocks);
    }

    private static void expectNoSetback(Rig rig, Moved moved) {
        rig.expect(moved.setbacks() == 0, "The server put the player back " + moved.setbacks() + " times");
    }

    private void sprint(Rig rig) {
        Paces plain = paces(rig);
        rig.client().enable("Sprint");
        Moved moved = Motion.hold(rig, RUN_TICKS, FORWARD);
        rig.note(told(moved.across()) + " blocks against " + told(plain.sprinted()) + " at a sprint and "
            + told(plain.walked()) + " at a walk");
        rig.expect(moved.across() >= plain.sprinted() * 0.9, "Sprint did not reach the pace of a sprint");
        expectNoSetback(rig, moved);
    }

    private void speed(Rig rig) {
        Paces plain = paces(rig);
        rig.client().enable("Speed");
        Moved moved = Motion.hold(rig, RUN_TICKS, FORWARD);
        rig.note(told(moved.across()) + " blocks against " + told(plain.sprinted()) + " at a sprint");
        rig.expect(moved.across() >= plain.sprinted() * 1.3, "Speed did not outrun a sprint");
        expectNoSetback(rig, moved);
    }

    private void bhop(Rig rig) {
        Paces plain = paces(rig);
        rig.client().set("Speed", "Mode", "bhop");
        rig.client().enable("Speed");
        Moved moved = Motion.hold(rig, RUN_TICKS, FORWARD);
        rig.note(told(moved.across()) + " blocks with " + moved.takeOffs() + " hops against "
            + told(plain.sprinted()) + " at a sprint");
        rig.expect(moved.takeOffs() >= 2, "Speed did not hop");
        rig.expect(moved.across() >= plain.walked() * 1.3, "The hops did not outrun a walk");
        expectNoSetback(rig, moved);
    }

    // A server that bans flight kicks whoever floats for four seconds. The hover is longer.
    private void flight(Rig rig) {
        Paces plain = paces(rig);
        rig.client().enable("NoFall");
        rig.client().enable("Flight");
        Moved up = Motion.hold(rig, RUN_TICKS, JUMP);
        Moved hover = Motion.hold(rig, HOVER_TICKS);
        Moved along = Motion.hold(rig, RUN_TICKS, FORWARD);
        rig.controls().hold(SNEAK);
        rig.until("the flight to come down", DESCENT_LIMIT, mc -> mc.player.onGround());
        rig.controls().release(SNEAK);
        rig.client().disable("Flight");
        rig.ticks(TAP_TICKS * 5);
        rig.note("It rose " + told(up.rise()) + " blocks and drifted " + told(hover.rise()) + " in a hover of "
            + HOVER_TICKS + " ticks and flew " + told(along.across()) + " blocks against " + told(plain.sprinted())
            + " at a sprint");
        rig.note("It landed with " + rig.ask(mc -> mc.player.getHealth()) + " health");
        Stage.expectAlive(rig);
        rig.expect(up.rise() >= 3, "Flight did not rise");
        rig.expect(Math.abs(hover.rise()) < 1.5, "Flight did not hold its height");
        rig.expect(along.across() >= plain.sprinted() * 1.2, "Flight was no faster than a sprint");
        rig.expect(rig.world().health() == FULL_HEALTH, "The flight cost health");
        rig.expect(up.setbacks() + hover.setbacks() + along.setbacks() == 0, "The server put the flight back");
    }

    // The player stands on one block high in the air and the block is taken away.
    private void noFallLedge(Rig rig) {
        World world = rig.world();
        BlockPos ledge = home.above(DEADLY_DROP - 1);
        world.setBlock(ledge, "stone");
        world.teleport(home.getX() + 0.5, home.getY() + DEADLY_DROP, home.getZ() + 0.5, 180, 0);
        rig.client().enable("NoFall");
        rig.ticks(RUN_TICKS);
        world.setBlock(ledge, "air");
        landUnhurt(rig);
    }

    private void noFallTeleport(Rig rig) {
        rig.client().enable("NoFall");
        lift(rig, DEADLY_DROP);
        landUnhurt(rig);
    }

    private static void landUnhurt(Rig rig) {
        int fell = Motion.untilLanded(rig);
        rig.ticks(TAP_TICKS * 5);
        Stage.expectAlive(rig);
        float health = rig.world().health();
        rig.note("Fell " + DEADLY_DROP + " blocks in " + fell + " ticks and has " + health + " health");
        rig.expect(health == FULL_HEALTH, "The fall cost " + (FULL_HEALTH - health) + " health");
    }

    private void step(Rig rig) {
        paces(rig);
        rig.world().fill(home.offset(-1, 0, -6), home.offset(1, 0, -2), "stone");
        rig.client().enable("Step");
        Moved moved = Motion.hold(rig, RUN_TICKS, FORWARD);
        rig.note("It rose " + told(moved.rise()) + " blocks with " + moved.airTicks() + " ticks off the ground");
        rig.expect(moved.rise() >= 0.99, "Step did not walk up the block");
        rig.expect(moved.takeOffs() == 0 || moved.airTicks() <= 3, "The player jumped up the block");
    }

    private void highJump(Rig rig) {
        Paces plain = paces(rig);
        rig.client().enable("HighJump");
        double jumped = jump(rig).highest();
        rig.note("It jumped " + told(jumped) + " blocks against " + told(plain.jumped()));
        rig.expect(jumped >= plain.jumped() + 0.4, "HighJump jumped no higher");
    }

    private void longJump(Rig rig) {
        Paces plain = paces(rig);
        rig.client().enable("LongJump");
        Moved moved = Motion.hold(rig, RUN_TICKS, FORWARD, SPRINT, JUMP);
        rig.note(told(moved.across()) + " blocks against " + told(plain.sprintJumped()) + " in plain sprint jumps");
        rig.expect(moved.across() >= plain.sprintJumped() * 1.2, "LongJump jumped no further");
    }

    private void jesus(Rig rig) {
        paces(rig);
        rig.world().fill(home.offset(-1, -1, -6), home.offset(1, -1, -2), "water");
        rig.client().enable("Jesus");
        Moved moved = Motion.hold(rig, RUN_TICKS + RUN_TICKS / 2, FORWARD);
        rig.note("It crossed " + told(moved.across()) + " blocks and sank " + told(-moved.lowest()) + " at the most");
        rig.expect(moved.lowest() >= -0.4, "The player sank into the water");
        rig.expect(moved.across() >= 6.5, "The player did not get across the water");
    }

    private void spider(Rig rig) {
        paces(rig);
        rig.world().fill(home.offset(-1, 0, -3), home.offset(1, 4, -3), "stone");
        rig.client().enable("Spider");
        Moved moved = Motion.hold(rig, RUN_TICKS * 3, FORWARD);
        rig.note("It climbed " + told(moved.highest()) + " blocks");
        rig.expect(moved.highest() >= 4.5, "Spider did not climb the wall");
    }

    private void airJump(Rig rig) {
        Paces plain = paces(rig);
        rig.client().enable("AirJump");
        Moved moved = Motion.watch(rig, JUMP_TICKS, () -> {
            for (int tap = 0; tap < 3; tap++) {
                rig.controls().hold(JUMP);
                rig.ticks(TAP_TICKS);
                rig.controls().release(JUMP);
                rig.ticks(TAP_TICKS * 3);
            }
        }, () -> { });
        rig.note("Three taps took it " + told(moved.highest()) + " blocks up against " + told(plain.jumped())
            + " for one jump");
        rig.expect(moved.highest() >= plain.jumped() * 1.8, "AirJump did not jump again in the air");
    }

    private void glide(Rig rig) {
        Paces plain = paces(rig);
        rig.client().enable("Glide");
        double dropped = drop(rig);
        rig.note("It dropped " + told(dropped) + " blocks in a second against " + told(plain.dropped()));
        rig.expect(dropped <= plain.dropped() * 0.6, "Glide fell no slower");
    }

    private void fastFall(Rig rig) {
        Paces plain = paces(rig);
        rig.client().enable("FastFall");
        double dropped = drop(rig);
        rig.note("It dropped " + told(dropped) + " blocks in a second against " + told(plain.dropped()));
        rig.expect(dropped >= plain.dropped() * 1.1, "FastFall fell no faster");
    }

    // A golden apple can be eaten on a full stomach. Eating slows a walk to a fifth.
    private void noSlowdown(Rig rig) {
        Paces plain = paces(rig);
        rig.world().hotbar(0, "golden_apple 8");
        rig.act(mc -> mc.player.getInventory().setSelectedSlot(0));
        rig.ticks(TAP_TICKS * 2);
        rig.client().enable("NoSlowdown");
        rig.controls().holdMouse(USE);
        Moved moved = Motion.hold(rig, RUN_TICKS, FORWARD);
        rig.controls().releaseMouse(USE);
        rig.note(told(moved.across()) + " blocks whilst eating against " + told(plain.walked()) + " at a walk");
        rig.expect(moved.across() >= plain.walked() * 0.85, "Eating slowed the walk");
        expectNoSetback(rig, moved);
    }

    private void sneak(Rig rig) {
        rig.client().enable("Sneak");
        rig.ticks(TAP_TICKS * 5);
        rig.expect(rig.world().holds("sneaking"), "The server does not hold the player as sneaking");
        rig.client().disable("Sneak");
        rig.ticks(TAP_TICKS * 5);
        rig.expect(!rig.world().holds("sneaking"), "The server still holds the player as sneaking with Sneak off");
    }

    // A pit three deep lies ahead.
    private void pit(Rig rig) {
        rig.world().fill(home.offset(-1, -3, -4), home.offset(1, -1, -3), "air");
    }

    private void edgeGuard(Rig rig) {
        paces(rig);
        pit(rig);
        rig.client().enable("EdgeGuard");
        Moved moved = Motion.hold(rig, RUN_TICKS + RUN_TICKS / 2, FORWARD);
        rig.note("It walked " + told(moved.across()) + " blocks and dropped " + told(-moved.lowest()));
        rig.expect(moved.lowest() >= -0.2, "The player went over the edge");
    }

    private void autoJump(Rig rig) {
        paces(rig);
        rig.client().enable("AutoJump");
        Moved moved = Motion.hold(rig, RUN_TICKS + RUN_TICKS / 2, FORWARD, SPRINT);
        rig.note(moved.takeOffs() + " jumps in " + (RUN_TICKS + RUN_TICKS / 2) + " ticks of sprinting");
        rig.expect(moved.takeOffs() >= 2, "AutoJump did not jump");
    }

    private void parkour(Rig rig) {
        paces(rig);
        pit(rig);
        rig.client().enable("Parkour");
        Moved moved = Motion.hold(rig, RUN_TICKS + RUN_TICKS / 2, FORWARD, SPRINT);
        rig.note("It ran " + told(moved.across()) + " blocks and dropped " + told(-moved.lowest()));
        rig.expect(moved.lowest() >= -0.2, "The player fell into the gap");
        rig.expect(moved.across() >= 6, "The player did not get past the gap");
    }

    private void autoWalk(Rig rig) {
        Paces plain = paces(rig);
        rig.client().enable("AutoWalk");
        Moved moved = Motion.hold(rig, RUN_TICKS);
        rig.client().disable("AutoWalk");
        rig.note(told(moved.across()) + " blocks with no key held against " + told(plain.walked()) + " at a walk");
        rig.expect(moved.across() >= plain.walked() * 0.8, "AutoWalk did not walk");
    }

    private void blink(Rig rig) {
        paces(rig);
        rig.client().enable("Blink");
        Moved held = Motion.hold(rig, RUN_TICKS, FORWARD);
        Vec3 seen = rig.ask(mc -> mc.player.position());
        double walked = seen.subtract(Vec3.atBottomCenterOf(home)).horizontalDistance();
        rig.client().disable("Blink");
        // The held walk goes out a tick at a time and takes as long as it was held for.
        rig.ticks(RUN_TICKS * 2);
        double apart = rig.world().where().distanceTo(seen);
        rig.note("The game walked " + told(walked) + " blocks whilst the server saw " + told(held.across())
            + " and afterwards the two were " + told(apart) + " apart");
        rig.expect(walked >= 3, "The player did not walk");
        rig.expect(held.across() < 0.5, "The server saw the walk whilst Blink was on");
        rig.expect(apart < 0.5, "The server did not catch up once Blink was off");
    }

    // A ladder up a stone column right in front of the player.
    private void quickClimb(Rig rig) {
        paces(rig);
        rig.world().fill(home.offset(0, 0, -2), home.offset(0, 12, -2), "stone");
        rig.world().fill(home.offset(0, 0, -1), home.offset(0, 12, -1), "ladder[facing=south]");
        double plain = Motion.hold(rig, RUN_TICKS, FORWARD).highest();
        goHome(rig);
        rig.client().enable("QuickClimb");
        double quick = Motion.hold(rig, RUN_TICKS, FORWARD).highest();
        rig.note("It climbed " + told(quick) + " blocks against " + told(plain));
        rig.expect(quick >= plain * 1.25, "QuickClimb climbed no faster");
    }

    // Under a low ceiling a jump is over in a few ticks and the game makes the next one wait.
    private void noJumpDelay(Rig rig) {
        paces(rig);
        rig.world().fill(home.offset(-1, 2, -1), home.offset(1, 2, 1), "glass");
        int plain = Motion.hold(rig, RUN_TICKS * 2, JUMP).takeOffs();
        goHome(rig);
        rig.client().enable("NoJumpDelay");
        int quick = Motion.hold(rig, RUN_TICKS * 2, JUMP).takeOffs();
        rig.note(quick + " jumps in " + RUN_TICKS * 2 + " ticks against " + plain);
        rig.expect(quick > plain, "NoJumpDelay jumped no more often");
    }

    // Wings alone lose height all the way down a glide.
    private void elytraFly(Rig rig) {
        double plain = glided(rig);
        rig.client().enable("ElytraFly");
        double flown = glided(rig);
        rig.note("A glide lost " + told(plain) + " blocks of height on wings alone and " + told(flown)
            + " with ElytraFly");
        rig.expect(plain >= 2, "Wings alone lost no height. That proves nothing");
        rig.expect(flown <= 1.5 && flown <= plain - 2, "ElytraFly lost " + told(flown) + " blocks of height");
    }

    // Blocks of height a glide straight ahead loses. It starts high up in creative where no
    // landing hurts.
    private double glided(Rig rig) {
        World world = rig.world();
        Props.wear(rig, "armor.chest", "elytra");
        world.gameMode("creative");
        lift(rig, HIGH);
        rig.until("the fall to begin", TAP_TICKS * 10, mc -> !mc.player.onGround());
        rig.ticks(TAP_TICKS * 2);
        rig.controls().hold(JUMP);
        rig.ticks(TAP_TICKS);
        rig.controls().release(JUMP);
        rig.until("the wings to open", RUN_TICKS, mc -> mc.player.isFallFlying());
        double from = rig.ask(mc -> mc.player.getY());
        Motion.hold(rig, GLIDE_TICKS, FORWARD);
        double lost = from - rig.ask(mc -> mc.player.getY());
        goHome(rig);
        world.gameMode(SURVIVAL);
        return lost;
    }

    private void boost(Rig rig) {
        Moved moved = Motion.watch(rig, TAP_TICKS * 8, () -> rig.client().press("Boost"), () -> { });
        rig.note("The dash covered " + told(moved.across()) + " blocks");
        rig.expect(moved.across() >= 2, "Boost did not dash");
    }

    private void slippy(Rig rig) {
        double plain = slid(rig);
        goHome(rig);
        rig.client().enable("Slippy");
        double slippery = slid(rig);
        rig.note("The player slid " + told(slippery) + " blocks on against " + told(plain));
        rig.expect(slippery >= plain + 1, "Slippy slid no further");
    }

    // Blocks the player slides on once the keys of a sprint are let go.
    private static double slid(Rig rig) {
        rig.controls().hold(FORWARD);
        rig.controls().hold(SPRINT);
        rig.ticks(RUN_TICKS / 2);
        Vec3 letGo = rig.ask(mc -> mc.player.position());
        rig.controls().release(FORWARD);
        rig.controls().release(SPRINT);
        rig.ticks(RUN_TICKS);
        return rig.world().where().subtract(letGo).horizontalDistance();
    }

    private void vehicleFly(Rig rig) {
        World world = rig.world();
        Props.summon(rig, "oak_boat", home.north(2), "{}");
        world.command("ride " + world.player() + " mount @e[type=oak_boat,limit=1]");
        rig.until("the player to sit in the boat", RUN_TICKS, mc -> mc.player.isPassenger());
        rig.client().enable("VehicleFly");
        double from = world.where().y;
        rig.controls().hold(JUMP);
        rig.ticks(RUN_TICKS);
        rig.controls().release(JUMP);
        double rose = world.where().y - from;
        rig.client().disable("VehicleFly");
        world.command("ride " + world.player() + " dismount");
        rig.note("The boat rose " + told(rose) + " blocks");
        rig.expect(rose >= 2, "VehicleFly did not lift the boat");
    }

    private void antiPush(Rig rig) {
        double carried = carriedOff(rig);
        rig.client().enable("AntiPush");
        double held = carriedOff(rig);
        rig.note("The current carried the player " + told(carried) + " blocks and with AntiPush " + told(held));
        rig.expect(carried >= 0.5, "The current did not carry the player without AntiPush. That proves nothing");
        rig.expect(held <= 0.1, "The current still carried the player off");
    }

    // A spring three blocks ahead floods the stage for three seconds. Blocks its current took the
    // player from its place.
    private double carriedOff(Rig rig) {
        World world = rig.world();
        world.setBlock(home.north(3), "water");
        rig.ticks(RUN_TICKS * 2);
        double carried = world.where().subtract(Vec3.atBottomCenterOf(home)).horizontalDistance();
        world.setBlock(home.north(3), "air");
        world.refill(home.offset(-FLOOD_REACH, 0, -FLOOD_REACH - 3), home.offset(FLOOD_REACH, 0, FLOOD_REACH), "air");
        goHome(rig);
        return carried;
    }

    private void noKnockback(Rig rig) {
        Props.summon(rig, "husk", home.north(2), Props.STILL_MOB);
        double pushed = hitByMob(rig);
        goHome(rig);
        rig.client().enable("NoKnockback");
        double held = hitByMob(rig);
        rig.note("The hit pushed the player " + told(pushed) + " blocks and with NoKnockback " + told(held));
        rig.expect(pushed >= 0.3, "The hit did not push the player without NoKnockback. That proves nothing");
        rig.expect(held <= 0.05, "The hit still pushed the player");
    }

    // The server deals a hit in the name of the husk. Blocks it moved the player.
    private static double hitByMob(Rig rig) {
        World world = rig.world();
        Vec3 from = world.where();
        world.command("damage " + world.player() + " 1 minecraft:mob_attack by @e[type=husk,limit=1]");
        rig.ticks(RUN_TICKS / 2);
        return world.where().subtract(from).horizontalDistance();
    }

    private void freecam(Rig rig) {
        Vec3 body = rig.world().where();
        rig.client().enable("Freecam");
        rig.controls().hold(FORWARD);
        rig.ticks(RUN_TICKS);
        rig.controls().release(FORWARD);
        double flown = rig.ask(mc -> mc.gameRenderer.mainCamera().position().distanceTo(mc.player.getEyePosition()));
        double moved = rig.world().where().distanceTo(body);
        rig.client().disable("Freecam");
        rig.ticks(TAP_TICKS * 3);
        double back = rig.ask(mc -> mc.gameRenderer.mainCamera().position().distanceTo(mc.player.getEyePosition()));
        rig.note("The camera flew " + told(flown) + " blocks from the body and the body moved " + told(moved));
        rig.expect(flown >= 3, "The camera did not fly");
        rig.expect(moved < 0.05, "The body moved with the camera");
        rig.expect(back < 0.5, "The camera did not come back to the body");
    }

    private void windJump(Rig rig) {
        Paces plain = paces(rig);
        Props.hold(rig, 0, "wind_charge 8");
        rig.client().enable("WindJump");
        double jumped = jump(rig).highest();
        rig.note("It jumped " + told(jumped) + " blocks against " + told(plain.jumped()));
        rig.expect(jumped >= plain.jumped() + 2, "WindJump jumped no higher");
    }
}
