package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigFailure;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.core.World;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// One case for each combat module that a mob or the player's own gear can prove. The server
// says whether the mob died and what the player wears and which blocks stand. What needs another
// player to fight is in the Server suite.
public final class CombatSuite implements Suite {

    private static final String NAME = "Combat";
    private static final String SLINGSHOT = "Slingshot makes an arrow hit much harder";
    private static final String SKY_DROP = "SkyDrop drops an arrow onto a mob much harder than a plain shot";
    private static final String SWAP_GAINS = "AttributeSwap adds the sharpness of another blade to the hit";
    private static final String SWAP_NEVER_WEAKER =
        "AttributeSwap never makes a hit weaker and puts the first item back";
    private static final String SWAP_SPARES =
        "AttributeSwap keeps to the item in hand against a target it is not set for";
    private static final String BED_SAFE = "AntiBed leaves a bed alone where beds are safe";
    private static final String SKY_DROP_FAULT = "Now and then the server refuses the way home after the drop and "
        + "leaves the player by the target. The arrow still lands";
    private static final String SLINGSHOT_FAULT = "The long hop ends where the server holds no chunk. The server "
        + "then refuses the way home and leaves the player 189 blocks from where they stood";
    private static final int ATTACK = InputConstants.MOUSE_BUTTON_LEFT;
    private static final int USE = InputConstants.MOUSE_BUTTON_RIGHT;

    private static final String SWORD = "diamond_sword";
    private static final String HUSK = "husk";
    private static final String CUBE = "sulfur_cube";
    private static final String BED = "red_bed";
    private static final String TOTEM = "totem_of_undying";
    private static final String OBSIDIAN = "obsidian";
    private static final String OFFHAND = "weapon.offhand";
    private static final String ON = "on";
    // A husk can come as a child and some modules leave children alone.
    private static final String GROWN_STILL = "{NoAI:1b,PersistenceRequired:1b,IsBaby:0b}";
    // The same with health enough to live through anything one case does to it.
    private static final String TOUGH_STILL = "{NoAI:1b,PersistenceRequired:1b,IsBaby:0b,Health:100f,"
        + "attributes:[{id:\"minecraft:max_health\",base:100}]}";
    private static final int TOUGH_HEALTH = 100;
    private static final int COW_HEALTH = 10;
    private static final String STRENGTH_ARROWS = "tipped_arrow[potion_contents={potion:\"minecraft:strength\"}] 8";
    private static final String SHARP_SWORD = SWORD + "[enchantments={\"minecraft:sharpness\":5}]";
    private static final String HOLDING = "holding";
    private static final float EAST = -90;
    private static final double AIMED = 8;

    private static final int SWORD_SLOT = 4;
    private static final int SEEN_TICKS = 10;
    private static final int DRAW_TICKS = 15;
    private static final int KILL_TICKS = 200;
    private static final int SWAP_TICKS = 60;
    private static final int BUILD_TICKS = 80;
    private static final int CLICK_TICKS = 60;
    private static final int HUSK_HEALTH = 20;
    // A diamond sword takes seven and half as much again with a critical hit.
    private static final int CRIT_HIT = 10;
    private static final int ARROWS = 64;
    private static final int SWINGS = 4;
    // A sword is ready again after this many ticks.
    private static final int SWING_GAP = 14;
    private static final float FULL_HEALTH = 20;
    private static final int ANVIL_HEIGHT = 12;
    private static final int ARROW_FROM = 9;
    private static final int FLIGHT_TICKS = 30;
    private static final int FULL_DRAW = 25;
    // A diamond sword takes seven health off a husk and a stick takes one. Sharpness five adds three.
    private static final int BLADE_HIT = 5;
    private static final int STICK_HIT = 2;
    private static final int SHARPER = 2;
    // A wind charge under the feet throws a player some seven blocks up.
    private static final int COMBO_TICKS = 80;
    private static final double LAUNCHED = 3;
    // A mace hit from the ground takes six health. Two of them stay under this.
    private static final int SMASHED = 15;
    // A chorus fruit takes thirty two ticks to eat.
    private static final int EAT_TICKS = 60;
    // ChorusHold lets a teleport go by itself after forty ticks.
    private static final int HELD_LONGEST = 60;
    private static final double MOVED = 0.3;
    private static final int FUSE = 30;
    private static final String LIT_CREEPER = "{NoAI:1b,PersistenceRequired:1b,ignited:1b,Fuse:" + FUSE + "}";
    // The stage is dirt three deep on bedrock.
    private static final int PIT_DEPTH = 3;
    private static final int TNT_FUSE = 50;
    // Ticks before the end of its fuse at which TNT that must not blow is taken away.
    private static final int TNT_SPARE = 10;
    // Evade hops four blocks.
    private static final double HOPPED = 3;
    // A creeper hurts out to six blocks and TNT out to eight.
    private static final double CREEPER_REACH = 6;
    private static final double TNT_REACH = 8;
    private static final int LAP_TICKS = 70;
    private static final double HALF_LAP = 180;
    private static final double RING = 2.5;
    private static final double OFF_RING = 1.25;

    private BlockPos home;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        Grounds.play(rig, NAME, plays(), stage -> home = stage.home(),
            Grounds.Limits.NONE.aloneOnly(SWAP_GAINS).knownOnServer(SLINGSHOT, SLINGSHOT_FAULT)
                .knownOnServer(SKY_DROP, SKY_DROP_FAULT).holdsBack(SWAP_NEVER_WEAKER, SWAP_SPARES, BED_SAFE));
    }

    private Map<String, Case.Body> plays() {
        Map<String, Case.Body> plays = new LinkedHashMap<>();
        plays.put("KillAura kills a mob in reach", this::killAura);
        plays.put("TriggerBot kills the mob under the crosshair", this::triggerBot);
        plays.put("TriggerBot with Only crits holds its swing until a jump makes the hit critical",
            this::triggerBotCrits);
        plays.put("AutoWeapon takes the sword out for a hit", this::autoWeapon);
        plays.put("AutoTotem puts a totem in the offhand", this::autoTotem);
        plays.put("Offhand puts its item in the offhand", this::offhand);
        plays.put("AutoArmor puts armour on", this::autoArmor);
        plays.put("BowAimbot turns a drawn bow to its target", this::bowAimbot);
        plays.put("Surround walls your feet in", this::surround);
        plays.put("SelfTrap puts a roof over your head", this::selfTrap);
        plays.put("SelfWeb webs your own block", this::selfWeb);
        plays.put("AutoClicker hits the mob under the crosshair", this::autoClicker);
        plays.put("BowSpam shoots arrow after arrow", this::bowSpam);
        plays.put("Reach hits a mob five blocks off", this::reach);
        plays.put("Burrow puts a block into your own space", this::burrow);
        plays.put("Wrecker breaks a boat with one hit", this::wrecker);
        plays.put("Cocoon seals you in", this::cocoon);
        plays.put("AntiAnvil roofs you before a falling anvil lands", this::antiAnvil);
        plays.put("ArrowDodge steps out of the way of an arrow", this::arrowDodge);
        plays.put(SLINGSHOT, this::slingshot);
        plays.put("Quiver puts the tipped arrow on the string", this::quiver);
        plays.put("PotionArrows shoots a strength arrow into yourself", this::potionArrows);
        plays.put("PotionArrows shoots again when its first arrow brought no effect", this::potionArrowsAgain);
        plays.put("AntiAnchor breaks the anchor set beside you", this::antiAnchor);
        plays.put(SWAP_NEVER_WEAKER, this::attributeSwapNeverWeaker);
        plays.put(SWAP_GAINS, this::attributeSwapGains);
        plays.put("AttributeSwap brings the fire of another blade to the hit", this::attributeSwapFire);
        plays.put(SWAP_SPARES, this::attributeSwapSpares);
        plays.put("MaceCombo launches off a wind charge and smashes a mob", this::maceCombo);
        plays.put("SelfAnvil drops an anvil into your own block", this::selfAnvil);
        plays.put("ChorusHold keeps you where you stood until the fruit is put away", this::chorusHold);
        plays.put("ChorusHold lets the teleport go by itself after two seconds", this::chorusHoldLimit);
        plays.put("Evade hops clear of a creeper before it blows", this::evadeCreeper);
        plays.put("Evade hops clear of lit TNT once its setting is on", this::evadeTnt);
        plays.put("Evade hops out of a pit that a creeper is about to blow up", this::evadePit);
        plays.put("Protect fights off a mob near the one it guards and never hits its ward", this::protect);
        plays.put("Confuse runs you round the mob KillAura fights", this::confuse);
        plays.put(SKY_DROP, this::skyDrop);
        plays.put("Blocker blocks the piston aimed into your hole", this::blockerPiston);
        plays.put("Blocker fills the crystal spots round your hole", this::blockerCrystals);
        plays.put("AntiBed breaks a bed set beside you in the Nether", this::antiBed);
        plays.put(BED_SAFE, this::antiBedOverworld);
        plays.put("CubeRigger loads TNT into a sulfur cube in reach", this::cubeRiggerLoads);
        plays.put("CubeRigger rigs the TNT cube you light", this::cubeRiggerRigs);
        return plays;
    }

    private void killAura(Rig rig) {
        Props.hold(rig, 0, SWORD);
        Props.summon(rig, HUSK, home.north(2), GROWN_STILL);
        rig.client().set("KillAura", "Hostile mobs", ON);
        rig.client().enable("KillAura");
        int took = Props.await(rig, "the husk to die", KILL_TICKS, () -> Props.living(rig, HUSK) == 0);
        rig.note("The husk was dead after about " + took + " ticks");
    }

    // The player looks north and the husk stands right there.
    private void triggerBot(Rig rig) {
        Props.hold(rig, 0, SWORD);
        Props.summon(rig, HUSK, home.north(2), GROWN_STILL);
        rig.client().enable("TriggerBot");
        int took = Props.await(rig, "the husk to die", KILL_TICKS, () -> Props.living(rig, HUSK) == 0);
        rig.note("The husk was dead after about " + took + " ticks");
    }

    // Standing still no hit would be critical and none goes out. A jump brings one on the way down.
    private void triggerBotCrits(Rig rig) {
        Props.hold(rig, 0, SWORD);
        Props.summon(rig, HUSK, home.north(2), TOUGH_STILL);
        rig.client().set("TriggerBot", "Only crits", ON);
        rig.client().enable("TriggerBot");
        rig.ticks(SWAP_TICKS);
        int standing = TOUGH_HEALTH - Props.health(rig, HUSK);
        rig.controls().hold(InputConstants.KEY_SPACE);
        rig.ticks(SEEN_TICKS / 2);
        rig.controls().release(InputConstants.KEY_SPACE);
        rig.ticks(SWAP_TICKS / 2);
        int jumped = TOUGH_HEALTH - Props.health(rig, HUSK) - standing;
        rig.client().enable("Criticals");
        rig.ticks(SWAP_TICKS / 2);
        int helped = TOUGH_HEALTH - Props.health(rig, HUSK) - standing - jumped;
        rig.note("The husk lost " + standing + " health whilst the player stood and " + jumped + " after one jump and "
            + helped + " from the ground with Criticals on");
        rig.expect(standing == 0, "TriggerBot hit from the ground with Only crits on");
        rig.expect(jumped >= CRIT_HIT, "No critical hit landed on the way down from a jump");
        rig.expect(helped >= CRIT_HIT, "TriggerBot still waited for a jump with Criticals on");
    }

    private void autoWeapon(Rig rig) {
        rig.world().hotbar(SWORD_SLOT, SWORD);
        Props.hold(rig, 0);
        Props.summon(rig, HUSK, home.north(2), GROWN_STILL);
        rig.ticks(SEEN_TICKS);
        rig.client().enable("AutoWeapon");
        rig.controls().click(ATTACK);
        rig.ticks(SEEN_TICKS);
        String slot = rig.world().facts().get("slot");
        rig.expect(Integer.toString(SWORD_SLOT).equals(slot),
            "The server has the player on slot " + slot + " and the sword lies in slot " + SWORD_SLOT);
    }

    private void autoTotem(Rig rig) {
        Props.pack(rig, 5, TOTEM);
        rig.client().enable("AutoTotem");
        Props.await(rig, "the totem to reach the offhand", SWAP_TICKS, () -> Props.holds(rig, OFFHAND, TOTEM));
    }

    private void offhand(Rig rig) {
        Props.pack(rig, 6, "end_crystal 8");
        rig.client().enable("Offhand");
        Props.await(rig, "the crystals to reach the offhand", SWAP_TICKS,
            () -> Props.holds(rig, OFFHAND, "end_crystal"));
    }

    private void autoArmor(Rig rig) {
        Props.pack(rig, 3, "diamond_chestplate");
        Props.pack(rig, 4, "iron_helmet");
        rig.client().enable("AutoArmor");
        Props.await(rig, "the armour to be worn", SWAP_TICKS,
            () -> Props.holds(rig, "armor.chest", "diamond_chestplate")
                && Props.holds(rig, "armor.head", "iron_helmet"));
    }

    // The husk stands eight blocks east and the player looks north as it draws.
    private void bowAimbot(Rig rig) {
        Props.hold(rig, 0, "bow");
        Props.pack(rig, 0, "arrow 16");
        Props.summon(rig, HUSK, home.east(8), GROWN_STILL);
        rig.client().set("BowAimbot", "Mobs", "all");
        rig.client().enable("BowAimbot");
        rig.ticks(SEEN_TICKS);
        rig.controls().holdMouse(USE);
        rig.ticks(DRAW_TICKS);
        float yaw = Float.parseFloat(rig.world().facts().get("yaw"));
        rig.controls().releaseMouse(USE);
        rig.note("The server had the drawn bow facing " + yaw + " degrees and the husk stands at " + EAST);
        rig.expect(Math.abs(Mth.wrapDegrees(yaw - EAST)) < AIMED, "The bow did not turn to the husk");
    }

    private void surround(Rig rig) {
        Props.hold(rig, 0, OBSIDIAN + " 16");
        rig.client().enable("Surround");
        List<BlockPos> walls = List.of(home.north(), home.east(), home.south(), home.west());
        Props.await(rig, "the four blocks round the feet", BUILD_TICKS,
            () -> walls.stream().allMatch(pos -> Props.is(rig, pos, OBSIDIAN)));
    }

    private void selfTrap(Rig rig) {
        Props.hold(rig, 0, OBSIDIAN + " 16");
        rig.client().enable("SelfTrap");
        Props.await(rig, "the block over the head", BUILD_TICKS, () -> Props.is(rig, home.above(2), OBSIDIAN));
    }

    private void selfWeb(Rig rig) {
        Props.hold(rig, 0, "cobweb 4");
        rig.client().enable("SelfWeb");
        Props.await(rig, "the web in the player's own block", BUILD_TICKS, () -> Props.is(rig, home, "cobweb"));
    }

    private void autoClicker(Rig rig) {
        Props.hold(rig, 0, SWORD);
        Props.summon(rig, HUSK, home.north(2), GROWN_STILL);
        rig.ticks(SEEN_TICKS);
        rig.client().enable("AutoClicker");
        rig.ticks(CLICK_TICKS);
        int health = Props.health(rig, HUSK);
        rig.note("The husk has " + health + " of " + HUSK_HEALTH + " health after " + CLICK_TICKS + " ticks");
        rig.expect(health < HUSK_HEALTH, "No click hurt the husk under the crosshair");
    }

    private void bowSpam(Rig rig) {
        Props.hold(rig, 0, "bow");
        Props.pack(rig, 0, "arrow " + ARROWS);
        rig.client().enable("BowSpam");
        rig.controls().holdMouse(USE);
        rig.ticks(CLICK_TICKS);
        rig.controls().releaseMouse(USE);
        rig.ticks(SEEN_TICKS);
        int shot = ARROWS - Props.carried(rig, "arrow");
        rig.note(shot + " arrows left the bow in " + CLICK_TICKS + " ticks");
        rig.expect(shot >= SWINGS, "BowSpam shot no more than a held bow does");
    }

    // The game itself reaches three blocks. The husk stands further off than that.
    private void reach(Rig rig) {
        Props.hold(rig, 0, SWORD);
        Props.summon(rig, HUSK, home.north(5), GROWN_STILL);
        rig.ticks(SEEN_TICKS);
        swing(rig);
        int plain = Props.health(rig, HUSK);
        rig.client().enable("Reach");
        swing(rig);
        int reached = Props.health(rig, HUSK);
        rig.note("The husk has " + reached + " health after the swings with Reach and had " + plain + " before");
        rig.expect(plain == HUSK_HEALTH, "The swings hit the husk without Reach. That proves nothing");
        rig.expect(reached < HUSK_HEALTH, "No swing reached the husk");
    }

    private static void swing(Rig rig) {
        for (int swing = 0; swing < SWINGS; swing++) {
            rig.controls().click(ATTACK);
            rig.ticks(SWING_GAP);
        }
    }

    private void burrow(Rig rig) {
        Props.hold(rig, 0, OBSIDIAN + " 16");
        rig.client().enable("Burrow");
        Props.await(rig, "the block in the player's own space", BUILD_TICKS * 2, () -> Props.is(rig, home, OBSIDIAN));
    }

    // A boat takes five punches. One is enough with Wrecker.
    private void wrecker(Rig rig) {
        rig.expect(boatsAfterOnePunch(rig) == 1, "One punch broke the boat without Wrecker. That proves nothing");
        rig.world().attempt("kill @e[type=oak_boat]");
        rig.client().enable("Wrecker");
        rig.expect(boatsAfterOnePunch(rig) == 0, "The boat stood after one punch");
    }

    private int boatsAfterOnePunch(Rig rig) {
        BlockPos spot = home.north(2);
        Props.summon(rig, "oak_boat", spot, "{}");
        Props.hold(rig, 0);
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAt(new Vec3(spot.getX() + 0.5, spot.getY() + 0.3, spot.getZ() + 0.5));
        rig.ticks(2);
        rig.controls().click(ATTACK);
        rig.ticks(SEEN_TICKS);
        rig.controls().look(180, 0);
        return Props.living(rig, "oak_boat");
    }

    private void cocoon(Rig rig) {
        Props.hold(rig, 0, OBSIDIAN + " 64");
        rig.client().enable("Cocoon");
        List<BlockPos> shell = List.of(home.north().above(), home.east().above(), home.south().above(),
            home.west().above(), home.above(2));
        Props.await(rig, "the shell round the head", BUILD_TICKS * 3,
            () -> shell.stream().allMatch(pos -> Props.is(rig, pos, OBSIDIAN)));
    }

    private void antiAnvil(Rig rig) {
        Props.hold(rig, 0, OBSIDIAN + " 16");
        rig.client().enable("AntiAnvil");
        BlockPos above = home.above(ANVIL_HEIGHT);
        rig.world().command("summon falling_block " + (above.getX() + 0.5) + " " + above.getY() + " "
            + (above.getZ() + 0.5) + " {BlockState:\"minecraft:anvil\",Time:1,HurtEntities:1b}");
        try {
            Props.await(rig, "a block over the head", FLIGHT_TICKS * 2, () -> !rig.world().isAir(home.above(2)));
        } finally {
            rig.note("Two and three blocks over the feet stand " + rig.world().blockAt(home.above(2)) + " and "
                + rig.world().blockAt(home.above(3)) + " and the feet stand in " + rig.world().blockAt(home));
        }
        rig.ticks(FLIGHT_TICKS);
        float health = rig.world().health();
        rig.expect(health == FULL_HEALTH, "The anvil cost " + (FULL_HEALTH - health) + " health");
    }

    // An arrow flies along the line the player stands on.
    private void arrowDodge(Rig rig) {
        float hit = shotAt(rig);
        rig.world().command("effect give " + rig.world().player() + " instant_health 1 20 true");
        rig.world().teleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 180, 0);
        rig.client().enable("ArrowDodge");
        float dodged = shotAt(rig);
        rig.note("The arrow left " + hit + " health and with ArrowDodge " + dodged);
        rig.expect(hit < FULL_HEALTH, "The arrow missed without ArrowDodge. That proves nothing");
        rig.expect(dodged == FULL_HEALTH, "The arrow still hit");
    }

    private float shotAt(Rig rig) {
        rig.world().attempt("kill @e[type=arrow]");
        rig.world().command("summon arrow " + (home.getX() + 0.5) + " " + (home.getY() + 1.2) + " "
            + (home.getZ() + 0.5 - ARROW_FROM) + " {Motion:[0.0d,0.05d,1.5d]}");
        rig.ticks(FLIGHT_TICKS);
        return rig.world().health();
    }

    // An arrow hits as hard as it is fast. A husk twelve blocks south takes one plain arrow and
    // lives.
    private void slingshot(Rig rig) {
        int plain = huskAfterArrow(rig);
        rig.client().enable("Slingshot");
        int slung = huskAfterArrow(rig);
        long strayed = Math.round(Math.abs(Double.parseDouble(rig.world().facts().get("z")) - home.getZ() - 0.5));
        rig.note("One arrow left the husk " + slung + " health with Slingshot and " + plain + " without");
        rig.expect(plain > 0 && plain < HUSK_HEALTH, "A plain arrow did not hurt the husk and leave it alive. That "
            + "proves nothing");
        rig.expect(strayed == 0, "The server holds the player " + strayed + " blocks from where they shot");
        rig.expect(slung < plain - 5, "The arrow hit no harder");
    }

    // One husk and no other. A husk that dies where it stands is still in the way of a click for
    // a second and the old one goes far down first.
    private static void freshHusk(Rig rig, BlockPos spot) {
        rig.world().attempt("execute as @e[type=husk] at @s run tp @s ~ ~-300 ~");
        rig.world().attempt("kill @e[type=husk]");
        Props.summon(rig, HUSK, spot, GROWN_STILL);
    }

    // The health left to a husk after one arrow from a full draw. Nought for a dead one.
    private int huskAfterArrow(Rig rig) {
        rig.world().attempt("kill @e[type=arrow]");
        freshHusk(rig, home.south(12));
        Props.hold(rig, 0, "bow");
        Props.pack(rig, 0, "arrow 16");
        rig.controls().look(0, -2);
        rig.ticks(SEEN_TICKS);
        rig.controls().holdMouse(USE);
        rig.ticks(FULL_DRAW);
        rig.controls().releaseMouse(USE);
        rig.ticks(FLIGHT_TICKS);
        int health = Props.living(rig, HUSK) == 0 ? 0 : Props.health(rig, HUSK);
        rig.controls().look(180, 0);
        return health;
    }

    private void quiver(Rig rig) {
        rig.expect(!tippedArrowFlew(rig), "A tipped arrow flew without Quiver. That proves nothing");
        rig.client().enable("Quiver");
        rig.expect(tippedArrowFlew(rig), "The plain arrow flew and the tipped one stayed in the pack");
    }

    // Plain arrows lie ahead of tipped ones in the pack and the game takes the first it finds.
    private boolean tippedArrowFlew(Rig rig) {
        rig.world().attempt("kill @e[type=arrow]");
        Props.hold(rig, 0, "bow");
        Props.pack(rig, 0, "arrow 16");
        Props.pack(rig, 5, "tipped_arrow[potion_contents={potion:\"minecraft:poison\"}] 8");
        rig.controls().look(0, -5);
        rig.ticks(SEEN_TICKS * 2);
        rig.controls().holdMouse(USE);
        rig.ticks(FULL_DRAW);
        rig.controls().releaseMouse(USE);
        rig.ticks(2);
        boolean tipped = rig.world().attempt(
            "execute if entity @e[type=arrow,nbt={item:{id:\"minecraft:tipped_arrow\"}}]");
        rig.controls().look(180, 0);
        return tipped;
    }

    private void potionArrows(Rig rig) {
        Props.hold(rig, 0, "bow");
        Props.pack(rig, 0, STRENGTH_ARROWS);
        rig.client().set("PotionArrows", "Only in holes", "off");
        rig.client().enable("PotionArrows");
        Props.await(rig, "the strength of the arrow", KILL_TICKS * 2, () -> strong(rig));
    }

    // An arrow does nothing to a creative player. The first one is spent on one and brings no
    // effect. The module has to see that and shoot again and by then the player can be hit.
    private void potionArrowsAgain(Rig rig) {
        World world = rig.world();
        Props.hold(rig, 0, "bow");
        Props.pack(rig, 0, STRENGTH_ARROWS);
        rig.client().set("PotionArrows", "Only in holes", "off");
        world.gameMode("creative");
        rig.client().enable("PotionArrows");
        Props.await(rig, "the first arrow to fly", KILL_TICKS, () -> Props.living(rig, "arrow") > 0);
        rig.ticks(SEEN_TICKS);
        boolean spent = !strong(rig);
        world.gameMode("survival");
        Props.await(rig, "the strength of a second arrow", KILL_TICKS * 2, () -> strong(rig));
        rig.expect(spent, "The first arrow gave a creative player its effect. That proves nothing");
    }

    private static boolean strong(Rig rig) {
        World world = rig.world();
        return world.attempt("execute if entity @a[name=" + world.player()
            + ",nbt={active_effects:[{id:\"minecraft:strength\"}]}]");
    }

    private void antiAnchor(Rig rig) {
        BlockPos anchor = home.north();
        rig.world().setBlock(anchor, "respawn_anchor[charges=1]");
        Props.hold(rig, 0, "netherite_pickaxe[enchantments={\"minecraft:efficiency\":5}]");
        rig.client().enable("AntiAnchor");
        Props.await(rig, "the anchor to be broken", KILL_TICKS * 2, () -> !Props.is(rig, anchor, "respawn_anchor"));
    }

    // Paper starts the charge of a hit again the moment the held item changes. A swap there would
    // land a fifth of the blow.
    private void attributeSwapNeverWeaker(Rig rig) {
        int plain = hitHolding(rig, SWORD, SHARP_SWORD);
        rig.client().enable("AttributeSwap");
        int swapped = hitHolding(rig, SWORD, SHARP_SWORD);
        String slot = rig.world().facts().get("slot");
        rig.note("One hit took " + plain + " health off the husk and " + swapped + " with AttributeSwap");
        rig.expect(plain >= BLADE_HIT, "The plain sword did not hit. That proves nothing");
        rig.expect(swapped >= plain, "AttributeSwap made the hit weaker");
        rig.expect("0".equals(slot), "The server has the player on slot " + slot + " after the hit and the first "
            + "sword lies in the first");
    }

    // A plain server strikes with the damage of the sword in hand and the sharpness of the other.
    private void attributeSwapGains(Rig rig) {
        int plain = hitHolding(rig, SWORD, SHARP_SWORD);
        rig.client().enable("AttributeSwap");
        int swapped = hitHolding(rig, SWORD, SHARP_SWORD);
        rig.note("One hit took " + plain + " health off the husk and " + swapped + " with AttributeSwap");
        rig.expect(swapped >= plain + SHARPER, "The sharpness of the other sword did not come with the hit");
    }

    private void attributeSwapFire(Rig rig) {
        rig.client().enable("AttributeSwap");
        hitHolding(rig, SWORD, SWORD + "[enchantments={\"minecraft:fire_aspect\":2}]");
        int burning = rig.world().count("data get entity @e[type=husk,limit=1] Fire");
        rig.note("The husk has " + burning + " ticks of fire left");
        rig.expect(burning > 0, "The fire of the other sword did not come with the hit");
    }

    private void attributeSwapSpares(Rig rig) {
        rig.client().set("AttributeSwap", "Swap against", "players");
        rig.client().enable("AttributeSwap");
        int dealt = hitHolding(rig, "stick", SHARP_SWORD);
        rig.note("One hit took " + dealt + " health off the husk");
        rig.expect(dealt <= STICK_HIT, "The sword came out against a mob with the swap set to players alone");
    }

    // The health one click takes off a fresh husk with one item in hand and another further along
    // the hotbar.
    private int hitHolding(Rig rig, String held, String other) {
        freshHusk(rig, home.north(2));
        Props.hold(rig, 0, held);
        rig.world().hotbar(SWORD_SLOT, other);
        // Long enough for the item in hand to charge.
        rig.ticks(SWING_GAP);
        rig.controls().click(ATTACK);
        rig.ticks(SWING_GAP);
        return HUSK_HEALTH - Props.health(rig, HUSK);
    }

    private void maceCombo(Rig rig) {
        Props.hold(rig, 0, "mace");
        rig.world().hotbar(1, "wind_charge 16");
        Props.summon(rig, HUSK, home.north(2), TOUGH_STILL);
        rig.client().set("MaceCombo", "Mobs", "all");
        rig.ticks(SEEN_TICKS);
        rig.client().enable("MaceCombo");
        double peak = 0;
        for (int tick = 0; tick < COMBO_TICKS; tick++) {
            rig.ticks(1);
            peak = Math.max(peak, rig.ask(mc -> mc.player.getY()) - home.getY());
        }
        rig.client().disable("MaceCombo");
        int dealt = TOUGH_HEALTH - Props.health(rig, HUSK);
        rig.note("The player rose " + Math.round(peak) + " blocks and the husk lost " + dealt + " health in "
            + COMBO_TICKS + " ticks");
        rig.expect(peak >= LAUNCHED, "No wind charge threw the player up");
        rig.expect(dealt >= SMASHED, "No smash landed on the husk");
    }

    // The anvil needs a wall to stand against two blocks up.
    private void selfAnvil(Rig rig) {
        rig.world().fill(home.north(), home.north().above(2), OBSIDIAN);
        Props.hold(rig, 0, "anvil 4");
        rig.client().enable("SelfAnvil");
        Props.await(rig, "an anvil in the player's own block", BUILD_TICKS, () -> rig.world().blockAt(home)
            .endsWith("anvil"));
        rig.expect(!rig.client().module("SelfAnvil").isEnabled(), "SelfAnvil stayed on after its anvil");
    }

    private void chorusHold(Rig rig) {
        Vec3 ate = eatAndHold(rig);
        Props.hold(rig, 1);
        land(rig, ate, SEEN_TICKS * 2);
    }

    private void chorusHoldLimit(Rig rig) {
        Vec3 ate = eatAndHold(rig);
        int took = land(rig, ate, HELD_LONGEST);
        rig.note("The teleport went by itself after " + took + " more ticks with the fruit still in hand");
    }

    // Eats one fruit and hands back where that was. The teleport is kept and both the game and
    // the server still have the player there.
    private Vec3 eatAndHold(Rig rig) {
        Props.hold(rig, 0, "chorus_fruit 4");
        rig.client().enable("ChorusHold");
        Vec3 ate = rig.ask(mc -> mc.player.position());
        rig.controls().holdMouse(USE);
        try {
            rig.until("the fruit to be eaten and its teleport kept", EAT_TICKS,
                mc -> HOLDING.equals(rig.client().module("ChorusHold").getSuffix()));
        } finally {
            rig.controls().releaseMouse(USE);
        }
        rig.ticks(SEEN_TICKS);
        Vec3 stands = rig.ask(mc -> mc.player.position());
        Vec3 held = rig.world().where();
        rig.expect(stands.distanceTo(ate) < MOVED, "The game moved the player whilst the teleport was kept");
        rig.expect(held.distanceTo(ate) < MOVED, "The server moved the player before the game took the teleport");
        return ate;
    }

    // Waits for the kept teleport to land and makes sure the server has the player in the same
    // place. Hands back the ticks it took.
    private int land(Rig rig, Vec3 ate, int limitTicks) {
        int took = rig.until("the kept teleport to land", limitTicks,
            mc -> mc.player.position().distanceTo(ate) > MOVED);
        rig.ticks(SEEN_TICKS);
        Vec3 stands = rig.ask(mc -> mc.player.position());
        double apart = rig.world().where().distanceTo(stands);
        rig.note("The fruit put the player " + Math.round(stands.distanceTo(ate)) + " blocks from where it was eaten");
        rig.expect(apart < MOVED, "The server has the player " + Math.round(apart) + " blocks from where the game "
            + "has them");
        return took;
    }

    private void evadeCreeper(Rig rig) {
        rig.client().enable("Evade");
        Blast blast = blast(rig, home.north(2), "creeper", LIT_CREEPER, FUSE);
        rig.note("The player stood " + Math.round(blast.clear()) + " blocks off when the creeper blew and lost "
            + blast.lost() + " health");
        expectClear(rig, blast, CREEPER_REACH);
    }

    private void evadeTnt(Rig rig) {
        rig.client().enable("Evade");
        double stayed = besideTnt(rig);
        rig.client().set("Evade", "Lit TNT", ON);
        Blast blast = blast(rig, home.north(2), "tnt", "{fuse:" + TNT_FUSE + "}", TNT_FUSE);
        rig.note("The player stayed within " + Math.round(stayed) + " blocks of the TNT with Lit TNT off and stood "
            + Math.round(blast.clear()) + " off when it blew with Lit TNT on and lost " + blast.lost() + " health");
        rig.expect(stayed < HOPPED, "Evade hopped away from TNT with its setting off");
        expectClear(rig, blast, TNT_REACH);
    }

    // The pit is a trench three long and as deep as the stage goes. The player stands at one end
    // of its floor and the creeper at the other. Every spot a hop away at the height of the
    // floor lies in the ground and the one way clear leads up and out.
    private void evadePit(Rig rig) {
        World world = rig.world();
        world.fill(home.below(PIT_DEPTH), home.north(2).below(), "air");
        world.teleport(home.getX() + 0.5, home.getY() - PIT_DEPTH, home.getZ() + 0.5, 180, 0);
        rig.client().enable("Evade");
        Blast blast = blast(rig, home.north(2).below(PIT_DEPTH), "creeper", LIT_CREEPER, FUSE);
        rig.note("The player stood " + Math.round(blast.clear()) + " blocks off when the creeper blew and lost "
            + blast.lost() + " health");
        expectClear(rig, blast, CREEPER_REACH);
    }

    // Stands lit TNT two blocks north and takes it away a moment before it would blow. A blast
    // that close kills the player and digs up the ground the next TNT is to stand on. Hands back
    // the furthest the player strayed from it.
    private double besideTnt(Rig rig) {
        BlockPos spot = home.north(2);
        Vec3 danger = Vec3.atBottomCenterOf(spot);
        Props.summon(rig, "tnt", spot, "{fuse:" + TNT_FUSE + "}");
        double strayed = 0;
        for (int tick = 0; tick < TNT_FUSE - TNT_SPARE; tick++) {
            rig.ticks(1);
            strayed = Math.max(strayed, rig.ask(mc -> mc.player.position().distanceTo(danger)));
        }
        rig.world().command("kill @e[type=tnt]");
        rig.ticks(SEEN_TICKS);
        rig.expect(Props.living(rig, "tnt") == 0, "The TNT is still there");
        return strayed;
    }

    // How far the player stood from a blast the last tick the game still saw what blew and the
    // health the blast cost.
    private record Blast(double clear, float lost) {
    }

    // Stands something that blows up on the spot. The server says when it is gone. The game
    // loses sight of it sooner when the player dies.
    private Blast blast(Rig rig, BlockPos spot, String type, String tags, int fuse) {
        Vec3 danger = Vec3.atBottomCenterOf(spot);
        float before = rig.world().health();
        Props.summon(rig, type, spot, tags);
        String id = "minecraft:" + type;
        double clear = 0;
        for (int tick = 0; tick < fuse + SEEN_TICKS * 2; tick++) {
            rig.ticks(1);
            double seen = rig.ask(mc -> {
                for (Entity entity : mc.level.entitiesForRendering()) {
                    if (EntityType.getKey(entity.getType()).toString().equals(id)) {
                        return mc.player.position().distanceTo(danger);
                    }
                }
                return -1.0;
            });
            if (seen >= 0) {
                clear = seen;
            } else if (tick > SEEN_TICKS && Props.living(rig, type) == 0) {
                Stage.expectAlive(rig);
                return new Blast(clear, before - rig.world().health());
            }
        }
        throw new RigFailure("The " + type + " never blew");
    }

    private static void expectClear(Rig rig, Blast blast, double reach) {
        rig.expect(blast.clear() >= reach, "Evade left the player " + Math.round(blast.clear())
            + " blocks from a blast that reaches " + Math.round(reach));
        rig.expect(blast.lost() <= 0, "The blast took " + blast.lost() + " health off the player");
    }

    private void protect(Rig rig) {
        Props.hold(rig, 0, SWORD);
        Props.summon(rig, "cow", home.east(2), "{NoAI:1b,PersistenceRequired:1b}");
        rig.client().set("Protect", "Guard", "nearest_mob");
        rig.ticks(SEEN_TICKS);
        rig.client().enable("Protect");
        rig.ticks(SEEN_TICKS);
        String guarding = String.valueOf(rig.client().module("Protect").getSuffix());
        Props.summon(rig, HUSK, home.north(3), GROWN_STILL);
        // Where everybody stood when the cow was first seen hurt. It goes with a failure.
        String[] hurt = new String[1];
        int took = Props.await(rig, "the husk to die", KILL_TICKS, () -> {
            if (hurt[0] == null && Props.living(rig, "cow") == 1 && Props.health(rig, "cow") < COW_HEALTH) {
                hurt[0] = rig.ask(mc -> {
                    StringBuilder stood = new StringBuilder("the player at " + mc.player.position());
                    for (Entity entity : mc.level.entitiesForRendering()) {
                        if (entity instanceof LivingEntity living && entity != mc.player) {
                            stood.append(" and ").append(entity.getName().getString()).append(" at ")
                                .append(entity.position()).append(" with ").append(living.getHealth()).append(" health");
                        }
                    }
                    return stood.toString();
                }) + hurtBy(rig);
            }
            return Props.living(rig, HUSK) == 0;
        });
        rig.note("Protect says " + guarding + " and the husk was dead after about " + took + " ticks");
        rig.expect(guarding.contains("Cow"), "Protect does not say it guards the cow");
        int cows = Props.living(rig, "cow");
        int health = cows == 0 ? 0 : Props.health(rig, "cow");
        rig.expect(cows == 1 && health == COW_HEALTH, "The cow Protect guards has " + health + " of " + COW_HEALTH
            + " health. It was first seen hurt with " + hurt[0]);
    }

    // What the single player server holds as the last thing that hurt the cow. A real server
    // keeps that to itself.
    private static String hurtBy(Rig rig) {
        if (rig.ask(mc -> !mc.hasSingleplayerServer())) {
            return "";
        }
        return rig.local().server(server -> {
            for (Entity entity : server.overworld().getAllEntities()) {
                if (entity instanceof Cow cow && cow.getLastDamageSource() != null) {
                    DamageSource source = cow.getLastDamageSource();
                    return ". The server says the cow was hurt by " + source.type().msgId() + " straight from "
                        + named(source.getDirectEntity()) + " and in the end from " + named(source.getEntity());
                }
            }
            return ". The server names nothing that hurt the cow";
        });
    }

    private static String named(Entity entity) {
        return entity == null ? "nothing" : entity.getName().getString();
    }

    // The husk cannot be hurt and the fight goes on for as long as the case looks.
    private void confuse(Rig rig) {
        BlockPos spot = home.north(3);
        Vec3 centre = Vec3.atBottomCenterOf(spot);
        Props.hold(rig, 0, "stick");
        Props.summon(rig, HUSK, spot, "{NoAI:1b,PersistenceRequired:1b,IsBaby:0b,Invulnerable:1b}");
        rig.client().set("KillAura", "Hostile mobs", ON);
        rig.client().enable("KillAura");
        rig.client().enable("Confuse");
        double swept = 0;
        double worst = 0;
        double last = bearing(rig, centre);
        for (int tick = 0; tick < LAP_TICKS; tick++) {
            rig.ticks(1);
            double now = bearing(rig, centre);
            swept += Mth.wrapDegrees(now - last);
            last = now;
            if (tick > LAP_TICKS / 2) {
                double off = Math.abs(rig.ask(mc -> mc.player.position().subtract(centre).horizontalDistance()) - RING);
                worst = Math.max(worst, off);
            }
        }
        rig.note("The player went " + Math.round(Math.abs(swept)) + " degrees round the husk in " + LAP_TICKS
            + " ticks and strayed " + String.format(java.util.Locale.ROOT, "%.2f", worst) + " blocks off the ring");
        rig.expect(Math.abs(swept) >= HALF_LAP, "Confuse did not run the player round the husk");
        rig.expect(worst <= OFF_RING, "Confuse did not keep its distance from the husk");
    }

    // The way from a spot to the player in degrees.
    private static double bearing(Rig rig, Vec3 from) {
        return rig.ask(mc -> Math.toDegrees(Math.atan2(mc.player.getZ() - from.z, mc.player.getX() - from.x)));
    }

    private void skyDrop(Rig rig) {
        int plain = huskAfterArrow(rig);
        rig.client().set("SkyDrop", "Mobs", "all");
        rig.client().enable("SkyDrop");
        int dropped = huskAfterArrow(rig);
        Vec3 held = rig.world().where();
        double strayed = held.distanceTo(new Vec3(home.getX() + 0.5, home.getY(), home.getZ() + 0.5));
        rig.note("One arrow left the husk " + dropped + " health with SkyDrop and " + plain + " without");
        rig.expect(plain > 0 && plain < HUSK_HEALTH, "A plain arrow did not hurt the husk and leave it alive. That "
            + "proves nothing");
        rig.expect(strayed < 1, "The server holds the player " + Math.round(strayed) + " blocks from where they shot");
        rig.expect(dropped < plain - 5, "The arrow hit no harder");
    }

    private void antiBed(Rig rig) {
        NetherRoom.enter(rig);
        try {
            BlockPos foot = NetherRoom.FEET.east();
            NetherRoom.set(rig, foot, BED + "[part=foot,facing=east]");
            NetherRoom.set(rig, foot.east(), BED + "[part=head,facing=east]");
            rig.ticks(SEEN_TICKS);
            rig.expect(NetherRoom.is(rig, foot, BED), "No bed stood beside the player. That proves nothing");
            rig.client().enable("AntiBed");
            Props.await(rig, "the bed beside the player to be broken", BUILD_TICKS,
                () -> !NetherRoom.is(rig, foot, BED));
        } finally {
            rig.client().disable("AntiBed");
            NetherRoom.leave(rig, home);
        }
    }

    // A bed in the overworld is for sleeping in.
    private void antiBedOverworld(Rig rig) {
        BlockPos foot = home.east();
        rig.world().setBlock(foot, BED + "[part=foot,facing=east]");
        rig.world().setBlock(foot.east(), BED + "[part=head,facing=east]");
        rig.ticks(SEEN_TICKS);
        rig.client().enable("AntiBed");
        rig.ticks(BUILD_TICKS);
        rig.expect(Props.is(rig, foot, BED), "AntiBed broke a bed that cannot explode");
    }

    private void cubeRiggerLoads(Rig rig) {
        Props.summon(rig, CUBE, home.north(2), "{NoAI:1b,PersistenceRequired:1b,Size:2}");
        Props.hold(rig, 0, "tnt 4");
        rig.client().set("CubeRigger", "Load TNT", ON);
        rig.ticks(SEEN_TICKS);
        rig.client().enable("CubeRigger");
        Props.await(rig, "TNT inside the cube", BUILD_TICKS, () -> cubeHolds(rig, "tnt"));
    }

    // Shears take the TNT out and the fill block goes in and the lighter primes the cube. The
    // cube ends up primed with something other than TNT inside.
    private void cubeRiggerRigs(Rig rig) {
        BlockPos spot = home.north(2);
        Props.summon(rig, CUBE, spot, "{NoAI:1b,PersistenceRequired:1b,Size:2,equipment:{body:{id:\"minecraft:tnt\","
            + "count:1}}}");
        Props.hold(rig, 0, "flint_and_steel");
        rig.world().hotbar(1, "shears");
        rig.world().hotbar(2, "magma_block 4");
        rig.ticks(SEEN_TICKS);
        rig.client().enable("CubeRigger");
        rig.controls().aimAt(Vec3.atBottomCenterOf(spot).add(0, 0.5, 0));
        rig.ticks(2);
        rig.controls().click(USE);
        rig.until("the cube to count as rigged", BUILD_TICKS,
            mc -> Props.tally(rig.client().module("CubeRigger")) >= 1);
        rig.expect(cubeHolds(rig, "magma_block"), "The server does not have the fill block inside the cube");
    }

    private static boolean cubeHolds(Rig rig, String block) {
        return rig.world().attempt("execute if entity @e[type=" + CUBE + ",nbt={equipment:{body:{id:\"minecraft:"
            + block + "\"}}}]");
    }

    // Blast proof blocks under the feet and on all four sides.
    private void hole(Rig rig) {
        rig.world().setBlock(home.below(), OBSIDIAN);
        for (BlockPos side : List.of(home.north(), home.east(), home.south(), home.west())) {
            rig.world().setBlock(side, OBSIDIAN);
        }
    }

    // The piston stands two blocks north at head height and looks at the player.
    private void blockerPiston(Rig rig) {
        hole(rig);
        Props.hold(rig, 0, OBSIDIAN + " 16");
        rig.world().setBlock(home.north(2).above(), "piston[facing=south]");
        rig.client().set("Blocker", "Crystal spots", "off");
        rig.client().enable("Blocker");
        Props.await(rig, "a block in front of the piston", BUILD_TICKS,
            () -> Props.is(rig, home.north().above(), OBSIDIAN));
    }

    // Enemy range at nought fills the spots with nobody about.
    private void blockerCrystals(Rig rig) {
        hole(rig);
        Props.hold(rig, 0, OBSIDIAN + " 16");
        rig.client().set("Blocker", "Enemy range", "0");
        rig.client().enable("Blocker");
        List<BlockPos> spots = List.of(home.north().above(), home.east().above(), home.south().above(),
            home.west().above());
        Props.await(rig, "the four spots on top of the walls", BUILD_TICKS * 2,
            () -> spots.stream().allMatch(pos -> Props.is(rig, pos, OBSIDIAN)));
    }
}
