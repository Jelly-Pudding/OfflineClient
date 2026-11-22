package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.rig.duo.Duo;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

// The modules are taken through what tears the state of a client apart. A death and a respawn.
// A trip to another dimension and back. Every game mode in turn. Leaving and joining again.
// Then the two states in which a client has to keep its hands still. A dead player and a
// spectator send the server no hit and no use and no dig whatever is switched on. The crowd
// is on when the state begins and each module is then switched on by itself inside it.
final class Lifecycle {

    private static final String SURVIVAL = "survival";
    private static final String SPECTATOR = "spectator";
    private static final String SERVED = " on a Paper server";
    private static final String KEEP_INVENTORY = "gamerule keep_inventory ";
    private static final List<String> GAME_MODES = List.of("creative", SPECTATOR, "adventure", SURVIVAL);
    // AutoLog leaves the server by its own judgement. In a crowd that sets off blasts it will
    // and that is its work. It has cases of its own. A ghost walks on after a death and has
    // its own as well.
    private static final Set<String> NOT_IN_THE_CROWD = Set.of("AutoLog");
    private static final Set<String> NOT_AT_A_DEATH = Set.of("Ghost", "AutoLog");
    // The modules that switch themselves off at one of these events without naming themselves
    // and why. Any other has to be on afterwards or say by name why it stopped.
    private static final Map<String, String> GOES_OFF = Map.of(
        "Surround", "its Toggle on death row is on",
        "Blink", "it ends when the server moves you and a respawn or a change of world does",
        "RemoteView", "what it looked through is gone",
        "Kaboom", "it is one blast and ends by itself",
        "FakePlayer", "its bodies went with the world they stood in",
        "AutoNametag", "it has no named name tag and tells you",
        "ChestAura", "its row for switching off on leaving is on",
        "Spam", "its row for stopping on leaving is on",
        "Staircase", "its stairs belong to the world it left");
    // It ends the death the silence is measured in.
    private static final Set<String> NOT_WHILST_DEAD = Set.of("Ghost", "AutoLog", "AutoRespawn");
    // What the hands of a player do. A packet of one of these kinds is a hit or a use or a dig
    // or a click in a container or a change of the held slot.
    private static final Set<Class<?>> HANDS = Set.of(ServerboundAttackPacket.class,
        ServerboundInteractPacket.class, ServerboundUseItemPacket.class, ServerboundUseItemOnPacket.class,
        ServerboundPlayerActionPacket.class, ServerboundContainerClickPacket.class, ServerboundPunchPacket.class,
        ServerboundSetCarriedItemPacket.class);

    private static final int BUSY_TICKS = 40;
    private static final int MODE_TICKS = 20;
    private static final int ALONE_TICKS = 8;
    private static final int DEATH_TICKS = 60;
    private static final int SETTLE_TICKS = 200;
    private static final float HURRY = 5;

    private interface Event {

        void happen(Rig rig, Duo duo, Stage stage);
    }

    private static final Map<String, Event> EVENTS = events();

    private Lifecycle() {
    }

    private static Map<String, Event> events() {
        Map<String, Event> events = new TreeMap<>();
        events.put("a death and a respawn", Lifecycle::dieAndComeBack);
        events.put("a trip to the Nether and back", (rig, duo, stage) -> {
            NetherRoom.enter(rig);
            rig.ticks(BUSY_TICKS);
            NetherRoom.leave(rig, stage.home());
        });
        events.put("every game mode in turn", (rig, duo, stage) -> {
            for (String mode : GAME_MODES) {
                rig.world().gameMode(mode);
                rig.ticks(MODE_TICKS);
            }
        });
        events.put("leaving and joining again", (rig, duo, stage) -> {
            if (duo != null) {
                duo.rejoin();
                return;
            }
            stage.reopen(WorldCreationUiState.SelectedGameMode.CREATIVE);
            rig.local().hurry(HURRY);
            rig.world().gameMode(SURVIVAL);
        });
        return events;
    }

    // The cases of one ground. The stage and the server are the ones the suite stands on when a
    // case runs. No server means single player.
    static List<Case> cases(List<Module> modules, Supplier<Stage> stage, Supplier<Duo> server, boolean served) {
        String where = served ? SERVED : "";
        List<Case> cases = new ArrayList<>();
        EVENTS.forEach((name, event) -> cases.add(Case.of("Every module at once lives through " + name + where,
            rig -> livesThrough(rig, modules, server.get(), stage.get(), event, name.contains("death")))));
        for (boolean dead : List.of(true, false)) {
            String state = dead ? "whilst you are dead" : "whilst you look on as a spectator";
            for (Category category : Category.values()) {
                List<Module> crowd = modules.stream().filter(module -> module.getCategory() == category).toList();
                cases.add(Case.of("Every " + category.getDisplayName() + " module keeps its hands still " + state
                    + where, rig -> handsStill(rig, crowd, server.get(), stage.get(), dead)));
            }
        }
        return cases;
    }

    private static void livesThrough(Rig rig, List<Module> crowd, Duo duo, Stage stage, Event event, boolean death) {
        Map<String, String> before = Traces.of(rig);
        int said = rig.client().chatMark();
        List<Module> on = TogetherSuite.switchOn(rig, crowd, duo != null, death ? NOT_AT_A_DEATH : NOT_IN_THE_CROWD);
        List<String> wentOff;
        try {
            rig.ticks(BUSY_TICKS / 2);
            List<Module> busy = on.stream().filter(module -> rig.ask(mc -> module.isEnabled())).toList();
            event.happen(rig, duo, stage);
            rig.ticks(BUSY_TICKS);
            wentOff = busy.stream().filter(module -> !rig.ask(mc -> module.isEnabled())).map(Module::getName).toList();
        } finally {
            switchOff(rig, on);
        }
        Stage.expectAlive(rig);
        rig.note(on.size() + " modules were on and it switched off " + wentOff);
        // A module may stop for a reason of its own as long as it tells the player by name.
        List<String> lines = rig.client().saidSince(said);
        List<String> unasked = wentOff.stream().filter(name -> !GOES_OFF.containsKey(name)
            && lines.stream().noneMatch(line -> line.contains(name))).toList();
        rig.expect(unasked.isEmpty(), "It switched off " + unasked + " without a word and nothing says they should"
            + " go off");
        List<String> left = Traces.changed(before, Traces.settled(rig, SETTLE_TICKS));
        rig.expect(left.isEmpty(), "Left behind " + left);
    }

    private static void dieAndComeBack(Rig rig, Duo duo, Stage stage) {
        World world = rig.world();
        world.command("kill " + world.player());
        rig.ticks(MODE_TICKS);
        // AutoRespawn is in the crowd and may have done this already.
        Stage.revive(rig);
    }

    // Counts what the hands send whilst the player lies dead or looks on. The kit stays with the
    // dead and there is a chest and a crop beside the two mobs. Whatever goes out in the first
    // ticks of the state was on its way already and is let through.
    private static void handsStill(Rig rig, List<Module> crowd, Duo duo, Stage stage, boolean dead) {
        World world = rig.world();
        BlockPos home = stage.home();
        world.setBlock(home.south(2), "chest");
        world.setBlock(home.west(2).below(), "farmland");
        world.setBlock(home.west(2), "wheat[age=7]");
        Set<String> without = dead ? NOT_WHILST_DEAD : Set.of();
        PacketTally hands = new PacketTally(packet -> HANDS.contains(packet.getClass()));
        List<String> loud = new ArrayList<>();
        List<Module> on = TogetherSuite.switchOn(rig, crowd, duo != null, without);
        int alone = 0;
        try {
            rig.ticks(MODE_TICKS);
            if (dead) {
                world.command(KEEP_INVENTORY + true);
                world.command("kill " + world.player());
                rig.until("the player to die", DEATH_TICKS, mc -> mc.player.isDeadOrDying());
            } else {
                world.gameMode(SPECTATOR);
            }
            rig.ticks(MODE_TICKS);
            hands.listen(rig);
            rig.ticks(BUSY_TICKS);
            switchOff(rig, on);
            hands.drain().forEach((kind, count) -> loud.add("the crowd sent " + kind + " " + count + " times"));
            for (Module module : TogetherSuite.switchable(crowd, duo != null, without)) {
                rig.client().switchTo(module, true);
                rig.ticks(ALONE_TICKS);
                rig.client().switchTo(module, false);
                rig.ticks(2);
                alone++;
                hands.drain().forEach((kind, count) -> loud.add(module.getName() + " alone sent " + kind + " "
                    + count + " times"));
            }
        } finally {
            hands.stop(rig);
            switchOff(rig, on);
            Stage.revive(rig);
            world.gameMode(SURVIVAL);
            if (dead) {
                world.command(KEEP_INVENTORY + false);
            }
        }
        rig.note(on.size() + " modules were on at once and " + alone + " were then tried alone");
        rig.expect(loud.isEmpty(), "The hands sent " + loud);
    }

    private static void switchOff(Rig rig, List<Module> on) {
        on.forEach(module -> rig.client().switchTo(module, false));
        rig.act(mc -> mc.gui.setScreen(null));
        rig.ticks(MODE_TICKS / 2);
    }
}
