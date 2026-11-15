package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigFailure;
import com.jellypudding.offlineclient.rig.core.World;
import net.minecraft.core.BlockPos;

import java.util.function.BooleanSupplier;

// The things a case puts on the stage and the questions it asks the server about them. Every
// answer comes from a server command and reads the same on both grounds.
final class Props {

    static final String STILL_MOB = "{NoAI:1b,PersistenceRequired:1b}";
    private static final int POLL_TICKS = 4;
    private static final int HELD_TICKS = 2;

    private Props() {
    }

    // Puts an item in a hotbar slot counted from nought and takes that slot in the hand.
    static void hold(Rig rig, int slot, String item) {
        rig.world().hotbar(slot, item);
        hold(rig, slot);
    }

    static void hold(Rig rig, int slot) {
        rig.act(mc -> mc.player.getInventory().setSelectedSlot(slot));
        rig.ticks(HELD_TICKS);
    }

    // Puts an item in a slot of the backpack counted from nought.
    static void pack(Rig rig, int slot, String item) {
        World world = rig.world();
        world.command("item replace entity " + world.player() + " inventory." + slot + " with " + item);
    }

    static void wear(Rig rig, String slot, String item) {
        World world = rig.world();
        world.command("item replace entity " + world.player() + " " + slot + " with " + item);
    }

    // Stands a mob in the middle of a block. The tags follow the id with no space between.
    static void summon(Rig rig, String mob, BlockPos at, String tags) {
        rig.world().command("summon " + mob + " " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5)
            + " " + tags);
    }

    // How many of an entity the server holds near the stage.
    static int living(Rig rig, String type) {
        return rig.world().count("execute if entity @e[type=" + type + "]");
    }

    // The health of the one mob of its kind on the stage with the fraction cut off.
    static int health(Rig rig, String type) {
        return rig.world().count("data get entity @e[type=" + type + ",limit=1] Health");
    }

    // How many of an item the player carries.
    static int carried(Rig rig, String item) {
        World world = rig.world();
        return world.count("clear " + world.player() + " " + item + " 0");
    }

    // True when the slot of the player holds the item. The slot is named the way commands name it.
    static boolean holds(Rig rig, String slot, String item) {
        World world = rig.world();
        return world.attempt("execute if items entity " + world.player() + " " + slot + " " + item);
    }

    // The three numbers of a spot the way a command takes them.
    static String at(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    static boolean is(Rig rig, BlockPos pos, String block) {
        return rig.world().blockAt(pos).equals("minecraft:" + block);
    }

    // Waits for something only the server can say and asks every few ticks. Hands back the ticks
    // it took.
    static int await(Rig rig, String what, int limitTicks, BooleanSupplier holds) {
        for (int waited = 0; waited <= limitTicks; waited += POLL_TICKS) {
            if (holds.getAsBoolean()) {
                return waited;
            }
            rig.ticks(POLL_TICKS);
        }
        throw new RigFailure("Waited " + limitTicks + " ticks for " + what);
    }
}
