package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// One case for each combat module that a mob or the player's own gear can prove. The server
// says whether the mob died and what the player wears and which blocks stand. What needs another
// player to fight is in the Server suite.
public final class CombatSuite implements Suite {

    private static final String NAME = "Combat";
    private static final String SLINGSHOT = "Slingshot makes an arrow hit much harder";
    private static final String SLINGSHOT_FAULT = "The long hop ends where the server holds no chunk. The server "
        + "then refuses the way home and leaves the player 189 blocks from where they stood";
    private static final int ATTACK = InputConstants.MOUSE_BUTTON_LEFT;
    private static final int USE = InputConstants.MOUSE_BUTTON_RIGHT;

    private static final String SWORD = "diamond_sword";
    private static final String HUSK = "husk";
    private static final String TOTEM = "totem_of_undying";
    private static final String OBSIDIAN = "obsidian";
    private static final String OFFHAND = "weapon.offhand";
    private static final String ON = "on";
    // A husk can come as a child and some modules leave children alone.
    private static final String GROWN_STILL = "{NoAI:1b,PersistenceRequired:1b,IsBaby:0b}";
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
    private static final int ARROWS = 64;
    private static final int SWINGS = 4;
    // A sword is ready again after this many ticks.
    private static final int SWING_GAP = 14;
    private static final float FULL_HEALTH = 20;
    private static final int ANVIL_HEIGHT = 12;
    private static final int ARROW_FROM = 9;
    private static final int FLIGHT_TICKS = 30;
    private static final int FULL_DRAW = 25;

    private BlockPos home;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        Grounds.play(rig, NAME, plays(), stage -> home = stage.home(), Set.of(),
            Map.of(SLINGSHOT, SLINGSHOT_FAULT));
    }

    private Map<String, Case.Body> plays() {
        Map<String, Case.Body> plays = new LinkedHashMap<>();
        plays.put("KillAura kills a mob in reach", this::killAura);
        plays.put("TriggerBot kills the mob under the crosshair", this::triggerBot);
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
        plays.put("AntiAnchor breaks the anchor set beside you", this::antiAnchor);
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

    // The health left to a husk after one arrow from a full draw. Nought for a dead one.
    private int huskAfterArrow(Rig rig) {
        rig.world().attempt("execute as @e[type=husk] at @s run tp @s ~ ~-300 ~");
        rig.world().attempt("kill @e[type=husk]");
        rig.world().attempt("kill @e[type=arrow]");
        Props.summon(rig, HUSK, home.south(12), GROWN_STILL);
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
        String player = rig.world().player();
        Props.hold(rig, 0, "bow");
        Props.pack(rig, 0, "tipped_arrow[potion_contents={potion:\"minecraft:strength\"}] 8");
        rig.client().set("PotionArrows", "Only in holes", "off");
        rig.client().enable("PotionArrows");
        Props.await(rig, "the strength of the arrow", KILL_TICKS * 2, () -> rig.world().attempt(
            "execute if entity @a[name=" + player + ",nbt={active_effects:[{id:\"minecraft:strength\"}]}]"));
    }

    private void antiAnchor(Rig rig) {
        BlockPos anchor = home.north();
        rig.world().setBlock(anchor, "respawn_anchor[charges=1]");
        Props.hold(rig, 0, "netherite_pickaxe[enchantments={\"minecraft:efficiency\":5}]");
        rig.client().enable("AntiAnchor");
        Props.await(rig, "the anchor to be broken", KILL_TICKS * 2, () -> !Props.is(rig, anchor, "respawn_anchor"));
    }
}
