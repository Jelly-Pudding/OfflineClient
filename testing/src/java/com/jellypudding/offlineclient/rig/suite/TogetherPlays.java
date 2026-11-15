package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.path.Travel;
import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.rig.suite.Motion.Moved;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Modules played together for what a player would expect of them. Most share something. The
// hand or a hotbar slot or the one block the server lets a player mine or the attack cooldown
// or the keys that move the player.
final class TogetherPlays {

    private static final int FORWARD = InputConstants.KEY_W;
    private static final int SPRINT = InputConstants.KEY_LCONTROL;
    private static final int JUMP = InputConstants.KEY_SPACE;
    private static final int ATTACK = InputConstants.MOUSE_BUTTON_LEFT;
    private static final int USE = InputConstants.MOUSE_BUTTON_RIGHT;

    private static final String SWORD = "diamond_sword";
    private static final String PICKAXE = "diamond_pickaxe";
    private static final String BEEF = "cooked_beef";
    private static final String TOTEM = "totem_of_undying";
    private static final String CRYSTAL = "end_crystal";
    private static final String OBSIDIAN = "obsidian";
    private static final String STONE = "stone";
    private static final String COBBLE = "cobblestone";
    private static final String BEDROCK = "bedrock";
    private static final String CHESTPLATE = "diamond_chestplate";
    private static final String ELYTRA = "elytra";
    private static final String BED = "red_bed";
    private static final String GAPPLE = "golden_apple";
    private static final String OFFHAND = "weapon.offhand";
    private static final String CHEST = "armor.chest";
    private static final String ON = "on";
    private static final String HUSK = "husk";
    private static final String TOUGH_HUSK = "{NoAI:1b,PersistenceRequired:1b,IsBaby:0b,Health:200f,"
        + "attributes:[{id:\"minecraft:max_health\",base:200}]}";
    // Nothing knocks this one back. It stays in reach of the player it fights.
    private static final String ANGRY_HUSK = "{PersistenceRequired:1b,IsBaby:0b,Health:200f,"
        + "attributes:[{id:\"minecraft:max_health\",base:200},{id:\"minecraft:knockback_resistance\",base:1}]}";
    private static final String WEAK_HUSK = "{NoAI:1b,PersistenceRequired:1b,IsBaby:0b}";
    // Two hits of a diamond sword kill this one. A sprint is past it before a third is charged.
    private static final String FRAIL_HUSK = "{NoAI:1b,PersistenceRequired:1b,IsBaby:0b,Health:12f}";

    // A diamond sword takes seven off a husk with the cooldown full and under two straight
    // after the cooldown began again. A hit that reaches a server one tick sooner than the game
    // reckoned loses an eighth.
    private static final double FULL_HIT = 6;
    // The ticks a sword takes to charge.
    private static final double SWORD_TICKS = 12.5;
    private static final int PEACEFUL = 0;

    private static final int BEEF_GIVEN = 8;
    private static final int SEEN_TICKS = 10;
    private static final int FIGHT_TICKS = 300;
    private static final int SHORT_FIGHT = 120;
    // A bite lands a tick or two after the health of the mob it follows is told.
    private static final int WELL_INTO_A_BITE = 4;
    private static final int SWAP_TICKS = 100;
    private static final int STEADY_TICKS = 40;
    private static final int BUILD_TICKS = 200;
    private static final int RUN_TICKS = 30;
    private static final int LONG_RUN = 45;
    private static final int NUKE_TICKS = 60;
    private static final int WORK_TICKS = 200;
    private static final int FLOOR_REACH = 6;
    private static final int PERCH = 8;
    private static final int POOL_LENGTH = 14;
    private static final int GAP_FROM = 4;
    private static final int GAP_TO = 6;
    private static final int FAR_SIDE = 26;
    private static final int HANDFUL = 10;
    private static final int WALK = 30;
    private static final int WALL = 8;
    // A diamond pickaxe is through stone in eight ticks and a bare hand in a hundred and fifty.
    private static final int BY_PICKAXE = 60;
    private static final float FULL_HEALTH = 20;
    // A minute. A rod with Lure bites well inside it.
    private static final int FISHING_TICKS = 1200;
    // A diamond sword takes seven and half as much again with a critical hit.
    private static final double CRIT_HIT = 9.5;

    private BlockPos home;

    void standOn(BlockPos home) {
        this.home = home;
    }

    Map<String, Case.Body> plays() {
        Map<String, Case.Body> plays = new LinkedHashMap<>();
        plays.put("KillAura holds its hits whilst AutoEat eats and fights on after", this::auraAndEat);
        plays.put("AutoTotem takes the offhand from Offhand when health is low and gives it back",
            this::totemAndOffhand);
        plays.put("AutoArmor leaves the elytra on that ChestSwap put there until Keep elytra is off",
            this::armorAndChestSwap);
        plays.put("Surround and SelfTrap build their walls and their roof together", this::surroundAndTrap);
        plays.put("Sprint and Speed and Step run up a ledge together with no setback", this::movers);
        plays.put("Nuker breaks stone faster once AutoTool hands it the pickaxe", this::nukerAndTool);
        plays.put("Scaffold keeps a floor under a Sprint with Speed on", this::scaffoldAndSpeed);
        plays.put("KillAura keeps its full hit whilst Nuker mines beside it", this::auraBesideNuker);
        plays.put("KillAura keeps its full hit whilst you mine by hand", this::auraBesideHandMining);
        plays.put("TriggerBot and KillAura share one cooldown and never hit weak", this::twoAttackers);
        plays.put("Surround builds its walls in a fight and KillAura still hits with the full sword",
            this::surroundInAFight);
        plays.put("Scaffold holds the floor beside a husk and KillAura wastes no weak hit", this::scaffoldBesideAHusk);
        plays.put("AutoEat eats on a bridge that Scaffold keeps building", this::eatOnABridge);
        plays.put("KillAura takes the sword for the fight and AutoTool hands Nuker the pickaxe after",
            this::swordThenPickaxe);
        plays.put("Freecam leaves the body standing and KillAura guards it", this::freecamGuarded);
        plays.put("Jesus and Speed run across water with no setback", this::overWater);
        plays.put("NoSlowdown lets a Sprint run on whilst AutoEat eats", this::eatOnTheRun);
        plays.put("EdgeGuard stops a run with Speed at the lip", this::guardedLip);
        plays.put("Parkour jumps a gap at a Sprint", this::jumpedGap);
        plays.put("AutoReplenish tops up the stack Scaffold builds a bridge from", this::fedBridge);
        plays.put("AntiBed breaks a bed whilst Nuker digs and Nuker digs on", this::bedBesideNuker);
        plays.put("ChestStealer empties a chest and AutoArmor wears what came out", this::lootedArmour);
        plays.put("AutoWeapon hands KillAura the sword and the hits that follow are full", this::armedAura);
        plays.put("NoKnockback and AntiPush keep you where you stand whilst KillAura fights a husk that hits back",
            this::unmovedFight);
        plays.put("A fight kit of KillAura and Criticals and AutoWeapon lands critical hits with the sword",
            this::fightKit);
        plays.put("A dig with Nuker and AutoTool carries on after AutoEat has had the hand for a meal",
            this::digWithAMeal);
        plays.put("AutoArmor and AutoTotem and AutoEat and AutoTool each get their turn with one inventory",
            this::helpersInTurn);
        plays.put("Goto walks on to its goal whilst AutoEat eats and KillAura fights a husk on the way",
            this::walkWithHelpers);
        plays.put("Flight keeps clear of the kick for floating whilst Freecam has the keys", this::hoverInFreecam);
        plays.put("AutoTool takes the pickaxe for a block and AutoWeapon the sword for a husk", this::toolThenWeapon);
        plays.put("AutoTotem puts up a second totem after a pop and KillAura fights on", this::secondTotem);
        plays.put("Spider climbs a wall and NoFall takes the hurt out of the drop behind it", this::overTheWall);
        plays.put("AutoFish lands a catch whilst AntiAFK keeps you moving", this::fishingAway);
        plays.put("AutoGap eats for health and AutoEat for hunger and each eats its own food", this::twoFeeders);
        return plays;
    }

    // The husk has health enough to stand through the whole case.
    private void auraAndEat(Rig rig) {
        World world = rig.world();
        Props.hold(rig, 0, SWORD);
        world.hotbar(1, BEEF + " " + BEEF_GIVEN);
        fight(rig, home.north(2));
        rig.client().enable("AutoEat");
        world.command("effect give " + world.player() + " hunger 30 255 true");
        double first = huskHealth(rig);
        double last = first;
        int biting = 0;
        int bites = 0;
        int hitsInABite = 0;
        for (int tick = 0; tick < FIGHT_TICKS; tick++) {
            rig.ticks(1);
            boolean eating = rig.ask(mc -> mc.player.isUsingItem());
            double health = huskHealth(rig);
            biting = eating ? biting + 1 : 0;
            if (biting == WELL_INTO_A_BITE) {
                bites++;
            }
            if (biting > WELL_INTO_A_BITE && health < last) {
                hitsInABite++;
            }
            last = health;
        }
        int eaten = BEEF_GIVEN - Props.carried(rig, BEEF);
        rig.note("The player ate " + eaten + " steaks in " + bites + " sittings and the husk lost " + Math.round(first
            - last) + " health and " + hitsInABite + " hits landed in the middle of a bite");
        rig.expect(eaten >= 1 && bites >= 1, "AutoEat did not eat with KillAura on");
        rig.expect(last < first, "KillAura never hit the husk with AutoEat on");
        rig.expect(hitsInABite == 0, "KillAura hit in the middle of a bite");
    }

    // A husk that stands through a case and KillAura set to fight it.
    private static void fight(Rig rig, BlockPos huskAt) {
        Props.summon(rig, HUSK, huskAt, TOUGH_HUSK);
        rig.client().set("KillAura", "Hostile mobs", ON);
        rig.ticks(SEEN_TICKS);
        rig.client().enable("KillAura");
    }

    // What the game holds as the health of the one husk on the stage.
    private static double huskHealth(Rig rig) {
        return rig.ask(mc -> {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof Husk husk) {
                    return (double) husk.getHealth();
                }
            }
            return 0.0;
        });
    }

    // What each hit took off the husk over the ticks.
    private static List<Double> hits(Rig rig, int ticks) {
        List<Double> hits = new ArrayList<>();
        double last = huskHealth(rig);
        for (int tick = 0; tick < ticks; tick++) {
            rig.ticks(1);
            double health = huskHealth(rig);
            if (health < last) {
                hits.add(last - health);
            }
            last = health;
        }
        return hits;
    }

    private static void expectFull(Rig rig, List<Double> hits, int fewest) {
        long weak = hits.stream().filter(hit -> hit < FULL_HIT).count();
        rig.expect(hits.size() >= fewest, "Only " + hits.size() + " hits landed");
        rig.expect(weak == 0, weak + " of the hits landed weak. They took " + hits);
    }

    // AutoTotem only claims the offhand at five hearts and under here. Three health are left
    // after the blow and they take a while to come back.
    private void totemAndOffhand(Rig rig) {
        World world = rig.world();
        Props.pack(rig, 5, TOTEM);
        Props.pack(rig, 6, CRYSTAL + " 8");
        rig.client().set("AutoTotem", "Health", "5");
        rig.client().enable("AutoTotem");
        rig.client().enable("Offhand");
        Props.await(rig, "Offhand to put its crystals in the offhand", SWAP_TICKS,
            () -> Props.holds(rig, OFFHAND, CRYSTAL));
        world.command("damage " + world.player() + " 17");
        Props.await(rig, "AutoTotem to take the offhand for a totem", SWAP_TICKS,
            () -> Props.holds(rig, OFFHAND, TOTEM));
        int changes = offhandChanges(rig);
        world.command("effect give " + world.player() + " instant_health 1 20 true");
        Props.await(rig, "the crystals to come back once health is up", SWAP_TICKS * 2,
            () -> Props.holds(rig, OFFHAND, CRYSTAL));
        rig.expect(changes == 0, "The offhand changed hands " + changes + " times whilst the totem was needed");
    }

    // How often the item in the offhand changed over a stretch of ticks.
    private static int offhandChanges(Rig rig) {
        int changes = 0;
        String last = null;
        for (int tick = 0; tick < STEADY_TICKS; tick++) {
            String held = rig.ask(mc -> BuiltInRegistries.ITEM.getKey(mc.player.getOffhandItem().getItem()).toString());
            if (last != null && !last.equals(held)) {
                changes++;
            }
            last = held;
            rig.ticks(1);
        }
        return changes;
    }

    private void armorAndChestSwap(Rig rig) {
        Props.wear(rig, CHEST, CHESTPLATE);
        Props.pack(rig, 4, ELYTRA);
        rig.client().enable("AutoArmor");
        rig.ticks(SEEN_TICKS);
        rig.client().press("ChestSwap");
        Props.await(rig, "the elytra to be worn", SWAP_TICKS, () -> Props.holds(rig, CHEST, ELYTRA));
        rig.ticks(STEADY_TICKS);
        boolean kept = Props.holds(rig, CHEST, ELYTRA);
        rig.client().set("AutoArmor", "Keep elytra", "off");
        Props.await(rig, "AutoArmor to put the chestplate back with Keep elytra off", SWAP_TICKS,
            () -> Props.holds(rig, CHEST, CHESTPLATE));
        rig.expect(kept, "AutoArmor took the elytra off again with Keep elytra on");
    }

    private void surroundAndTrap(Rig rig) {
        Props.hold(rig, 0, OBSIDIAN + " 64");
        rig.client().enable("Surround");
        rig.client().enable("SelfTrap");
        Props.await(rig, "the four walls and the roof", BUILD_TICKS, () -> walled(rig) && Props.is(rig,
            home.above(2), OBSIDIAN));
    }

    private boolean walled(Rig rig) {
        return Direction.Plane.HORIZONTAL.stream().allMatch(side -> Props.is(rig, home.relative(side), OBSIDIAN));
    }

    // A ledge one block high lies across the way. A sprint alone stops at it.
    private void movers(Rig rig) {
        World world = rig.world();
        world.fill(home.offset(-1, 0, -6), home.offset(1, 0, -4), STONE);
        double plain = Motion.hold(rig, RUN_TICKS, FORWARD, SPRINT).across();
        goHome(rig);
        enable(rig, "Sprint", "Speed", "Step");
        Moved moved = Motion.hold(rig, RUN_TICKS, FORWARD);
        rig.note(String.format(Locale.ROOT, "A sprint got %.1f blocks and the three together %.1f and rose %.1f",
            plain, moved.across(), moved.highest()));
        rig.expect(moved.across() >= plain + 1, "The three together got no further than a sprint the ledge stops");
        rig.expect(moved.highest() >= 1, "The run never got up the ledge");
        rig.expect(moved.setbacks() == 0, "The server put the run back " + moved.setbacks() + " times");
    }

    private void goHome(Rig rig) {
        rig.world().teleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 180, 0);
    }

    private static void enable(Rig rig, String... modules) {
        for (String module : modules) {
            rig.client().enable(module);
        }
    }

    // Stone a fist takes seven seconds a block to break. The pickaxe lies one slot along.
    private void nukerAndTool(Rig rig) {
        int byHand = nuked(rig, false);
        int withTool = nuked(rig, true);
        rig.note("Nuker broke " + byHand + " blocks of stone by hand and " + withTool + " with AutoTool on");
        rig.expect(withTool >= byHand + 3, "AutoTool did not hand Nuker the pickaxe");
    }

    private int nuked(Rig rig, boolean withTool) {
        World world = rig.world();
        quarry(rig);
        world.hotbar(1, PICKAXE);
        Props.hold(rig, 0);
        rig.ticks(SEEN_TICKS);
        if (withTool) {
            rig.client().enable("AutoTool");
        }
        rig.client().enable("Nuker");
        rig.ticks(NUKE_TICKS);
        rig.client().disable("Nuker");
        rig.client().disable("AutoTool");
        rig.ticks(SEEN_TICKS);
        return broken(rig);
    }

    // Twelve blocks of stone behind the player on a floor Nuker cannot break.
    private void quarry(Rig rig) {
        World world = rig.world();
        world.refill(home.offset(-FLOOR_REACH, -1, -FLOOR_REACH), home.offset(FLOOR_REACH, -1, FLOOR_REACH), BEDROCK);
        world.refill(quarryFrom(), quarryTo(), STONE);
    }

    private BlockPos quarryFrom() {
        return home.offset(-1, 0, 2);
    }

    private BlockPos quarryTo() {
        return home.offset(1, 1, 3);
    }

    // The blocks of the quarry that are gone. The count leaves the rest standing.
    private int broken(Rig rig) {
        String box = Props.at(quarryFrom()) + " " + Props.at(quarryTo());
        return rig.world().count("execute if blocks " + box + " " + Props.at(quarryFrom()) + " all")
            - rig.world().count("execute if blocks " + box + " " + Props.at(quarryFrom()) + " masked");
    }

    // The player stands on one block high in the air and runs off it.
    private void scaffoldAndSpeed(Rig rig) {
        perch(rig);
        Props.hold(rig, 0, COBBLE + " 64");
        enable(rig, "Scaffold", "Sprint", "Speed");
        Moved moved = Motion.hold(rig, RUN_TICKS, FORWARD);
        expectBridged(rig, moved, 4);
        rig.expect(moved.setbacks() == 0, "The server put the run back " + moved.setbacks() + " times");
    }

    // One block high in the air with the player on top of it.
    private void perch(Rig rig) {
        rig.world().setBlock(home.above(PERCH - 1), STONE);
        rig.world().teleport(home.getX() + 0.5, home.getY() + PERCH, home.getZ() + 0.5, 180, 0);
    }

    private static void expectBridged(Rig rig, Moved moved, double fewest) {
        rig.note(String.format(Locale.ROOT, "It ran %.1f blocks out and ended %.1f blocks lower", moved.across(),
            -moved.rise()));
        rig.expect(moved.across() >= fewest, "The player did not run out over the drop");
        rig.expect(Math.abs(moved.rise()) < 0.1, "The player fell");
    }

    // The sword mines the stone slowly. Nuker is on a block for the whole of the fight and
    // every tick of that is a swing.
    private void auraBesideNuker(Rig rig) {
        quarry(rig);
        Props.hold(rig, 0, SWORD);
        rig.client().set("Nuker", "Auto tool", "off");
        rig.client().enable("Nuker");
        rig.ticks(SEEN_TICKS);
        fight(rig, home.north(2));
        int[] mining = new int[1];
        List<Double> hits = hitsWhilst(rig, SHORT_FIGHT, mining);
        rig.note("Nuker was on a block for " + mining[0] + " of " + SHORT_FIGHT + " ticks and the hits took " + hits);
        rig.expect(mining[0] >= SHORT_FIGHT / 2, "Nuker did not mine through the fight. That proves nothing");
        expectFull(rig, hits, 3);
    }

    // The hits of a stretch of ticks and how many of those ticks the game was breaking a block.
    private static List<Double> hitsWhilst(Rig rig, int ticks, int[] mining) {
        List<Double> hits = new ArrayList<>();
        for (int tick = 0; tick < ticks; tick++) {
            hits.addAll(hits(rig, 1));
            if (rig.ask(mc -> mc.gameMode.isDestroying())) {
                mining[0]++;
            }
        }
        return hits;
    }

    // The player looks at a block of stone and holds the button down on it. The husk stands to
    // one side where KillAura turns to it on the server alone.
    private void auraBesideHandMining(Rig rig) {
        BlockPos block = home.north().above();
        rig.world().setBlock(block, OBSIDIAN);
        Props.hold(rig, 0, SWORD);
        rig.client().set("KillAura", "Pause on mine", "off");
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAtBlock(block, Direction.SOUTH);
        rig.controls().holdMouse(ATTACK);
        try {
            rig.ticks(SEEN_TICKS);
            fight(rig, home.east(2));
            int[] mining = new int[1];
            List<Double> hits = hitsWhilst(rig, SHORT_FIGHT, mining);
            rig.note("The hand was on the block for " + mining[0] + " of " + SHORT_FIGHT + " ticks and the hits took "
                + hits);
            rig.expect(mining[0] >= SHORT_FIGHT / 2, "The hand did not mine through the fight. That proves nothing");
            expectFull(rig, hits, 3);
        } finally {
            rig.controls().releaseMouse(ATTACK);
        }
    }

    // The husk stands under the crosshair and both modules want it.
    private void twoAttackers(Rig rig) {
        Props.hold(rig, 0, SWORD);
        fight(rig, home.north(2));
        rig.client().enable("TriggerBot");
        List<Double> hits = hits(rig, SHORT_FIGHT);
        int most = (int) (SHORT_FIGHT / SWORD_TICKS) + 1;
        rig.note(hits.size() + " hits landed in " + SHORT_FIGHT + " ticks and took " + hits);
        expectFull(rig, hits, 3);
        rig.expect(hits.size() <= most, "More hits landed than one cooldown after another allows");
    }

    // The obsidian lies one slot along from the sword. Every block Surround lays takes the
    // slot for a moment and the cooldown starts again with it.
    private void surroundInAFight(Rig rig) {
        Props.hold(rig, 0, SWORD);
        rig.world().hotbar(1, OBSIDIAN + " 64");
        fight(rig, home.north(2));
        rig.client().enable("Surround");
        Props.await(rig, "the four walls", BUILD_TICKS, () -> walled(rig));
        List<Double> hits = hits(rig, SHORT_FIGHT);
        rig.note("With the walls up the hits took " + hits);
        expectFull(rig, hits, 3);
        rig.expect(Props.holds(rig, "weapon.mainhand", SWORD), "The sword was not back in the hand");
    }

    // The husk stands on a pillar beside the way. Scaffold takes the slot for every block and
    // the cooldown starts again each time. A hit before it has filled would be a wasted one.
    // The walk stops in reach of the husk and the sword then has the hand to itself.
    private void scaffoldBesideAHusk(Rig rig) {
        perch(rig);
        BlockPos pillar = home.offset(2, PERCH - 1, -2);
        rig.world().setBlock(pillar, STONE);
        Props.hold(rig, 0, SWORD);
        rig.world().hotbar(1, COBBLE + " 64");
        fight(rig, pillar.above());
        rig.client().enable("Scaffold");
        rig.controls().hold(FORWARD);
        List<Double> onTheWay;
        try {
            onTheWay = hits(rig, SEEN_TICKS * 2);
        } finally {
            rig.controls().release(FORWARD);
        }
        List<Double> standing = hits(rig, STEADY_TICKS * 2);
        Vec3 ended = rig.ask(mc -> mc.player.position());
        double fell = home.getY() + PERCH - ended.y;
        rig.note(String.format(Locale.ROOT, "The walk went %.1f blocks out and ended %.1f lower. The hits on the way"
            + " took %s and the hits after it %s", home.getZ() + 0.5 - ended.z, fell, onTheWay, standing));
        rig.expect(Math.abs(fell) < 0.1, "The player fell");
        long weak = onTheWay.stream().filter(hit -> hit < FULL_HIT).count();
        rig.expect(weak == 0, weak + " hits landed weak on the way. They took " + onTheWay);
        expectFull(rig, standing, 2);
    }

    // Hunger sets in on the perch. The bridge has to hold whilst the food is in the hand.
    private void eatOnABridge(Rig rig) {
        World world = rig.world();
        perch(rig);
        Props.hold(rig, 0, COBBLE + " 64");
        world.hotbar(1, BEEF + " " + BEEF_GIVEN);
        enable(rig, "Scaffold", "AutoEat");
        world.command("effect give " + world.player() + " hunger 30 255 true");
        rig.ticks(STEADY_TICKS);
        Moved moved = Motion.hold(rig, LONG_RUN * 2, FORWARD);
        rig.ticks(STEADY_TICKS * 2);
        int eaten = BEEF_GIVEN - Props.carried(rig, BEEF);
        double fell = home.getY() + PERCH - rig.ask(mc -> mc.player.getY());
        rig.note(String.format(Locale.ROOT, "It walked %.1f blocks out and ended %.1f lower and ate %d steaks",
            moved.across(), fell, eaten));
        rig.expect(Math.abs(fell) < 0.1, "The player fell");
        rig.expect(moved.across() >= 4, "The player did not walk out over the drop");
        rig.expect(eaten >= 1, "AutoEat did not eat on the bridge");
    }

    // The husk dies after three hits. Nuker has the sword until then and the pickaxe after.
    private void swordThenPickaxe(Rig rig) {
        quarry(rig);
        Props.hold(rig, 0, SWORD);
        rig.world().hotbar(1, PICKAXE);
        Props.summon(rig, HUSK, home.north(2), WEAK_HUSK);
        rig.client().set("KillAura", "Hostile mobs", ON);
        rig.client().set("KillAura", "Auto weapon", ON);
        rig.ticks(SEEN_TICKS);
        enable(rig, "AutoTool", "Nuker", "KillAura");
        List<Double> hits = new ArrayList<>();
        int took = 0;
        while (huskHealth(rig) > 0 && took++ < WORK_TICKS) {
            hits.addAll(hits(rig, 1));
        }
        rig.expect(huskHealth(rig) <= 0, "The husk outlived " + WORK_TICKS + " ticks of the fight");
        int during = broken(rig);
        rig.ticks(NUKE_TICKS);
        int after = broken(rig) - during;
        rig.note("The husk died after about " + took + " ticks to hits of " + hits + ". Nuker broke " + during
            + " blocks in the fight and " + after + " in the " + NUKE_TICKS + " ticks after");
        long weak = hits.stream().filter(hit -> hit < FULL_HIT).count();
        rig.expect(weak <= 1, weak + " hits landed weak. Only the first may, straight after the sword came out");
        rig.expect(after >= 3, "Nuker did not get the pickaxe back after the fight");
    }

    // The keys move the camera. The body must stay and the husk beside it must be fought.
    private void freecamGuarded(Rig rig) {
        Props.hold(rig, 0, SWORD);
        fight(rig, home.north(2));
        Vec3 stood = rig.world().where();
        rig.client().enable("Freecam");
        rig.controls().hold(FORWARD);
        List<Double> hits;
        try {
            hits = hits(rig, SHORT_FIGHT);
        } finally {
            rig.controls().release(FORWARD);
        }
        double moved = rig.world().where().distanceTo(stood);
        double flown = rig.ask(mc -> mc.gameRenderer.mainCamera().position().distanceTo(mc.player.getEyePosition()));
        rig.client().disable("Freecam");
        rig.note(String.format(Locale.ROOT, "The camera flew %.1f blocks and the body moved %.2f and the hits took %s",
            flown, moved, hits));
        rig.expect(flown > 3, "The camera did not leave the body");
        rig.expect(moved < 0.1, "The body moved with the camera");
        expectFull(rig, hits, 3);
    }

    // A pool three wide lies ahead. Its water is level with the ground.
    private void overWater(Rig rig) {
        rig.world().fill(home.offset(-1, -1, -POOL_LENGTH), home.offset(1, -1, -2), "water");
        rig.ticks(SEEN_TICKS);
        enable(rig, "Jesus", "Speed");
        Moved moved = Motion.hold(rig, LONG_RUN, FORWARD, SPRINT);
        rig.note(String.format(Locale.ROOT, "It ran %.1f blocks and sank %.2f at the lowest", moved.across(),
            -moved.lowest()));
        rig.expect(moved.across() >= POOL_LENGTH - 4, "The run did not get across the pool");
        rig.expect(moved.lowest() > -0.5, "The player sank into the water");
        rig.expect(moved.setbacks() == 0, "The server put the run back " + moved.setbacks() + " times");
    }

    // Blocks a tick covered whilst food was being eaten.
    private double paceWhilstEating(Rig rig) {
        World world = rig.world();
        goHome(rig);
        world.hotbar(1, BEEF + " " + BEEF_GIVEN);
        world.command("effect give " + world.player() + " hunger 30 255 true");
        rig.until("AutoEat to begin a meal", WORK_TICKS, mc -> mc.player.isUsingItem());
        rig.controls().hold(FORWARD);
        rig.controls().hold(SPRINT);
        double covered = 0;
        int eating = 0;
        try {
            Vec3 last = rig.ask(mc -> mc.player.position());
            for (int tick = 0; tick < RUN_TICKS; tick++) {
                rig.ticks(1);
                Vec3 now = rig.ask(mc -> mc.player.position());
                if (rig.ask(mc -> mc.player.isUsingItem())) {
                    covered += now.subtract(last).horizontalDistance();
                    eating++;
                }
                last = now;
            }
        } finally {
            rig.controls().release(FORWARD);
            rig.controls().release(SPRINT);
        }
        world.command("effect clear " + world.player() + " hunger");
        rig.expect(eating >= SEEN_TICKS, "The meal was over after " + eating + " ticks. That proves nothing");
        return covered / Math.max(1, eating);
    }

    private void eatOnTheRun(Rig rig) {
        Props.hold(rig, 0);
        rig.client().enable("AutoEat");
        double slowed = paceWhilstEating(rig);
        rig.until("the meal to end", WORK_TICKS, mc -> !mc.player.isUsingItem());
        enable(rig, "NoSlowdown", "Sprint");
        double free = paceWhilstEating(rig);
        rig.note(String.format(Locale.ROOT, "A meal slowed the run to %.3f blocks a tick and with NoSlowdown it ran"
            + " %.3f", slowed, free));
        rig.expect(free >= slowed * 2, "The meal slowed the run as much with NoSlowdown on");
    }

    private void guardedLip(Rig rig) {
        perch(rig);
        enable(rig, "EdgeGuard", "Speed");
        Moved moved = Motion.hold(rig, RUN_TICKS, FORWARD);
        rig.note(String.format(Locale.ROOT, "It moved %.2f blocks and ended %.1f blocks lower", moved.across(),
            -moved.rise()));
        rig.expect(Math.abs(moved.rise()) < 0.1, "The player went over the lip");
    }

    // A walkway high up with three blocks missing from the middle of it.
    private void jumpedGap(Rig rig) {
        World world = rig.world();
        BlockPos start = home.above(PERCH - 1);
        world.fill(start, start.north(GAP_FROM - 1), STONE);
        world.fill(start.north(GAP_TO + 1), start.north(FAR_SIDE), STONE);
        world.teleport(home.getX() + 0.5, home.getY() + PERCH, home.getZ() + 0.5, 180, 0);
        enable(rig, "Sprint", "Parkour");
        Moved moved = Motion.hold(rig, LONG_RUN, FORWARD);
        rig.note(String.format(Locale.ROOT, "It ran %.1f blocks and ended %.1f blocks lower", moved.across(),
            -moved.rise()));
        rig.expect(moved.across() > GAP_TO, "The run did not get over the gap");
        rig.expect(Math.abs(moved.rise()) < 0.1, "The player fell into the gap");
    }

    // Ten blocks in the hand and a whole stack in the pack. The bridge takes most of the ten
    // and the hand must hold more at the end than it would have left.
    private void fedBridge(Rig rig) {
        perch(rig);
        Props.hold(rig, 0, COBBLE + " " + HANDFUL);
        Props.pack(rig, 9, COBBLE + " 64");
        enable(rig, "AutoReplenish", "Scaffold", "Sprint");
        Moved moved = Motion.hold(rig, LONG_RUN, FORWARD);
        expectBridged(rig, moved, 7);
        int inHand = rig.ask(mc -> mc.player.getInventory().getItem(0).getCount());
        rig.note("The hand held " + inHand + " blocks at the end");
        rig.expect(inHand > HANDFUL - 7, "The stack in the hand was never topped up");
    }

    // The stone stands beside the player in the room in the Nether and the bed goes down on
    // the other side once Nuker is at work.
    private void bedBesideNuker(Rig rig) {
        NetherRoom.enter(rig);
        try {
            List<BlockPos> stone = new ArrayList<>();
            for (int along = -1; along <= 1; along++) {
                stone.add(NetherRoom.FEET.offset(-2, 0, along));
                stone.add(NetherRoom.FEET.offset(-2, 1, along));
            }
            stone.forEach(pos -> NetherRoom.set(rig, pos, STONE));
            // A stone pickaxe is slow enough to still be at it when the bed goes down. Nuker
            // itself leaves the bed alone. Breaking it is the other module's work here.
            Props.hold(rig, 0, "stone_pickaxe");
            rig.client().set("Nuker", "Flat", ON);
            Props.pick(rig, "Nuker", "Blocks", "minecraft:" + BED, "minecraft:" + OBSIDIAN);
            enable(rig, "Nuker", "AntiBed");
            rig.until("Nuker to be on a block", WORK_TICKS, mc -> mc.gameMode.isDestroying());
            BlockPos foot = NetherRoom.FEET.east();
            NetherRoom.set(rig, foot, BED + "[part=foot,facing=east]");
            NetherRoom.set(rig, foot.east(), BED + "[part=head,facing=east]");
            int took = Props.await(rig, "the bed beside the player to be broken", BUILD_TICKS,
                () -> !NetherRoom.is(rig, foot, BED));
            boolean bothOn = rig.ask(mc -> rig.client().module("Nuker").isEnabled()
                && rig.client().module("AntiBed").isEnabled());
            long stood = stone.stream().filter(pos -> NetherRoom.is(rig, pos, STONE)).count();
            Props.await(rig, "Nuker to break more stone once the bed is gone", WORK_TICKS,
                () -> stone.stream().filter(pos -> NetherRoom.is(rig, pos, STONE)).count() < stood);
            rig.note("The bed went after about " + took + " ticks with " + stood + " of " + stone.size()
                + " blocks of stone still standing");
            rig.expect(bothOn, "One of the two switched the other off");
            rig.expect(stood > 0, "Nuker had finished before the bed went. That proves nothing");
        } finally {
            rig.client().disable("Nuker");
            rig.client().disable("AntiBed");
            NetherRoom.leave(rig, home);
        }
    }

    private void lootedArmour(Rig rig) {
        BlockPos chest = home.north(2);
        rig.world().command("setblock " + Props.at(chest) + " chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:"
            + CHESTPLATE + "\",count:1}]}");
        Props.hold(rig, 0);
        enable(rig, "ChestStealer", "AutoArmor");
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAtBlock(chest, Direction.SOUTH);
        rig.controls().click(USE);
        try {
            Props.await(rig, "the chestplate to come out of the chest", WORK_TICKS,
                () -> Props.carried(rig, CHESTPLATE) == 1);
        } finally {
            rig.act(mc -> {
                if (mc.gui.screen() != null) {
                    mc.player.closeContainer();
                }
            });
        }
        // The slots for armour are not on the screen of a chest. It goes on once that is shut.
        Props.await(rig, "the chestplate to be worn", WORK_TICKS, () -> Props.holds(rig, CHEST, CHESTPLATE));
    }

    // A stick is in the hand and the sword lies three slots along. The hit that brings the
    // sword out lands with the stick's strength or with the cooldown begun again.
    private void armedAura(Rig rig) {
        Props.hold(rig, 0, "stick");
        rig.world().hotbar(3, SWORD);
        rig.client().enable("AutoWeapon");
        fight(rig, home.north(2));
        List<Double> hits = hits(rig, SHORT_FIGHT);
        rig.note("The hits took " + hits);
        rig.expect(Props.holds(rig, "weapon.mainhand", SWORD), "The sword never came out");
        expectFull(rig, hits.subList(Math.min(1, hits.size()), hits.size()), 3);
    }

    // The stick brings the sword out with the first hit. Every hit after it has to be critical.
    private void fightKit(Rig rig) {
        Props.hold(rig, 0, "stick");
        rig.world().hotbar(3, SWORD);
        enable(rig, "Criticals", "AutoWeapon");
        fight(rig, home.north(2));
        List<Double> hits = hits(rig, SHORT_FIGHT);
        List<Double> armed = hits.subList(Math.min(1, hits.size()), hits.size());
        long plain = armed.stream().filter(hit -> hit < CRIT_HIT).count();
        rig.note("The hits took " + hits);
        rig.expect(Props.holds(rig, "weapon.mainhand", SWORD), "The sword never came out");
        rig.expect(armed.size() >= 3, "Only " + armed.size() + " hits landed with the sword out");
        rig.expect(plain == 0, plain + " of the hits with the sword were not critical");
    }

    // A slow pickaxe and a big quarry keep Nuker at work until hunger sets in. The meal takes
    // the hand and the pickaxe has to come back for the rest.
    private void digWithAMeal(Rig rig) {
        World world = rig.world();
        quarry(rig);
        world.refill(quarryFrom().above(2), quarryTo().above(2), STONE);
        Props.hold(rig, 0);
        world.hotbar(1, "stone_pickaxe");
        world.hotbar(2, BEEF + " " + BEEF_GIVEN);
        enable(rig, "AutoTool", "Nuker", "AutoEat");
        world.command("effect give " + world.player() + " hunger 30 255 true");
        rig.until("AutoEat to begin a meal", WORK_TICKS, mc -> mc.player.isUsingItem());
        rig.until("the meal to end", WORK_TICKS, mc -> !mc.player.isUsingItem());
        world.command("effect clear " + world.player() + " hunger");
        int atTheMeal = broken(rig);
        int eaten = BEEF_GIVEN - Props.carried(rig, BEEF);
        Props.await(rig, "Nuker to break more stone after the meal", WORK_TICKS, () -> broken(rig) > atTheMeal);
        rig.note("The player ate " + eaten + " steaks with " + atTheMeal + " blocks broken by the end of the meal");
        rig.expect(eaten >= 1, "AutoEat did not eat beside Nuker");
    }

    // Armour in the pack goes on. A blow brings the totem to the offhand. Hunger brings a meal.
    // A held click on stone brings the pickaxe out. None of the four may undo another.
    private void helpersInTurn(Rig rig) {
        World world = rig.world();
        BlockPos block = home.north().above();
        world.setBlock(block, STONE);
        Props.hold(rig, 0);
        world.hotbar(1, BEEF + " " + BEEF_GIVEN);
        world.hotbar(2, PICKAXE);
        Props.pack(rig, 5, TOTEM);
        Props.pack(rig, 6, CHESTPLATE);
        rig.client().set("AutoTotem", "Health", "5");
        enable(rig, "AutoArmor", "AutoTotem", "AutoEat", "AutoTool");
        Props.await(rig, "AutoArmor to put the chestplate on", SWAP_TICKS, () -> Props.holds(rig, CHEST, CHESTPLATE));
        world.command("damage " + world.player() + " 12");
        Props.await(rig, "AutoTotem to take the offhand", SWAP_TICKS, () -> Props.holds(rig, OFFHAND, TOTEM));
        world.command("effect give " + world.player() + " hunger 30 255 true");
        Props.await(rig, "AutoEat to eat a steak", WORK_TICKS * 2, () -> Props.carried(rig, BEEF) < BEEF_GIVEN);
        world.command("effect clear " + world.player() + " hunger");
        rig.until("the meal to end", WORK_TICKS, mc -> !mc.player.isUsingItem());
        rig.controls().aimAtBlock(block, Direction.SOUTH);
        rig.controls().holdMouse(ATTACK);
        try {
            Props.await(rig, "AutoTool to mine the stone with the pickaxe", WORK_TICKS, () -> !Props.is(rig, block, STONE));
        } finally {
            rig.controls().releaseMouse(ATTACK);
        }
        rig.expect(Props.holds(rig, CHEST, CHESTPLATE), "The chestplate came off again");
        rig.expect(Props.holds(rig, OFFHAND, TOTEM), "The totem left the offhand again");
    }

    // The goal lies thirty blocks north over flat ground. A husk stands beside the way and
    // hunger sets in as the walk begins. The walk is past the husk in a second and a half. With
    // their pauses on a meal at that moment would rightly keep KillAura off it.
    private void walkWithHelpers(Rig rig) {
        World world = rig.world();
        Props.hold(rig, 0, SWORD);
        world.hotbar(1, BEEF + " " + BEEF_GIVEN);
        Props.summon(rig, HUSK, home.offset(2, 0, -WALK / 3), FRAIL_HUSK);
        rig.client().set("KillAura", "Hostile mobs", ON);
        rig.client().set("KillAura", "Pause on use", "off");
        rig.client().set("AutoEat", "Pause combat", "off");
        rig.ticks(SEEN_TICKS);
        enable(rig, "KillAura", "AutoEat");
        world.command("effect give " + world.player() + " hunger 30 255 true");
        rig.client().command("goto " + home.getX() + " " + (home.getZ() - WALK));
        try {
            rig.until("the walk to end", WORK_TICKS * 3, mc -> !Travel.walking());
        } finally {
            rig.client().command("goto stop");
            world.command("effect clear " + world.player() + " hunger");
        }
        double off = rig.ask(mc -> mc.player.position()).distanceTo(Vec3.atBottomCenterOf(home.north(WALK)));
        int eaten = BEEF_GIVEN - Props.carried(rig, BEEF);
        rig.note(String.format(Locale.ROOT, "The walk ended %.1f blocks from its goal with %d steaks eaten and %d"
            + " husks left alive", off, eaten, Props.living(rig, HUSK)));
        rig.expect(off < 2.5, "The walk did not reach its goal");
        rig.expect(eaten >= 1, "AutoEat did not eat on the walk");
        rig.expect(Props.living(rig, HUSK) == 0, "KillAura did not kill the husk beside the way");
    }

    // The hand is empty. A held click on stone has to bring the pickaxe out and a click on the
    // husk the sword.
    private void toolThenWeapon(Rig rig) {
        World world = rig.world();
        BlockPos block = home.north().above();
        world.setBlock(block, STONE);
        Props.hold(rig, 0);
        world.hotbar(1, SWORD);
        world.hotbar(2, PICKAXE);
        Props.summon(rig, HUSK, home.east(2), TOUGH_HUSK);
        enable(rig, "AutoTool", "AutoWeapon");
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAtBlock(block, Direction.SOUTH);
        rig.controls().holdMouse(ATTACK);
        int took;
        try {
            took = Props.await(rig, "the stone to be mined", WORK_TICKS, () -> !Props.is(rig, block, STONE));
        } finally {
            rig.controls().releaseMouse(ATTACK);
        }
        rig.ticks(SEEN_TICKS);
        Husk husk = husk(rig);
        rig.controls().aimAtEntity(husk);
        rig.ticks(2);
        rig.controls().click(ATTACK);
        Props.await(rig, "the sword to come out for the husk", SWAP_TICKS, () -> Props.holds(rig, "weapon.mainhand", SWORD));
        rig.note("The stone was gone after about " + took + " ticks");
        rig.expect(took <= BY_PICKAXE, "The stone took " + took + " ticks. A bare hand needs a hundred and fifty");
    }

    private static Husk husk(Rig rig) {
        return rig.ask(mc -> {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof Husk husk) {
                    return husk;
                }
            }
            return null;
        });
    }

    // One totem goes to the offhand and one stays in the pack. A blow that would kill pops the
    // first and the second has to take its place whilst the fight goes on.
    private void secondTotem(Rig rig) {
        World world = rig.world();
        Props.hold(rig, 0, SWORD);
        Props.pack(rig, 5, TOTEM);
        Props.pack(rig, 6, TOTEM);
        rig.client().enable("AutoTotem");
        fight(rig, home.north(2));
        Props.await(rig, "the first totem in the offhand", SWAP_TICKS, () -> Props.holds(rig, OFFHAND, TOTEM));
        world.command("damage " + world.player() + " 100");
        Props.await(rig, "the first totem to pop", SWAP_TICKS, () -> Props.carried(rig, TOTEM) == 1);
        Props.await(rig, "the second totem in the offhand", SWAP_TICKS, () -> Props.holds(rig, OFFHAND, TOTEM));
        List<Double> hits = hits(rig, SHORT_FIGHT / 2);
        Stage.expectAlive(rig);
        rig.note("After the pop the hits took " + hits);
        rig.expect(hits.size() >= 2, "KillAura did not fight on after the pop");
    }

    // The wall is one block thick and eight high. The climb ends on top of it and the walk goes
    // on over the far edge.
    private void overTheWall(Rig rig) {
        rig.world().fill(home.offset(-1, 0, -3), home.offset(1, WALL - 1, -3), STONE);
        enable(rig, "Spider", "NoFall");
        Moved moved = Motion.hold(rig, WORK_TICKS / 2, FORWARD);
        rig.ticks(STEADY_TICKS);
        float health = rig.world().health();
        rig.note(String.format(Locale.ROOT, "It climbed %.1f blocks and went %.1f blocks on and has %.0f health",
            moved.highest(), moved.across(), health));
        rig.expect(moved.highest() >= WALL - 0.5, "Spider did not climb the wall");
        rig.expect(moved.across() >= 4, "The walk did not go on over the wall");
        rig.expect(health == FULL_HEALTH, "The drop behind the wall cost " + (FULL_HEALTH - health) + " health");
    }

    // The pond lies ahead and the rod bites fast. Everything AntiAFK does goes on meanwhile.
    private void fishingAway(Rig rig) {
        rig.world().fill(home.offset(-2, -3, -8), home.offset(2, -1, -3), "water");
        Props.hold(rig, 0, "fishing_rod[enchantments={\"minecraft:lure\":3}]");
        rig.controls().look(180, 25);
        rig.client().set("AntiAFK", "Interval", "1");
        enable(rig, "AutoFish", "AntiAFK");
        int took = Props.await(rig, "a catch to reach the backpack", FISHING_TICKS, () -> caught(rig) > 0);
        rig.note("The first catch was in the backpack after about " + took + " ticks");
    }

    // Stacks in the backpack and hotbar that are no fishing rod.
    private static int caught(Rig rig) {
        return rig.ask(mc -> {
            int stacks = 0;
            for (int slot = 0; slot < mc.player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = mc.player.getInventory().getItem(slot);
                if (!stack.isEmpty() && !stack.is(Items.FISHING_ROD)) {
                    stacks++;
                }
            }
            return stacks;
        });
    }

    // A blow takes the health under AutoGap's line and hunger sets in at the same time.
    private void twoFeeders(Rig rig) {
        World world = rig.world();
        Props.hold(rig, 0);
        world.hotbar(1, BEEF + " " + BEEF_GIVEN);
        world.hotbar(2, GAPPLE + " 2");
        enable(rig, "AutoGap", "AutoEat");
        world.command("damage " + world.player() + " 8");
        world.command("effect give " + world.player() + " hunger 30 255 true");
        Props.await(rig, "AutoGap to eat a golden apple", WORK_TICKS, () -> Props.carried(rig, GAPPLE) < 2);
        Props.await(rig, "AutoEat to eat a steak", WORK_TICKS * 2, () -> Props.carried(rig, BEEF) < BEEF_GIVEN);
        world.command("effect clear " + world.player() + " hunger");
        rig.until("the last meal to end", WORK_TICKS, mc -> !mc.player.isUsingItem());
        rig.note("It ate " + (2 - Props.carried(rig, GAPPLE)) + " golden apples and " + (BEEF_GIVEN
            - Props.carried(rig, BEEF)) + " steaks");
        rig.expect(Props.carried(rig, GAPPLE) == 1, "AutoGap ate a second golden apple it had no need of");
    }

    // A server that lets nobody float throws out a player who hangs in the air for four seconds.
    // Flight dips in time by itself and has to go on doing that whilst the keys move the camera.
    private void hoverInFreecam(Rig rig) {
        // The dips are counted in ticks of the game and the server counts its own. Seventy leave
        // ten to spare and a game that stutters for half a second uses them up.
        rig.client().set("Flight", "Kick interval", "40");
        rig.client().enable("Flight");
        Motion.hold(rig, RUN_TICKS / 2, JUMP);
        double height = rig.ask(mc -> mc.player.getY());
        rig.client().enable("Freecam");
        rig.controls().hold(FORWARD);
        try {
            rig.ticks(WORK_TICKS);
        } finally {
            rig.controls().release(FORWARD);
        }
        Stage.expectAlive(rig);
        double sank = height - rig.ask(mc -> mc.player.getY());
        rig.client().disable("Freecam");
        rig.client().disable("Flight");
        rig.ticks(STEADY_TICKS);
        rig.note(String.format(Locale.ROOT, "The body hung %.1f blocks up for %d ticks and sank %.2f", height
            - home.getY(), WORK_TICKS, sank));
        rig.expect(height - home.getY() >= 1, "Flight never lifted the body. That proves nothing");
        rig.expect(Math.abs(sank) < 1.5, "The body did not keep its height");
    }

    // The husk walks and hits. A world of the rig's own is peaceful until the case says otherwise.
    private void unmovedFight(Rig rig) {
        World world = rig.world();
        boolean peaceful = world.count("difficulty") == PEACEFUL;
        world.attempt("difficulty easy");
        try {
            Props.hold(rig, 0, SWORD);
            world.command("effect give " + world.player() + " regeneration 60 255 true");
            rig.client().set("KillAura", "Hostile mobs", ON);
            enable(rig, "NoKnockback", "AntiPush", "KillAura");
            Props.summon(rig, HUSK, home.north(2), ANGRY_HUSK);
            Vec3 stood = rig.ask(mc -> mc.player.position());
            double first = 200;
            double furthest = 0;
            int blows = 0;
            boolean hurt = false;
            for (int tick = 0; tick < FIGHT_TICKS; tick++) {
                rig.ticks(1);
                furthest = Math.max(furthest, rig.ask(mc -> mc.player.position()).subtract(stood).horizontalDistance());
                boolean hurting = rig.ask(mc -> mc.player.hurtTime > 0);
                if (hurting && !hurt) {
                    blows++;
                }
                hurt = hurting;
            }
            double lost = first - huskHealth(rig);
            rig.note(String.format(Locale.ROOT, "The husk landed %d blows and the player moved %.2f blocks at most and"
                + " the husk lost %.0f health", blows, furthest, lost));
            rig.expect(blows >= 2, "The husk never hit the player. That proves nothing");
            rig.expect(furthest < 0.5, "The blows pushed the player");
            rig.expect(lost > 0, "KillAura never hit back");
        } finally {
            world.attempt("kill @e[type=husk]");
            if (peaceful) {
                world.attempt("difficulty peaceful");
            }
        }
    }
}
