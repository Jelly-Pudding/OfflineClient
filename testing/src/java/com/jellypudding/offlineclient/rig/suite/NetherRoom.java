package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.World;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

// A sealed room of obsidian in the Nether for the modules that only work where a bed explodes.
// The player is put in its middle and taken back to the overworld afterwards.
final class NetherRoom {

    // The block the player's feet are in.
    static final BlockPos FEET = new BlockPos(0, 100, 0);

    private static final String IN_NETHER = "execute in minecraft:the_nether ";
    private static final String SHELL = "run fill -6 99 -6 6 106 6 obsidian hollow";
    private static final String INSIDE = "run fill -5 100 -5 5 105 5 air";
    private static final String CHUNKS = "run forceload add -16 -16 15 15";
    private static final String NO_CHUNKS = "run forceload remove -16 -16 15 15";
    private static final int BUILD_TICKS = 400;
    private static final int MOVE_TICKS = 300;
    private static final int SEEN_TICKS = 10;
    private static final double ARRIVED = 0.5;

    private NetherRoom() {
    }

    // Builds the room in chunks that may never have been made and stands the player in it.
    static void enter(Rig rig) {
        World world = rig.world();
        world.command(IN_NETHER + CHUNKS);
        Props.await(rig, "the room in the Nether to stand", BUILD_TICKS, () -> {
            world.attempt(IN_NETHER + SHELL);
            return is(rig, FEET.below(), "obsidian");
        });
        world.attempt(IN_NETHER + INSIDE);
        Vec3 spot = Vec3.atBottomCenterOf(FEET);
        world.command(IN_NETHER + "run tp " + world.player() + " " + spot.x + " " + spot.y + " " + spot.z + " 180 0");
        rig.until("the player to stand in the Nether", MOVE_TICKS, mc -> mc.player != null
            && mc.level.dimension() == Level.NETHER && mc.player.position().distanceTo(spot) < ARRIVED);
        rig.ticks(SEEN_TICKS);
    }

    // Back to the overworld spot the case began on.
    static void leave(Rig rig, BlockPos home) {
        World world = rig.world();
        world.attempt("execute in minecraft:overworld run tp " + world.player() + " " + (home.getX() + 0.5) + " "
            + home.getY() + " " + (home.getZ() + 0.5) + " 180 0");
        rig.until("the player to stand in the overworld again", MOVE_TICKS,
            mc -> mc.player != null && mc.level.dimension() == Level.OVERWORLD);
        world.attempt(IN_NETHER + NO_CHUNKS);
        rig.ticks(SEEN_TICKS);
    }

    static void set(Rig rig, BlockPos pos, String block) {
        rig.world().command(IN_NETHER + "run setblock " + Props.at(pos) + " " + block);
    }

    static boolean is(Rig rig, BlockPos pos, String block) {
        return rig.world().attempt(IN_NETHER + "if block " + Props.at(pos) + " minecraft:" + block);
    }
}
