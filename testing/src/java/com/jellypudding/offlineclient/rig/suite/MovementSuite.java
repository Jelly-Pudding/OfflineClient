package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigFailure;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.rig.suite.Motion.Moved;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// One case for each movement module. The test player moves by held keys and the server says where
// it ended up. Each case plays in single player and again on a Paper server that checks every
// move and kicks anybody who hangs in the air. A module passes against the plain move measured on
// the same ground a moment before.
public final class MovementSuite implements Suite {

    private static final String NAME = "Movement";
    private static final String TIMER = "Timer runs the game faster";
    private static final String PHASE = "NoClip pushes you through a wall";
    private static final String PHASE_CREATIVE = "NoClip pushes you through a wall in creative";
    private static final String PHASE_REFUSED = "NoClip stays put in survival and says why";
    private static final String CREATIVE = "creative";
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
    private static final int STALL_MILLIS = 1500;
    private static final String STALL = "Flight keeps clear of the kick for floating when the game stalls for a moment";
    private static final String NO_DODGE = "Flight with no dodge of the kick is thrown out in a climb of seven seconds";
    private static final String CLIMB = "Flight climbs for seven seconds in every mode with either dodge of the kick";
    private static final String DIPS = "Flight hovers and drifts through sixteen dips in a row with either dodge of the kick";
    private static final List<String> FLIGHT_MODES = List.of("creative", "direct", "teleport");
    private static final List<String> KICK_DODGES = List.of("dip", "packet");
    private static final int CLIMB_TICKS = 140;
    // With a dip every forty one ticks a single dip the server misses is a kick.
    private static final int TIGHT_INTERVAL = 41;
    private static final int DIPS_IN_A_ROW = 16;
    private static final int DESCENT_LIMIT = 200;
    private static final int GLIDE_TICKS = 40;
    private static final int FLOOD_REACH = 9;
    private static final int VOID_TICKS = 160;
    private static final int FETCH_TICKS = 160;
    private static final double NANOS = 1_000_000_000.0;
    // A trident is ready to throw after ten ticks in the hand.
    private static final int CHARGE_TICKS = 14;
    private static final int THROW_TICKS = 50;
    // The wall begins a block and a bit ahead and is two blocks thick.
    private static final double WALL_AT = 2;
    private static final double BEYOND = 3.5;
    private static final int FAR = 40;
    private static final int CRUISE = 12;
    private static final int FLY_TICKS = 600;
    private static final double ARRIVED = 9;

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
        plays.put(STALL, this::flightThroughAStall);
        plays.put(NO_DODGE, this::flightNoDodge);
        plays.put(CLIMB, this::flightLongClimb);
        plays.put(DIPS, this::flightManyDips);
        plays.put("NoFall takes the hurt out of a deadly fall off a ledge", this::noFallLedge);
        plays.put("NoFall takes the hurt out of a deadly fall that a teleport began", this::noFallTeleport);
        plays.put("NoFall in its Airborne mode takes a deadly fall and charges nothing once it is off",
            this::noFallNoGround);
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
        plays.put("HoleSnap drops a sprint into a safe hole", this::holeSnap);
        plays.put("AntiVoid catches a fall into the void", this::antiVoid);
        plays.put("ElytraBoost fires a rocket whilst you glide", this::elytraBoost);
        plays.put("Fetch brings a far item back", this::fetch);
        plays.put("NoReturn stops you going back to where it went on", this::noReturn);
        plays.put(TIMER, this::timer);
        plays.put("TridentBoost throws you on dry land and further with more boost", this::tridentBoost);
        plays.put(PHASE, this::noClipPhase);
        plays.put(PHASE_CREATIVE, this::noClipCreative);
        plays.put(PHASE_REFUSED, this::noClipRefused);
        plays.put("Autopilot flies you to the spot you name", this::autopilot);
        plays.put("CushionHop sets a cushion on a wall and seats you on it", this::cushionHop);
        return plays;
    }

    @Override
    public void run(Rig rig) {
        Grounds.play(rig, NAME, plays(), stage -> standOn(stage.home()),
            Grounds.Limits.NONE.serverOnly(TIMER, PHASE_CREATIVE, PHASE_REFUSED, STALL, NO_DODGE, CLIMB, DIPS)
                .aloneOnly(PHASE));
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

    // A riptide trident does nothing out of water. The module lets it go and scales the throw.
    private void tridentBoost(Rig rig) {
        double dry = thrown(rig);
        rig.client().set("TridentBoost", "Boost", "1");
        rig.client().enable("TridentBoost");
        double plain = thrown(rig);
        rig.client().set("TridentBoost", "Boost", "2");
        double boosted = thrown(rig);
        goHome(rig);
        rig.note("The trident threw the player " + told(dry) + " blocks on dry land and " + told(plain)
            + " with TridentBoost and " + told(boosted) + " with the boost doubled");
        rig.expect(dry < 1, "The trident threw the player on dry land without TridentBoost. That proves nothing");
        rig.expect(plain >= 5, "The trident did not throw the player on dry land");
        rig.expect(boosted >= plain * 1.3, "A doubled boost threw the player no further");
    }

    // How far the server saw a riptide trident carry the player along the ground.
    private double thrown(Rig rig) {
        goHome(rig);
        Props.hold(rig, 0, "trident[enchantments={\"minecraft:riptide\":3}]");
        return Motion.watch(rig, THROW_TICKS, () -> {
            rig.controls().holdMouse(USE);
            rig.ticks(CHARGE_TICKS);
            rig.controls().releaseMouse(USE);
        }, () -> { }).across();
    }

    // A wall two blocks thick and three high right ahead.
    private Moved pushedAtWall(Rig rig) {
        goHome(rig);
        rig.world().refill(home.offset(-1, 0, -3), home.offset(1, 2, -2), "stone");
        return Motion.hold(rig, RUN_TICKS, FORWARD);
    }

    private void noClipPhase(Rig rig) {
        throughWall(rig);
    }

    private void noClipCreative(Rig rig) {
        rig.world().gameMode(CREATIVE);
        throughWall(rig);
    }

    private void throughWall(Rig rig) {
        Moved stopped = pushedAtWall(rig);
        rig.client().set("NoClip", "Mode", "phase");
        rig.client().enable("NoClip");
        Moved through = pushedAtWall(rig);
        rig.note("A walk at the wall got " + told(stopped.across()) + " blocks and " + told(through.across())
            + " with NoClip");
        rig.expect(stopped.across() < WALL_AT, "The wall did not stop a plain walk. That proves nothing");
        rig.expect(through.across() >= BEYOND, "The push did not come out behind the wall");
    }

    // A Paper server pulls a survival player back out of a wall. The module does not try.
    private void noClipRefused(Rig rig) {
        rig.client().set("NoClip", "Mode", "phase");
        rig.client().enable("NoClip");
        int mark = rig.client().chatMark();
        Moved pushed = pushedAtWall(rig);
        List<String> said = rig.client().saidSince(mark);
        rig.note("The push got " + told(pushed.across()) + " blocks and the client said " + said);
        rig.expect(pushed.across() < WALL_AT, "The push went into the wall");
        rig.expect(!said.isEmpty(), "NoClip gave no reason for staying put");
        expectNoSetback(rig, pushed);
    }

    // A wall three blocks high stands across the way. The crosshair rests on the side of its top
    // block. No cushion stands on a side and it goes on top.
    private void cushionHop(Rig rig) {
        BlockPos top = home.offset(0, 2, -3);
        rig.world().refill(home.offset(-1, 0, -3), home.offset(1, 2, -3), "stone");
        Props.hold(rig, 0, "white_cushion 4");
        rig.controls().aimAt(new Vec3(top.getX() + 0.5, top.getY() + 0.5, top.getZ() + 1));
        rig.ticks(TAP_TICKS);
        rig.client().enable("CushionHop");
        rig.until("the player to sit on a cushion", RUN_TICKS * 2, mc -> mc.player.getVehicle() instanceof Cushion);
        rig.ticks(TAP_TICKS * 5);
        Vec3 held = rig.world().where();
        rig.note("The server has the player " + told(held.y - home.getY()) + " blocks up and "
            + told(home.getZ() + 0.5 - held.z) + " blocks on");
        // A seated player is held a little under the top of the cushion.
        rig.expect(held.y >= top.getY() + 0.5, "The server does not have the player on top of the wall");
        rig.expect(Math.abs(held.z - (top.getZ() + 0.5)) < 1, "The server does not have the player over the wall");
    }

    // Creative flies without a module. The autopilot switches Flight on for the trip all the same.
    private void autopilot(Rig rig) {
        rig.world().gameMode(CREATIVE);
        double x = home.getX() + 0.5 + FAR;
        double z = home.getZ() + 0.5;
        rig.client().set("Autopilot", "Cruise height", String.valueOf(home.getY() + CRUISE));
        rig.client().command("autopilot " + (home.getX() + FAR) + " " + home.getZ());
        try {
            int took = rig.until("the autopilot to reach its spot", FLY_TICKS,
                mc -> Math.hypot(mc.player.getX() - x, mc.player.getZ() - z) <= ARRIVED);
            rig.ticks(RUN_TICKS);
            Vec3 held = rig.world().where();
            double off = Math.hypot(held.x - x, held.z - z);
            rig.note("The flight took " + took + " ticks and the server has the player " + told(off)
                + " blocks from the spot and " + told(held.y - home.getY()) + " over the ground");
            rig.expect(off <= ARRIVED, "The server does not have the player at the spot");
            rig.expect(held.y - home.getY() >= CRUISE / 2.0, "The flight did not keep its height at the end");
        } finally {
            rig.client().disable("Autopilot");
            goHome(rig);
        }
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

    // The server throws out whoever hangs in the air for eighty of its ticks. With a dip every
    // sixty a single dip it misses is a kick. The game stalls for a second and a half across the
    // moment a dip is due and plays it in the burst of ticks that follows. The dip has to last
    // until the server has had a tick of its own to see it.
    private void flightThroughAStall(Rig rig) {
        rig.client().set("Flight", "Kick interval", "60");
        rig.client().enable("Flight");
        Motion.hold(rig, RUN_TICKS / 2, JUMP);
        rig.ticks(RUN_TICKS * 3 / 4);
        rig.act(mc -> {
            try {
                Thread.sleep(STALL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        rig.ticks(HOVER_TICKS);
        Stage.expectAlive(rig);
        double height = rig.ask(mc -> mc.player.getY()) - home.getY();
        rig.client().disable("Flight");
        rig.note("The player still hung " + told(height) + " blocks up " + HOVER_TICKS + " ticks after the stall");
        rig.expect(height >= 1, "Flight never lifted the player. That proves nothing");
    }

    // The climb the dodges are held against. A server that lets it stand with no dodge at all
    // proves nothing about them.
    private void flightNoDodge(Rig rig) {
        rig.client().set("Flight", "AntiKick", "off");
        rig.client().enable("Flight");
        try {
            Motion.hold(rig, CLIMB_TICKS, JUMP);
        } catch (RuntimeException | AssertionError lost) {
            rig.note("The climb ended early");
        }
        boolean thrownOut = rig.ask(mc -> mc.player == null);
        if (!thrownOut) {
            goHome(rig);
            rig.client().disable("Flight");
        }
        rig.expect(thrownOut, "The server let a climb of " + CLIMB_TICKS + " ticks stand with no dodge of the kick");
    }

    // A climb is a hang in the air to the server however fast it goes. Each dodge has to make
    // a real drop of it now and then.
    private void flightLongClimb(Rig rig) {
        for (String mode : FLIGHT_MODES) {
            for (String dodge : KICK_DODGES) {
                String combo = mode + " with " + dodge;
                rig.client().restoreDefaults();
                rig.client().set("Flight", "Mode", mode);
                rig.client().set("Flight", "AntiKick", dodge);
                rig.client().set("Flight", "Rise step", "0.5");
                rig.client().enable("Flight");
                Moved up;
                try {
                    up = Motion.hold(rig, CLIMB_TICKS, JUMP);
                } catch (RuntimeException | AssertionError lost) {
                    throw new RigFailure(combo + " did not last the climb. " + Rig.describe(lost));
                }
                String state = rig.ask(mc -> mc.player == null ? "was thrown out" : "");
                rig.expect(state.isEmpty(), combo + " " + state);
                goHome(rig);
                rig.client().disable("Flight");
                rig.note(combo + " rose " + told(up.rise()) + " blocks");
                rig.expect(up.rise() >= 20, combo + " rose " + told(up.rise()) + " blocks in " + CLIMB_TICKS + " ticks");
            }
        }
    }

    // Every dip has to reach the server. The player hangs still for one round and drifts
    // slowly forward for the next since a player on the move sends a position every tick.
    private void flightManyDips(Rig rig) {
        for (String dodge : KICK_DODGES) {
            for (boolean drifting : List.of(false, true)) {
                String combo = dodge + (drifting ? " on the move" : " hanging still");
                rig.client().restoreDefaults();
                rig.client().set("Flight", "AntiKick", dodge);
                rig.client().set("Flight", "Kick interval", String.valueOf(TIGHT_INTERVAL));
                rig.client().set("Flight", "Horizontal speed", "0.1");
                rig.client().enable("Flight");
                Motion.hold(rig, RUN_TICKS / 2, JUMP);
                Moved hover;
                try {
                    hover = drifting ? Motion.hold(rig, TIGHT_INTERVAL * DIPS_IN_A_ROW, FORWARD)
                        : Motion.hold(rig, TIGHT_INTERVAL * DIPS_IN_A_ROW);
                } catch (RuntimeException | AssertionError lost) {
                    throw new RigFailure(combo + " did not last the hover. " + Rig.describe(lost));
                }
                String state = rig.ask(mc -> mc.player == null ? "was thrown out" : "");
                rig.expect(state.isEmpty(), combo + " " + state);
                double height = rig.ask(mc -> mc.player.getY()) - home.getY();
                goHome(rig);
                rig.client().disable("Flight");
                rig.note(combo + " hung " + told(height) + " blocks up at the end and had drifted " + told(hover.rise())
                    + " blocks up or down and " + told(hover.across()) + " along");
                rig.expect(height >= 1, combo + " never lifted the player. That proves nothing");
                rig.expect(Math.abs(hover.rise()) < 1.5, combo + " did not hold its height. It drifted "
                    + told(hover.rise()) + " blocks");
            }
        }
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

    // The server never hears of the landing and holds the whole fall. Switched off on the
    // ground that fall has to be forgotten before the server is told the truth again.
    private void noFallNoGround(Rig rig) {
        rig.client().set("NoFall", "Mode", "airborne");
        rig.client().enable("NoFall");
        lift(rig, DEADLY_DROP);
        landUnhurt(rig);
        rig.client().set("NoFall", "Mode", "packet");
        walkedUnhurt(rig, "the mode was changed");
        rig.client().set("NoFall", "Mode", "airborne");
        lift(rig, DEADLY_DROP);
        landUnhurt(rig);
        rig.client().disable("NoFall");
        walkedUnhurt(rig, "NoFall was off");
    }

    // The first steps after Airborne tell the server the truth again.
    private static void walkedUnhurt(Rig rig, String when) {
        Motion.hold(rig, RUN_TICKS, FORWARD);
        rig.ticks(TAP_TICKS * 5);
        float health = rig.world().health();
        rig.expect(health == FULL_HEALTH, "The fall was charged once " + when + ". It cost "
            + (FULL_HEALTH - health) + " health");
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

    // A hole one block wide with obsidian all round lies in the way of a sprint.
    private void holeSnap(Rig rig) {
        World world = rig.world();
        BlockPos hole = home.offset(0, -1, -3);
        for (BlockPos wall : java.util.List.of(hole.north(), hole.east(), hole.south(), hole.west(), hole.below())) {
            world.setBlock(wall, "obsidian");
        }
        world.setBlock(hole, "air");
        Moved over = Motion.hold(rig, RUN_TICKS / 2, FORWARD, SPRINT);
        goHome(rig);
        rig.client().enable("HoleSnap");
        Moved into = Motion.hold(rig, RUN_TICKS / 2, FORWARD, SPRINT);
        rig.note("The sprint ended " + told(-into.rise()) + " blocks down with HoleSnap and " + told(-over.rise())
            + " without");
        rig.expect(over.rise() > -0.5, "The sprint fell into the hole without HoleSnap. That proves nothing");
        rig.expect(into.rise() < -0.9 && into.across() < 4.5, "The sprint did not end in the hole");
    }

    // A shaft through the bedrock two blocks ahead.
    private void antiVoid(Rig rig) {
        World world = rig.world();
        BlockPos shaft = home.north(2);
        world.fill(shaft.below(4), shaft.below(), "air");
        rig.client().enable("AntiVoid");
        Motion.hold(rig, RUN_TICKS / 2, FORWARD);
        rig.ticks(VOID_TICKS);
        Stage.expectAlive(rig);
        double depth = home.getY() - rig.world().where().y;
        rig.note("The player hangs " + told(depth) + " blocks under the stage");
        rig.expect(depth < 30, "The player fell on into the void");
    }

    private void elytraBoost(Rig rig) {
        World world = rig.world();
        Props.wear(rig, "armor.chest", "elytra");
        Props.hold(rig, 0, "firework_rocket 16");
        // The rocket is to be a real one the server sees fly.
        rig.client().set("ElytraBoost", "Anti consume", "off");
        rig.client().enable("ElytraBoost");
        world.gameMode("creative");
        lift(rig, HIGH);
        rig.until("the fall to begin", TAP_TICKS * 10, mc -> !mc.player.onGround());
        rig.ticks(TAP_TICKS * 2);
        rig.controls().hold(JUMP);
        rig.ticks(TAP_TICKS);
        rig.controls().release(JUMP);
        rig.until("the wings to open", RUN_TICKS, mc -> mc.player.isFallFlying());
        // A rocket only lifts a player the server has gliding. The game opens its wings a
        // moment before the server does.
        Props.await(rig, "the server to see the glide", RUN_TICKS, () -> world.holds("gliding"));
        rig.expect(rig.ask(mc -> mc.player.isFallFlying()), "The glide ended before the rocket could go");
        rig.client().press("ElytraBoost");
        rig.ticks(TAP_TICKS);
        int rockets = 0;
        for (int look = 0; look < TAP_TICKS * 5 && rockets == 0; look++) {
            rig.ticks(1);
            rockets = Props.living(rig, "firework_rocket");
        }
        goHome(rig);
        world.gameMode(SURVIVAL);
        rig.expect(rockets > 0, "No rocket flew");
    }

    private void fetch(Rig rig) {
        World world = rig.world();
        BlockPos far = home.north(10);
        // The module leaves a drop alone that is too fresh to be picked up.
        Props.summon(rig, "item", far, "{Item:{id:\"minecraft:diamond\",count:3}}");
        rig.ticks(RUN_TICKS * 2);
        int mark = rig.client().chatMark();
        rig.client().enable("Fetch");
        int took;
        try {
            took = Props.await(rig, "the diamonds to be in the pack", FETCH_TICKS,
                () -> Props.carried(rig, "diamond") == 3);
        } finally {
            rig.note("Fetch said " + rig.client().saidSince(mark));
        }
        rig.ticks(RUN_TICKS / 2);
        double away = world.where().distanceTo(Vec3.atBottomCenterOf(home));
        rig.note("The diamonds were in the pack after about " + took + " ticks and the player stands " + told(away)
            + " blocks from where it began");
        rig.expect(away < 1.5, "The player did not come back");
    }

    private void timer(Rig rig) {
        double plain = ticksASecond(rig);
        rig.client().enable("Timer");
        rig.ticks(TAP_TICKS * 5);
        double fast = ticksASecond(rig);
        rig.note("The game ran " + told(fast) + " ticks a second against " + told(plain));
        rig.expect(fast >= plain * 1.6, "Timer ran the game no faster");
    }

    private static double ticksASecond(Rig rig) {
        long began = System.nanoTime();
        int from = rig.ask(mc -> mc.player.tickCount);
        rig.ticks(RUN_TICKS * 2);
        int ticks = rig.ask(mc -> mc.player.tickCount) - from;
        return ticks / ((System.nanoTime() - began) / NANOS);
    }

    // A sprint south and a longer sprint back north. Blocks south of the start the player ends up.
    private void noReturn(Rig rig) {
        double free = thereAndBack(rig);
        goHome(rig);
        // An area an earlier case closed on this server stays closed and keeps the radius it got.
        rig.client().command("noreturn clear");
        // One chunk round the spot stays closed. Left alone it would be as far as the eye sees.
        rig.client().set("NoReturn", "Radius", "1");
        rig.client().enable("NoReturn");
        double held = thereAndBack(rig);
        rig.note("The way back ended " + told(held) + " blocks short of the start and without NoReturn " + told(free));
        rig.expect(free < 5, "The way back did not reach the start without NoReturn. That proves nothing");
        rig.expect(held >= 10, "The player got back to where NoReturn went on");
    }

    // The way out ends well past the two chunks that stay closed south of the start.
    private double thereAndBack(Rig rig) {
        rig.controls().look(0, 0);
        Motion.hold(rig, RUN_TICKS * 6, FORWARD, SPRINT);
        rig.controls().look(180, 0);
        Motion.hold(rig, RUN_TICKS * 7, FORWARD, SPRINT);
        return rig.world().where().z - (home.getZ() + 0.5);
    }
}
