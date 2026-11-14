package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.core.World;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

// One case for each module that draws into the world. The same still scene is looked at before
// the module goes on and whilst it is on and after it went off. The module must change the
// picture and must leave nothing behind. The game's own interface is hidden for the pictures
// and what a module adds to the module list cannot count as a change. A module that draws on
// the interface itself is looked at with the game's interface shown and the client's overlay off.
public final class RenderSuite implements Suite {

    private static final String NAME = "Render";
    private static final WorldCreationUiState.SelectedGameMode CREATIVE =
        WorldCreationUiState.SelectedGameMode.CREATIVE;
    private static final int USE = InputConstants.MOUSE_BUTTON_RIGHT;
    private static final String SURVIVAL = "survival";
    private static final String ALL_MOBS = "all";
    private static final String MOBS = "Mobs";
    private static final String ON = "on";
    private static final String GROWN_STILL = "{NoAI:1b,PersistenceRequired:1b,IsBaby:0b}";

    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;
    // The mobs of the case before die in a puff of smoke that hangs about for a few seconds.
    private static final int STILL_TICKS = 80;
    // Long enough for a fade or a zoom to finish.
    private static final int SHOWN_TICKS = 30;
    // A hundredth of a hundredth of the picture. Fewer changed pixels than this is no drawing.
    private static final double LEAST = 0.0001;
    private static final double PER_CENT = 100;
    private static final Set<String> ON_THE_INTERFACE = Set.of("Nametags");

    // One module to look at. The scene may get something more for it before the first picture and
    // settings go on in pairs of a name and a value.
    private record Sight(String module, Consumer<Rig> arrange, String... settings) {
    }

    private BlockPos home;

    @Override
    public String name() {
        return NAME;
    }

    private List<Sight> sights() {
        Consumer<Rig> nothing = rig -> { };
        return List.of(
            new Sight("ESP", nothing, MOBS, ALL_MOBS),
            new Sight("Chams", nothing, MOBS, ALL_MOBS),
            new Sight("Skeletons", nothing, MOBS, ALL_MOBS),
            new Sight("Tracers", nothing, MOBS, ALL_MOBS),
            new Sight("Nametags", nothing, MOBS, ALL_MOBS),
            new Sight("ItemESP", nothing),
            new Sight("ChestESP", nothing),
            new Sight("BlockESP", nothing),
            new Sight("HoleESP", nothing),
            new Sight("XRay", nothing),
            new Sight("WallHack", nothing),
            new Sight("ChunkBorders", nothing),
            new Sight("Highways", nothing),
            new Sight("FarmRanges", nothing),
            new Sight("SculkRange", rig -> rig.world().setBlock(home.offset(-1, 0, -5), "sculk_sensor")),
            new Sight("Fullbright", rig -> rig.world().command("time set midnight")),
            new Sight("TimeChanger", nothing, "Time", "18000"),
            new Sight("Ambience", nothing, "Custom sky colour", ON),
            new Sight("Zoom", nothing),
            new Sight("TrueSight", rig -> rig.world().command(
                "effect give @e[type=husk] invisibility infinite 0 true")),
            new Sight("AntiBlind", rig -> rig.world().command(
                "effect give " + rig.world().player() + " blindness 600 0 true")),
            new Sight("Trajectories", rig -> {
                Props.hold(rig, 0, "bow");
                Props.pack(rig, 0, "arrow 16");
                rig.controls().holdMouse(USE);
            }));
    }

    @Override
    public void run(Rig rig) {
        List<Case> cases = new ArrayList<>();
        for (Sight sight : sights()) {
            cases.add(Case.of(sight.module() + " changes the picture and leaves it clean", each -> look(each, sight)));
        }
        List<Case> wanted = rig.wanted(NAME, cases);
        if (wanted.isEmpty()) {
            return;
        }
        int[] size = rig.ask(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        rig.openWorld(CREATIVE);
        rig.frames().drawAt(WIDTH, HEIGHT);
        try {
            Stage stage = new Stage(rig);
            home = stage.home();
            for (Case each : wanted) {
                stage.reset(false);
                rig.world().gameMode(SURVIVAL);
                rig.run(NAME, each);
            }
        } finally {
            rig.frames().drawAt(size[0], size[1]);
            rig.closeWorld();
        }
    }

    private void look(Rig rig, Sight sight) {
        scene(rig);
        sight.arrange().accept(rig);
        boolean overlay = ON_THE_INTERFACE.contains(sight.module());
        if (overlay) {
            rig.client().disable("HUD");
        }
        hideInterface(rig, !overlay);
        try {
            rig.ticks(STILL_TICKS);
            Path before = rig.frames().shot(sight.module() + " before");
            rig.ticks(2);
            Path again = rig.frames().shot(sight.module() + " still");
            for (int pair = 0; pair + 1 < sight.settings().length; pair += 2) {
                rig.client().set(sight.module(), sight.settings()[pair], sight.settings()[pair + 1]);
            }
            rig.client().enable(sight.module());
            rig.ticks(SHOWN_TICKS);
            Path on = rig.frames().shot(sight.module() + " on");
            rig.client().disable(sight.module());
            rig.ticks(SHOWN_TICKS);
            Path off = rig.frames().shot(sight.module() + " off");
            double shown = Pictures.changed(before, on, again);
            double left = Pictures.changed(before, off, again);
            rig.note(String.format(Locale.ROOT, "%.3f%% of the picture changed and %.3f%% was still changed once it "
                + "went off", shown * PER_CENT, left * PER_CENT));
            rig.expect(shown >= LEAST, sight.module() + " changed nothing in the picture");
            rig.expect(left <= Math.max(LEAST, shown / 4), sight.module() + " left its mark on the picture");
        } finally {
            hideInterface(rig, false);
            rig.world().attempt("time set noon");
        }
    }

    // What the modules have to draw on. Two mobs and a dropped item and a chest and an ore and
    // a hole a player could stand in. Nothing in it moves.
    private void scene(Rig rig) {
        World world = rig.world();
        Props.summon(rig, "husk", home.north(5), GROWN_STILL);
        Props.summon(rig, "cow", home.offset(-3, 0, -6), Props.STILL_MOB);
        Props.summon(rig, "item", home.offset(2, 0, -4),
            "{Item:{id:\"minecraft:diamond\",count:1},PickupDelay:32767,Age:-32768}");
        world.setBlock(home.offset(-2, 0, -4), "chest");
        world.setBlock(home.offset(3, 0, -6), "diamond_ore");
        BlockPos hole = home.offset(1, -1, -3);
        for (BlockPos wall : List.of(hole.north(), hole.east(), hole.south(), hole.west(), hole.below())) {
            world.setBlock(wall, "obsidian");
        }
        world.setBlock(hole, "air");
    }

    private static void hideInterface(Rig rig, boolean hidden) {
        rig.act(mc -> {
            if (mc.gui.hud.isHidden() != hidden) {
                mc.gui.hud.toggle();
            }
        });
    }
}
