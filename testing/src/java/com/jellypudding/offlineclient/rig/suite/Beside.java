package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigFailure;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.rig.suite.Motion.Moved;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

// The modules a player leans on most are played beside every other module one at a time. Each
// has to go on doing its work. A module that stops one of them by design is listed with the
// reason and any other that does fails the case by name.
final class Beside {

    private static final int FORWARD = InputConstants.KEY_W;
    private static final int JUMP = InputConstants.KEY_SPACE;
    private static final String PANIC = "Panic";
    private static final String TOTEM = "totem_of_undying";
    private static final String CHESTPLATE = "diamond_chestplate";
    private static final int SWAP_TICKS = 40;
    private static final int POLL_TICKS = 4;
    private static final int LIFT_TICKS = 10;
    private static final int HANG_TICKS = 20;
    private static final String STONE = "stone";
    private static final String TOUGH_HUSK = "{NoAI:1b,PersistenceRequired:1b,IsBaby:0b,Health:200f,"
        + "attributes:[{id:\"minecraft:max_health\",base:200}]}";
    private static final int SEEN_TICKS = 5;
    private static final int FIGHT_TICKS = 40;
    private static final int DIG_TICKS = 40;
    private static final int WALK_TICKS = 30;
    private static final int FLOOR_REACH = 6;
    private static final int PERCH = 8;

    // The modules that stop each of them on purpose and why. Autopilot only does when an
    // earlier case left it a spot to fly to.
    private static final String FLIES_OFF = "it flies the player away to the spot it was last given";
    private static final Map<String, String> AURA_STOPPED_BY = Map.of(
        "FakePlayer", "its practice dummy stands nearer than the husk and KillAura fights that",
        "Autopilot", FLIES_OFF);
    private static final Map<String, String> NUKER_STOPPED_BY = Map.of(
        "AutoSwitch", "it turns the hand to another slot on its timer and a block starts again with every change",
        "Kaboom", "it floods the server with breaks of its own and the one block the server mines is never Nuker's",
        "Autopilot", FLIES_OFF);
    private static final Map<String, String> SCAFFOLD_STOPPED_BY = Map.of(
        "Cocoon", "it seals the player in with the same blocks",
        "InstantBunker", "it walls the player in with the same blocks",
        "Staircase", "it takes the player up a staircase of its own",
        "Blink", "it keeps every move from the server",
        "Sneak", "Scaffold takes a held sneak for the wish to build downwards",
        "Freecam", "the keys move the camera and the body stays",
        "Autopilot", FLIES_OFF);
    private static final Map<String, String> TOTEM_STOPPED_BY = Map.of();
    private static final Map<String, String> ARMOUR_STOPPED_BY = Map.of();
    private static final Map<String, String> FLIGHT_STOPPED_BY = Map.of(
        "Freecam", "the keys move the camera and the body stays",
        "Staircase", "it switches Flight off since a flying player never lands on the steps it builds");

    // What a core module needs on the stage and the proof that it did its work there.
    private interface Core {

        String module();

        // The words that end the name of its cases.
        String work();

        void setUp(Rig rig, BlockPos home);

        boolean worked(Rig rig, BlockPos home);

        // Modules that stop it on purpose and why.
        Map<String, String> stoppedBy();
    }

    private static final Core AURA = new Core() {
        @Override
        public String module() {
            return "KillAura";
        }

        @Override
        public String work() {
            return "keeps hitting";
        }

        @Override
        public void setUp(Rig rig, BlockPos home) {
            Props.hold(rig, 0, "diamond_sword");
            Props.summon(rig, "husk", home.north(2), TOUGH_HUSK);
            rig.client().set("KillAura", "Hostile mobs", "on");
            rig.ticks(SEEN_TICKS);
        }

        @Override
        public boolean worked(Rig rig, BlockPos home) {
            int before = Props.health(rig, "husk");
            rig.ticks(FIGHT_TICKS);
            return Props.living(rig, "husk") == 0 || Props.health(rig, "husk") < before;
        }

        @Override
        public Map<String, String> stoppedBy() {
            return AURA_STOPPED_BY;
        }
    };

    private static final Core NUKER = new Core() {
        @Override
        public String module() {
            return "Nuker";
        }

        @Override
        public String work() {
            return "keeps digging";
        }

        @Override
        public void setUp(Rig rig, BlockPos home) {
            World world = rig.world();
            world.refill(home.offset(-FLOOR_REACH, -1, -FLOOR_REACH), home.offset(FLOOR_REACH, -1, FLOOR_REACH),
                "bedrock");
            world.refill(home.offset(-1, 0, 2), home.offset(1, 1, 3), STONE);
            Props.hold(rig, 0, "diamond_pickaxe");
            rig.ticks(SEEN_TICKS);
        }

        @Override
        public boolean worked(Rig rig, BlockPos home) {
            rig.ticks(DIG_TICKS);
            String box = Props.at(home.offset(-1, 0, 2)) + " " + Props.at(home.offset(1, 1, 3));
            return rig.world().count("fill " + box + " air replace " + STONE) < 12;
        }

        @Override
        public Map<String, String> stoppedBy() {
            return NUKER_STOPPED_BY;
        }
    };

    private static final Core SCAFFOLD = new Core() {
        @Override
        public String module() {
            return "Scaffold";
        }

        @Override
        public String work() {
            return "keeps the floor";
        }

        @Override
        public void setUp(Rig rig, BlockPos home) {
            rig.world().setBlock(home.above(PERCH - 1), STONE);
            rig.world().teleport(home.getX() + 0.5, home.getY() + PERCH, home.getZ() + 0.5, 180, 0);
            Props.hold(rig, 0, "cobblestone 64");
        }

        @Override
        public boolean worked(Rig rig, BlockPos home) {
            // A sneak covers a block and a half in the time. A hop on the way is no fall.
            Moved moved = Motion.hold(rig, WALK_TICKS, FORWARD);
            return moved.across() >= 1 && moved.lowest() > -0.1;
        }

        @Override
        public Map<String, String> stoppedBy() {
            return SCAFFOLD_STOPPED_BY;
        }
    };

    private static final Core TOTEM_HAND = new Core() {
        @Override
        public String module() {
            return "AutoTotem";
        }

        @Override
        public String work() {
            return "keeps a totem in the offhand";
        }

        @Override
        public void setUp(Rig rig, BlockPos home) {
            Props.pack(rig, 5, TOTEM);
        }

        @Override
        public boolean worked(Rig rig, BlockPos home) {
            return within(rig, () -> Props.holds(rig, "weapon.offhand", TOTEM));
        }

        @Override
        public Map<String, String> stoppedBy() {
            return TOTEM_STOPPED_BY;
        }
    };

    private static final Core ARMOUR = new Core() {
        @Override
        public String module() {
            return "AutoArmor";
        }

        @Override
        public String work() {
            return "puts the armour on";
        }

        @Override
        public void setUp(Rig rig, BlockPos home) {
            Props.pack(rig, 5, CHESTPLATE);
        }

        @Override
        public boolean worked(Rig rig, BlockPos home) {
            return within(rig, () -> Props.holds(rig, "armor.chest", CHESTPLATE));
        }

        @Override
        public Map<String, String> stoppedBy() {
            return ARMOUR_STOPPED_BY;
        }
    };

    private static final Core WINGS = new Core() {
        @Override
        public String module() {
            return "Flight";
        }

        @Override
        public String work() {
            return "lifts you and holds you there";
        }

        @Override
        public void setUp(Rig rig, BlockPos home) {
        }

        @Override
        public boolean worked(Rig rig, BlockPos home) {
            Motion.hold(rig, LIFT_TICKS, JUMP);
            rig.ticks(HANG_TICKS);
            return rig.ask(mc -> mc.player.getY()) >= home.getY() + 1.5;
        }

        @Override
        public Map<String, String> stoppedBy() {
            return FLIGHT_STOPPED_BY;
        }
    };

    private Beside() {
    }

    // True once the server says it within the time a swap of slots takes.
    private static boolean within(Rig rig, BooleanSupplier holds) {
        for (int waited = 0; waited <= SWAP_TICKS; waited += POLL_TICKS) {
            if (holds.getAsBoolean()) {
                return true;
            }
            rig.ticks(POLL_TICKS);
        }
        return false;
    }

    // One case for each core module and each category of the others. The stage is the one the
    // suite stands on when the case runs.
    static List<Case> cases(List<Module> modules, Supplier<Stage> stage) {
        List<Case> cases = new ArrayList<>();
        for (Core core : List.of(AURA, NUKER, SCAFFOLD, TOTEM_HAND, ARMOUR, WINGS)) {
            for (Category category : Category.values()) {
                List<Module> others = modules.stream().filter(module -> module.getCategory() == category).toList();
                cases.add(Case.of(core.module() + " " + core.work() + " beside every " + category.getDisplayName()
                    + " module in turn", rig -> sweep(rig, stage.get(), core, others)));
            }
        }
        return cases;
    }

    private static void sweep(Rig rig, Stage stage, Core core, List<Module> others) {
        Module main = rig.client().module(core.module());
        rig.expect(played(rig, stage, core, null), core.module() + " did not work by itself. That proves nothing");
        List<String> stopped = new ArrayList<>();
        List<String> onPurpose = new ArrayList<>();
        int beside = 0;
        for (Module other : others) {
            if (other == main || !takesPart(other, main)) {
                continue;
            }
            beside++;
            if (played(rig, stage, core, other)) {
                continue;
            }
            String why = core.stoppedBy().get(other.getName());
            if (why == null) {
                stopped.add(other.getName());
            } else {
                onPurpose.add(other.getName() + " (" + why + ")");
            }
        }
        rig.note("Played beside " + beside + " modules. Stopped on purpose by " + onPurpose);
        rig.expect(stopped.isEmpty(), core.module() + " stopped working beside " + stopped);
    }

    // A module that will not run in a blanket case stays out and one the core module clashes
    // with would only switch it off.
    private static boolean takesPart(Module other, Module main) {
        return other.isTogglable() && !other.getName().equals(PANIC)
            && SmokeSuite.leftOut(other.getName(), false) == null && !main.clashesWith(other);
    }

    // One go of the core module on a fresh stage with the other module on beside it. A fault in
    // the game counts as work not done and is told.
    private static boolean played(Rig rig, Stage stage, Core core, Module other) {
        stage.reset(false);
        core.setUp(rig, stage.home());
        rig.client().enable(core.module());
        if (other != null) {
            rig.client().switchTo(other, true);
        }
        try {
            return core.worked(rig, stage.home());
        } catch (RigFailure failure) {
            rig.note("Beside " + (other == null ? "nothing" : other.getName()) + " it ended with " + failure.getMessage());
            return false;
        } finally {
            rig.controls().release(FORWARD);
            rig.controls().release(JUMP);
            rig.act(mc -> mc.gui.setScreen(null));
        }
    }
}
