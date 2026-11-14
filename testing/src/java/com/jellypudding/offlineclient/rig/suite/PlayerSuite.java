package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.rig.suite.Motion.Moved;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import java.util.LinkedHashMap;
import java.util.Map;

// One case for each module that looks after the player. It is made hungry or hurt or set on
// fire or killed and the server says what the module did about it.
public final class PlayerSuite implements Suite {

    private static final String NAME = "Player";
    private static final int FORWARD = InputConstants.KEY_W;

    private static final String BEEF = "cooked_beef";
    private static final String APPLE = "golden_apple";
    private static final String CHEST = "armor.chest";
    private static final String ELYTRA = "elytra";
    private static final float FULL_HEALTH = 20;
    private static final float NORTH = 180;
    private static final float TURNED = 77;
    private static final double TURN_NEAR = 2;

    private static final int SEEN_TICKS = 10;
    private static final int WALK_TICKS = 40;
    private static final int ACT_TICKS = 100;
    private static final int EAT_TICKS = 400;
    private static final int BEEF_GIVEN = 16;
    private static final int APPLES_GIVEN = 8;
    private static final int FEW = 4;
    private static final int REFILLED = 9;
    private static final int BOTTLES = 64;
    private static final int THROW_TICKS = 20;
    private static final int USE = InputConstants.MOUSE_BUTTON_RIGHT;

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
        plays.put("AutoEat eats once you are hungry", this::autoEat);
        plays.put("AutoGap eats a golden apple once you are hurt", this::autoGap);
        plays.put("AutoExtinguish puts you out", this::autoExtinguish);
        plays.put("AutoRespawn brings you back with no death screen", this::autoRespawn);
        plays.put("AutoReplenish tops a hotbar stack up from the backpack", this::autoReplenish);
        plays.put("AutoDrop throws junk out of the backpack", this::autoDrop);
        plays.put("ChestSwap swaps the chestplate for the elytra", this::chestSwap);
        plays.put("AutoSleep goes to bed at night", this::autoSleep);
        plays.put("AutoDoors opens the door in your way", this::autoDoors);
        plays.put("AntiCactus keeps a cactus from hurting you", this::antiCactus);
        plays.put("NoRotate keeps your view when the server turns your head", this::noRotate);
        plays.put("FastUse throws bottles faster", this::fastUse);
        return plays;
    }

    // A strong hunger effect empties the food bar in a few seconds.
    private void autoEat(Rig rig) {
        World world = rig.world();
        Props.hold(rig, 0, BEEF + " " + BEEF_GIVEN);
        rig.client().enable("AutoEat");
        world.command("effect give " + world.player() + " hunger 30 255 true");
        int took = Props.await(rig, "the first steak to be eaten", EAT_TICKS,
            () -> Props.carried(rig, BEEF) < BEEF_GIVEN);
        rig.note("The first steak was eaten after about " + took + " ticks");
    }

    private void autoGap(Rig rig) {
        World world = rig.world();
        Props.hold(rig, 0, APPLE + " " + APPLES_GIVEN);
        rig.client().enable("AutoGap");
        world.command("damage " + world.player() + " 9");
        int took = Props.await(rig, "a golden apple to be eaten", ACT_TICKS * 2,
            () -> Props.carried(rig, APPLE) < APPLES_GIVEN);
        rig.note("An apple was eaten about " + took + " ticks after the hurt");
    }

    // A fire lit in the player's own block. Left alone a player burns for eight seconds after it.
    private void autoExtinguish(Rig rig) {
        Props.hold(rig, 0, "water_bucket");
        rig.client().enable("AutoExtinguish");
        rig.world().setBlock(home, "fire");
        rig.until("the player to catch fire", ACT_TICKS, mc -> mc.player.isOnFire());
        int took = rig.until("the player to be put out", ACT_TICKS, mc -> !mc.player.isOnFire());
        rig.note("The player burnt for " + took + " ticks");
    }

    private void autoRespawn(Rig rig) {
        World world = rig.world();
        rig.client().enable("AutoRespawn");
        world.command("kill " + world.player());
        rig.ticks(SEEN_TICKS);
        rig.until("the player to live again", ACT_TICKS, mc -> mc.player != null && mc.player.isAlive()
            && !(mc.gui.screen() instanceof DeathScreen));
        rig.ticks(SEEN_TICKS);
        rig.expect(world.health() == FULL_HEALTH, "The server has the player at " + world.health() + " health");
    }

    private void autoReplenish(Rig rig) {
        Props.hold(rig, 0, "cobblestone " + FEW);
        Props.pack(rig, 0, "cobblestone 64");
        rig.client().enable("AutoReplenish");
        rig.until("the stack in the hand to be topped up", ACT_TICKS,
            mc -> mc.player.getInventory().getItem(0).getCount() >= REFILLED);
    }

    private void autoDrop(Rig rig) {
        Props.pack(rig, 3, "rotten_flesh 10");
        rig.client().enable("AutoDrop");
        Props.await(rig, "the junk to be thrown out", ACT_TICKS, () -> Props.carried(rig, "rotten_flesh") == 0);
        rig.expect(Props.living(rig, "item") >= 1, "The junk left the backpack and nothing lies on the ground");
    }

    private void chestSwap(Rig rig) {
        Props.wear(rig, CHEST, "diamond_chestplate");
        Props.pack(rig, 2, ELYTRA);
        rig.ticks(SEEN_TICKS);
        rig.client().press("ChestSwap");
        Props.await(rig, "the elytra to be worn", ACT_TICKS, () -> Props.holds(rig, CHEST, ELYTRA));
    }

    private void autoSleep(Rig rig) {
        World world = rig.world();
        world.setBlock(home.north(2), "red_bed[facing=north,part=foot]");
        world.setBlock(home.north(3), "red_bed[facing=north,part=head]");
        world.command("time set midnight");
        try {
            rig.client().enable("AutoSleep");
            rig.until("the player to lie in the bed", ACT_TICKS, mc -> mc.player.isSleeping());
        } finally {
            world.command("time set noon");
        }
    }

    // A door in a wall right ahead. The walk gets through it or stops at it.
    private void autoDoors(Rig rig) {
        World world = rig.world();
        double stopped = walkedAtDoor(rig);
        world.teleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, NORTH, 0);
        rig.client().enable("AutoDoors");
        double through = walkedAtDoor(rig);
        rig.note("The walk got " + through + " blocks with AutoDoors and " + stopped + " without");
        rig.expect(stopped <= 2.5, "The shut door did not stop the walk. That proves nothing");
        rig.expect(through >= 4, "The walk did not get through the door");
    }

    private double walkedAtDoor(Rig rig) {
        World world = rig.world();
        world.fill(home.offset(-2, 0, -2), home.offset(2, 1, -2), "stone");
        world.setBlock(home.offset(0, 1, -2), "air");
        world.setBlock(home.offset(0, 0, -2), "oak_door[half=lower,facing=south]");
        world.setBlock(home.offset(0, 1, -2), "oak_door[half=upper,facing=south]");
        Moved moved = Motion.hold(rig, WALK_TICKS, FORWARD);
        return Math.round(moved.across() * 10) / 10.0;
    }

    // A cactus on sand right ahead and half a second of walking into it.
    private void antiCactus(Rig rig) {
        World world = rig.world();
        world.setBlock(home.offset(0, -1, -1), "sand");
        world.setBlock(home.north(), "cactus");
        Motion.hold(rig, WALK_TICKS / 2, FORWARD);
        float pricked = world.health();
        world.command("effect give " + world.player() + " instant_health 1 20 true");
        world.teleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, NORTH, 0);
        rig.client().enable("AntiCactus");
        Motion.hold(rig, WALK_TICKS / 2, FORWARD);
        float guarded = world.health();
        rig.note("The cactus left " + pricked + " health and with AntiCactus " + guarded);
        rig.expect(pricked < FULL_HEALTH, "The cactus did not hurt without AntiCactus. That proves nothing");
        rig.expect(guarded == FULL_HEALTH, "The cactus still hurt");
    }

    private void noRotate(Rig rig) {
        World world = rig.world();
        String turn = "tp " + world.player() + " " + (home.getX() + 0.5) + " " + home.getY() + " " + (home.getZ() + 0.5)
            + " " + TURNED + " 0";
        world.command(turn);
        rig.ticks(SEEN_TICKS);
        float obeyed = rig.ask(mc -> mc.player.getYRot());
        rig.controls().look(NORTH, 0);
        rig.client().enable("NoRotate");
        rig.ticks(SEEN_TICKS);
        world.command(turn);
        rig.ticks(SEEN_TICKS);
        float kept = rig.ask(mc -> mc.player.getYRot());
        rig.note("The server turned the view to " + obeyed + " degrees and with NoRotate it stayed at " + kept);
        rig.expect(Math.abs(Mth.wrapDegrees(obeyed - TURNED)) < TURN_NEAR,
            "The server did not turn the view without NoRotate. That proves nothing");
        rig.expect(Math.abs(Mth.wrapDegrees(kept - NORTH)) < TURN_NEAR, "The server turned the view");
    }

    // The game waits four ticks between two throws from a held key.
    private void fastUse(Rig rig) {
        int plain = thrown(rig);
        rig.client().enable("FastUse");
        int fast = thrown(rig);
        rig.note(fast + " bottles were thrown in " + THROW_TICKS + " ticks against " + plain);
        rig.expect(fast >= plain * 2, "FastUse threw no faster");
    }

    private static int thrown(Rig rig) {
        Props.hold(rig, 0, "experience_bottle " + BOTTLES);
        rig.controls().look(NORTH, -60);
        rig.controls().holdMouse(USE);
        rig.ticks(THROW_TICKS);
        rig.controls().releaseMouse(USE);
        rig.ticks(SEEN_TICKS);
        return BOTTLES - Props.carried(rig, "experience_bottle");
    }
}
