package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.rig.suite.Motion.Moved;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final int ATTACK = InputConstants.MOUSE_BUTTON_LEFT;
    private static final int SPRINT = InputConstants.KEY_LCONTROL;
    private static final int JUMP = InputConstants.KEY_SPACE;
    private static final int RUN_TICKS = 120;
    private static final int MINE_TICKS = 50;
    private static final int SNOWBALLS = 16;
    // Where the first hotbar slot lies in the inventory screen measured from its corner.
    private static final int SLOT_X = 8 + 8;
    private static final int SLOT_Y = 142 + 8;
    private static final int INVENTORY_WIDTH = 176;
    private static final int INVENTORY_HEIGHT = 166;

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
        plays.put("AutoDrop keeps what is on its keep list and throws the rest", this::autoDropKeep);
        plays.put("AntiBlind keeps Speed from widening the view", this::effectZoom);
        plays.put("ChestSwap swaps the chestplate for the elytra", this::chestSwap);
        plays.put("AutoSleep goes to bed at night", this::autoSleep);
        plays.put("AutoDoors opens the door in your way", this::autoDoors);
        plays.put("AntiCactus keeps a cactus from hurting you", this::antiCactus);
        plays.put("NoRotate keeps your view when the server turns your head", this::noRotate);
        plays.put("FastUse throws bottles faster", this::fastUse);
        plays.put("AutoPotion throws a healing potion once you are hurt", this::autoPotion);
        plays.put("AutoMend throws bottles at worn mending gear", this::autoMend);
        plays.put("AntiHunger makes a run cost less food", this::antiHunger);
        plays.put("Multitask mines whilst you eat", this::multitask);
        plays.put("NoMiningTrace mines past a mob in the way", this::noMiningTrace);
        plays.put("GhostHand opens a chest through a wall", this::ghostHand);
        plays.put("Throw throws a whole stack on one click", this::throwAll);
        plays.put("AutoSwitch goes through the hotbar by itself", this::autoSwitch);
        plays.put("Rotation turns the view the server holds to its angle", this::rotation);
        plays.put("NoInteract keeps your hand off a charged respawn anchor", this::noInteract);
        plays.put("Loadouts puts the hotbar back the way it was saved", this::loadouts);
        plays.put("Ghost lets you walk on whilst the server holds you dead", this::ghost);
        plays.put("FastPlace places blocks with no wait between them", this::fastPlace);
        plays.put("LiquidInteract rests the crosshair on water", this::liquidInteract);
        plays.put("MenuTools puts its buttons above a chest's menu", this::menuTools);
        plays.put("LootList lists the enchanted gear in a chest", this::lootList);
        plays.put("PortalMenus keeps the inventory open in a portal", this::portalMenus);
        plays.put("InventoryTweaks pushes split stacks together", this::inventoryTweaks);
        plays.put("InvWalk walks you whilst the inventory is open", this::invWalk);
        plays.put("GUIMove turns the view with the arrow keys whilst the inventory is open", this::guiMove);
        plays.put("FreeLook turns your body with the arrow keys and leaves the camera be", this::freeLook);
        plays.put("FrameInput still takes your keys and your mining", this::frameInput);
        plays.put("MenuClicker plays its step as a chest opens", this::menuClicker);
        plays.put("ShulkerPacker packs the backpack into a shulker box", this::shulkerPacker);
        plays.put("ChestLink keeps the ender chest open behind a closed screen", this::chestLink);
        plays.put("ChestMemory finds the chest that holds an item", this::chestMemory);
        plays.put("BetterTooltips adds the hunger a food stills to its tooltip", this::betterTooltips);
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

    // An empty keep list would throw the whole backpack out. It throws nothing instead.
    private void autoDropKeep(Rig rig) {
        Props.pack(rig, 3, "diamond 3");
        Props.pack(rig, 4, "oak_planks 10");
        Props.pack(rig, 5, "bread 5");
        rig.client().set("AutoDrop", "Mode", "keep");
        rig.client().enable("AutoDrop");
        rig.ticks(SEEN_TICKS * 3);
        int kept = Props.carried(rig, "oak_planks") + Props.carried(rig, "bread");
        Props.pick(rig, "AutoDrop", "Keep", "minecraft:diamond");
        Props.await(rig, "all but the diamonds to be thrown out", ACT_TICKS,
            () -> Props.carried(rig, "oak_planks") + Props.carried(rig, "bread") == 0);
        rig.expect(kept == 15, "An empty keep list threw things out");
        rig.expect(Props.carried(rig, "diamond") == 3, "The diamonds on the keep list were thrown out");
    }

    private void effectZoom(Rig rig) {
        World world = rig.world();
        double plain = viewWidening(rig);
        world.command("effect give " + world.player() + " speed 60 4 true");
        rig.ticks(SEEN_TICKS);
        double fast = viewWidening(rig);
        rig.client().set("AntiBlind", "Effect zoom", "on");
        rig.client().enable("AntiBlind");
        rig.ticks(2);
        double held = viewWidening(rig);
        rig.note("The view widens by " + plain + " at rest and " + fast + " under Speed and " + held
            + " with AntiBlind on");
        rig.expect(fast > plain + 0.1, "Speed did not widen the view. That proves nothing");
        rig.expect(Math.abs(held - plain) < 0.01, "The view still widened under Speed");
    }

    // The factor the game widens the field of view by for the pace of the player.
    private static double viewWidening(Rig rig) {
        return rig.ask(mc -> (double) mc.player.getFieldOfViewModifier(true, 1));
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

    private void autoPotion(Rig rig) {
        World world = rig.world();
        Props.hold(rig, 0, "splash_potion[potion_contents={potion:\"minecraft:strong_healing\"}]");
        world.hotbar(1, "splash_potion[potion_contents={potion:\"minecraft:strong_healing\"}]");
        rig.client().enable("AutoPotion");
        world.command("damage " + world.player() + " 9");
        int took = Props.await(rig, "a potion to be thrown", ACT_TICKS, () -> Props.carried(rig, "splash_potion") < 2);
        rig.note("A potion left the hotbar about " + took + " ticks after the hurt");
    }

    private void autoMend(Rig rig) {
        Props.wear(rig, CHEST, "diamond_chestplate[damage=300,enchantments={\"minecraft:mending\":1}]");
        Props.hold(rig, 0, "experience_bottle " + BOTTLES);
        rig.client().enable("AutoMend");
        int took = Props.await(rig, "bottles to be thrown", ACT_TICKS * 2,
            () -> Props.carried(rig, "experience_bottle") < BOTTLES);
        rig.note("The first bottle was thrown after about " + took + " ticks");
    }

    // A run with jumps for six seconds. What it costs is read off the server in hundredths.
    private void antiHunger(Rig rig) {
        int plain = runCost(rig);
        rig.world().teleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, NORTH, 0);
        rig.client().enable("AntiHunger");
        int spared = runCost(rig);
        rig.note("The run cost " + spared + " hundredths of hunger against " + plain);
        rig.expect(plain >= 30, "The run cost no hunger without AntiHunger. That proves nothing");
        rig.expect(spared <= plain * 0.7, "AntiHunger spared no food");
    }

    private int runCost(Rig rig) {
        World world = rig.world();
        String player = world.player();
        world.command("effect give " + player + " saturation 1 20 true");
        rig.ticks(SEEN_TICKS);
        int before = spent(world, player);
        Motion.hold(rig, RUN_TICKS, FORWARD, SPRINT, JUMP);
        return spent(world, player) - before;
    }

    // Hunger spent in hundredths of a point. Saturation goes first and what is not yet taken off it waits.
    private static int spent(World world, String player) {
        int saturation = world.count("data get entity " + player + " foodSaturationLevel 100");
        int waiting = world.count("data get entity " + player + " foodExhaustionLevel 25");
        return waiting - saturation;
    }

    // The game lets nobody mine with food at the mouth.
    private void multitask(Rig rig) {
        rig.expect(!minedWhilstEating(rig), "The block broke whilst eating without Multitask. That proves nothing");
        rig.client().enable("Multitask");
        rig.expect(minedWhilstEating(rig), "The block did not break whilst eating");
    }

    private boolean minedWhilstEating(Rig rig) {
        BlockPos block = home.north(2);
        rig.world().refill(block, block, "dirt");
        Props.hold(rig, 0, APPLE + " " + APPLES_GIVEN);
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAtBlock(block, Direction.SOUTH);
        rig.controls().holdMouse(USE);
        rig.ticks(2);
        rig.controls().holdMouse(ATTACK);
        rig.ticks(MINE_TICKS);
        rig.controls().releaseMouse(ATTACK);
        rig.controls().releaseMouse(USE);
        rig.ticks(SEEN_TICKS);
        return Props.is(rig, block, "air");
    }

    // A husk stands between the player and a block of stone.
    private void noMiningTrace(Rig rig) {
        World world = rig.world();
        BlockPos block = home.north(3).above();
        world.setBlock(block, "stone");
        Props.summon(rig, "husk", home.north(), "{NoAI:1b,PersistenceRequired:1b,IsBaby:0b}");
        Props.hold(rig, 0, "diamond_pickaxe");
        rig.client().enable("NoMiningTrace");
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAtBlock(block, Direction.SOUTH);
        rig.controls().holdMouse(ATTACK);
        rig.ticks(MINE_TICKS);
        rig.controls().releaseMouse(ATTACK);
        rig.ticks(SEEN_TICKS);
        rig.expect(Props.is(rig, block, "air"), "The block behind the husk did not break");
        rig.expect(Props.health(rig, "husk") == FULL_HEALTH, "The husk in the way was hit");
    }

    private void ghostHand(Rig rig) {
        World world = rig.world();
        BlockPos wall = home.north(2);
        world.fill(wall.offset(-1, 0, 0), wall.offset(1, 1, 0), "stone");
        world.setBlock(wall.north(), "chest[facing=south]");
        Props.hold(rig, 0);
        rig.ticks(SEEN_TICKS);
        // The crosshair points at the middle of the chest and the wall is what it rests on.
        rig.controls().aimAt(Vec3.atCenterOf(wall.north()));
        rig.ticks(2);
        rig.controls().click(USE);
        rig.ticks(SEEN_TICKS);
        rig.expect(rig.ask(mc -> !(mc.gui.screen() instanceof AbstractContainerScreen)),
            "The chest opened through the wall without GhostHand. That proves nothing");
        rig.client().enable("GhostHand");
        rig.controls().click(USE);
        rig.ticks(SEEN_TICKS);
        boolean opened = rig.ask(mc -> mc.gui.screen() instanceof AbstractContainerScreen);
        rig.act(mc -> {
            if (mc.gui.screen() != null) {
                mc.player.closeContainer();
            }
        });
        rig.expect(opened, "The chest behind the wall did not open");
    }

    private void throwAll(Rig rig) {
        Props.hold(rig, 0, "snowball " + SNOWBALLS);
        rig.client().enable("Throw");
        rig.controls().look(NORTH, -30);
        rig.ticks(SEEN_TICKS);
        // The button is down for the one tick the game takes the click in.
        rig.controls().holdMouse(USE);
        rig.ticks(2);
        rig.controls().releaseMouse(USE);
        rig.ticks(SEEN_TICKS);
        int thrown = SNOWBALLS - Props.carried(rig, "snowball");
        rig.note(thrown + " snowballs went out on one click");
        rig.expect(thrown >= 5, "One click threw " + thrown + " snowballs");
    }

    private void autoSwitch(Rig rig) {
        rig.client().enable("AutoSwitch");
        Set<String> slots = new HashSet<>();
        for (int look = 0; look < SEEN_TICKS; look++) {
            rig.ticks(2);
            slots.add(rig.world().facts().get("slot"));
        }
        rig.note("The server saw the slots " + slots);
        rig.expect(slots.size() >= 3, "The hotbar slot hardly changed");
    }

    private void rotation(Rig rig) {
        rig.client().enable("Rotation");
        rig.ticks(SEEN_TICKS);
        float yaw = Float.parseFloat(rig.world().facts().get("yaw"));
        rig.note("The server has the player facing " + yaw + " degrees");
        rig.expect(Math.abs(Mth.wrapDegrees(yaw)) < TURN_NEAR, "The view did not go to the angle of the module");
    }

    // A charged respawn anchor blows up when it is clicked outside the Nether. In creative the
    // blast that does go off hurts nobody.
    private void noInteract(Rig rig) {
        World world = rig.world();
        BlockPos anchor = home.north(2);
        world.gameMode("creative");
        world.setBlock(anchor, "respawn_anchor[charges=1]");
        Props.hold(rig, 0);
        rig.client().enable("NoInteract");
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAtBlock(anchor, Direction.SOUTH);
        rig.controls().click(USE);
        rig.ticks(SEEN_TICKS);
        boolean kept = Props.is(rig, anchor, "respawn_anchor");
        rig.client().disable("NoInteract");
        rig.controls().click(USE);
        rig.ticks(SEEN_TICKS);
        boolean blown = !Props.is(rig, anchor, "respawn_anchor");
        world.gameMode("survival");
        rig.expect(blown, "The anchor did not blow up without NoInteract. That proves nothing");
        rig.expect(kept, "The click went through and the anchor blew up");
    }

    private void loadouts(Rig rig) {
        World world = rig.world();
        world.hotbar(0, "diamond_sword");
        world.hotbar(1, "diamond_pickaxe");
        rig.ticks(SEEN_TICKS);
        rig.client().command("loadout save rig");
        world.hotbar(0, "diamond_pickaxe");
        world.hotbar(1, "diamond_sword");
        rig.ticks(SEEN_TICKS);
        rig.client().enable("Loadouts");
        rig.client().command("loadout load rig");
        Props.await(rig, "the sword to be back in the first slot", ACT_TICKS,
            () -> Props.holds(rig, "hotbar.0", "diamond_sword") && Props.holds(rig, "hotbar.1", "diamond_pickaxe"));
    }

    private void ghost(Rig rig) {
        World world = rig.world();
        rig.client().enable("Ghost");
        world.command("kill " + world.player());
        rig.ticks(SEEN_TICKS * 2);
        boolean screened = rig.ask(mc -> mc.gui.screen() instanceof DeathScreen);
        Vec3 fell = rig.ask(mc -> mc.player.position());
        rig.controls().hold(FORWARD);
        rig.ticks(WALK_TICKS);
        rig.controls().release(FORWARD);
        double walked = rig.ask(mc -> mc.player.position().distanceTo(fell));
        float health = world.health();
        rig.controls().press(InputConstants.KEY_R);
        boolean rose = true;
        try {
            rig.until("the respawn key to bring the player back", ACT_TICKS, mc -> mc.player != null
                && mc.player.isAlive());
        } catch (RuntimeException stillDead) {
            rose = false;
            rig.client().disable("Ghost");
            rig.act(mc -> mc.player.respawn());
            rig.until("the player to live again", ACT_TICKS, mc -> mc.player != null && mc.player.isAlive());
        }
        rig.expect(!screened, "The death screen came up");
        rig.expect(health == 0, "The server has the player at " + health + " health. That proves nothing");
        rig.expect(walked > 1, "The dead player walked " + Math.round(walked) + " blocks");
        rig.expect(rose, "The respawn key did not bring the player back");
    }

    // A stack of blocks grows down from a ceiling as fast as the game lets one follow the next.
    private void fastPlace(Rig rig) {
        int plain = ticksToPlaceThree(rig);
        rig.client().enable("FastPlace");
        int fast = ticksToPlaceThree(rig);
        rig.note("Three blocks took " + plain + " ticks and " + fast + " with FastPlace");
        rig.expect(plain >= 8, "Three blocks took " + plain + " ticks without FastPlace. That proves nothing");
        rig.expect(fast * 2 <= plain, "Three blocks still took " + fast + " ticks");
    }

    private int ticksToPlaceThree(Rig rig) {
        World world = rig.world();
        world.refill(home.above(2), home.above(4), "air");
        world.refill(home.above(5), home.above(5), "stone");
        Props.hold(rig, 0, "cobblestone 64");
        rig.controls().look(NORTH, -90);
        rig.ticks(SEEN_TICKS);
        rig.controls().holdMouse(USE);
        try {
            return rig.until("three blocks under the ceiling", ACT_TICKS,
                mc -> mc.player.getInventory().getItem(0).getCount() <= 61);
        } finally {
            rig.controls().releaseMouse(USE);
            // The server takes the last block a tick after the game counted it.
            rig.ticks(SEEN_TICKS);
        }
    }

    // One block of the floor is water. The crosshair goes through it onto the block below.
    private void liquidInteract(Rig rig) {
        BlockPos pool = home.offset(0, -1, -2);
        rig.world().setBlock(pool, "water");
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAt(Vec3.atCenterOf(pool));
        rig.ticks(2);
        BlockPos plain = aimedBlock(rig);
        rig.client().enable("LiquidInteract");
        rig.ticks(2);
        BlockPos wet = aimedBlock(rig);
        rig.expect(!pool.equals(plain), "The crosshair rested on the water without LiquidInteract. That proves "
            + "nothing");
        rig.expect(pool.equals(wet), "The crosshair still goes through the water");
    }

    private static BlockPos aimedBlock(Rig rig) {
        return rig.ask(mc -> mc.hitResult instanceof BlockHitResult hit ? hit.getBlockPos() : BlockPos.ZERO);
    }

    private void menuTools(Rig rig) {
        BlockPos chest = home.north(2);
        rig.world().setBlock(chest, "chest[facing=south]");
        Props.hold(rig, 0);
        rig.ticks(SEEN_TICKS);
        boolean bare = !opensWith(rig, chest, "Desync");
        rig.client().enable("MenuTools");
        boolean shown = opensWith(rig, chest, "Desync");
        rig.expect(bare, "The chest's menu said Desync without MenuTools. That proves nothing");
        rig.expect(shown, "No Desync button stands above the chest's menu");
    }

    private void lootList(Rig rig) {
        BlockPos chest = home.north(2);
        rig.world().command("setblock " + Props.at(chest) + " chest[facing=south]{Items:[{Slot:0b,"
            + "id:\"minecraft:diamond_sword\",count:1,components:{\"minecraft:enchantments\":"
            + "{\"minecraft:sharpness\":5}}}]}");
        Props.hold(rig, 0);
        rig.ticks(SEEN_TICKS);
        boolean bare = !opensWith(rig, chest, "Diamond Sword");
        rig.client().enable("LootList");
        boolean listed = opensWith(rig, chest, "Diamond Sword");
        rig.expect(bare, "The chest's menu named the sword without LootList. That proves nothing");
        rig.expect(listed, "No list beside the chest names the enchanted sword");
    }

    // Opens the chest and says whether its screen writes the words.
    private static boolean opensWith(Rig rig, BlockPos chest, String words) {
        rig.controls().aimAt(Vec3.atCenterOf(chest));
        rig.ticks(2);
        rig.controls().click(USE);
        rig.until("the chest to open", ACT_TICKS, mc -> mc.gui.screen() instanceof AbstractContainerScreen);
        rig.ticks(SEEN_TICKS);
        boolean written = rig.frames().capture("menu with " + words).shows(words);
        rig.act(mc -> mc.player.closeContainer());
        rig.ticks(SEEN_TICKS);
        return written;
    }

    private void portalMenus(Rig rig) {
        World world = rig.world();
        world.fill(home.offset(-1, -1, -3), home.offset(2, 3, -3), "obsidian");
        world.fill(home.offset(0, 0, -3), home.offset(1, 2, -3), "nether_portal[axis=x]");
        boolean shut = !staysOpenInPortal(rig);
        rig.client().enable("PortalMenus");
        boolean kept = staysOpenInPortal(rig);
        rig.expect(shut, "The inventory stayed open in the portal without PortalMenus. That proves nothing");
        rig.expect(kept, "The portal shut the inventory");
    }

    // Steps into the portal and opens the inventory there. The stay is far shorter than the four
    // seconds a portal takes to send a player on.
    private boolean staysOpenInPortal(Rig rig) {
        World world = rig.world();
        world.teleport(home.getX() + 0.5, home.getY(), home.getZ() - 2.5, NORTH, 0);
        rig.ticks(SEEN_TICKS / 2);
        rig.act(mc -> mc.gui.setScreen(new InventoryScreen(mc.player)));
        rig.ticks(SEEN_TICKS / 2);
        boolean open = rig.ask(mc -> mc.gui.screen() instanceof InventoryScreen);
        rig.act(mc -> mc.gui.setScreen(null));
        world.teleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, NORTH, 0);
        rig.ticks(SEEN_TICKS);
        return open;
    }

    // Two part stacks of one item lie side by side in the backpack.
    private void inventoryTweaks(Rig rig) {
        Props.pack(rig, 0, "cobblestone 20");
        Props.pack(rig, 1, "cobblestone 30");
        rig.ticks(SEEN_TICKS);
        long apart = cobbleStacks(rig);
        rig.client().enable("InventoryTweaks");
        rig.act(mc -> mc.gui.setScreen(new InventoryScreen(mc.player)));
        try {
            rig.until("the two stacks to be one", ACT_TICKS, mc -> stacksOfCobble(mc.player.getInventory()) == 1);
        } finally {
            rig.act(mc -> mc.gui.setScreen(null));
        }
        rig.ticks(SEEN_TICKS);
        int held = Props.carried(rig, "cobblestone");
        rig.expect(apart == 2, "The backpack held " + apart + " stacks to begin with. That proves nothing");
        rig.expect(held == 50, "The server holds " + held + " cobblestone and fifty went in");
    }

    private static long cobbleStacks(Rig rig) {
        return rig.ask(mc -> stacksOfCobble(mc.player.getInventory()));
    }

    private static long stacksOfCobble(Inventory pack) {
        long stacks = 0;
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            if (pack.getItem(slot).is(Items.COBBLESTONE)) {
                stacks++;
            }
        }
        return stacks;
    }

    private void invWalk(Rig rig) {
        double shut = walkedBehindInventory(rig);
        rig.world().teleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, NORTH, 0);
        rig.client().enable("InvWalk");
        double open = walkedBehindInventory(rig);
        rig.note("The forward key moved the player " + Math.round(shut) + " blocks behind the inventory and "
            + Math.round(open) + " with InvWalk");
        rig.expect(shut < 0.5, "The player walked behind the inventory without InvWalk. That proves nothing");
        rig.expect(open > 2, "The forward key did nothing behind the inventory");
    }

    // How far the server has the player gone after the forward key was held behind the inventory.
    private static double walkedBehindInventory(Rig rig) {
        rig.act(mc -> mc.gui.setScreen(new InventoryScreen(mc.player)));
        rig.ticks(2);
        Vec3 from = rig.world().where();
        rig.controls().hold(FORWARD);
        rig.ticks(WALK_TICKS);
        rig.controls().release(FORWARD);
        rig.ticks(SEEN_TICKS);
        rig.act(mc -> mc.gui.setScreen(null));
        return rig.world().where().distanceTo(from);
    }

    private void guiMove(Rig rig) {
        double still = turnedBehindInventory(rig);
        rig.client().enable("GUIMove");
        double turned = turnedBehindInventory(rig);
        rig.expect(still < 1, "The arrow key turned the view without GUIMove. That proves nothing");
        rig.expect(turned >= 20, "The arrow key turned the view by " + Math.round(turned) + " degrees");
    }

    // Degrees the view turned whilst the right arrow was held behind the inventory.
    private static double turnedBehindInventory(Rig rig) {
        rig.act(mc -> mc.gui.setScreen(new InventoryScreen(mc.player)));
        rig.ticks(2);
        float from = rig.ask(mc -> mc.player.getYRot());
        rig.controls().hold(InputConstants.KEY_RIGHT);
        rig.ticks(SEEN_TICKS);
        rig.controls().release(InputConstants.KEY_RIGHT);
        float to = rig.ask(mc -> mc.player.getYRot());
        rig.act(mc -> mc.gui.setScreen(null));
        return Math.abs(Mth.degreesDifference(from, to));
    }

    // In player mode the mouse has the camera and the arrow keys have the body.
    private void freeLook(Rig rig) {
        float from = rig.ask(mc -> mc.player.getYRot());
        rig.client().enable("FreeLook");
        try {
            rig.ticks(SEEN_TICKS);
            float looked = rig.ask(mc -> mc.gameRenderer.mainCamera().yRot());
            rig.controls().hold(InputConstants.KEY_RIGHT);
            rig.ticks(SEEN_TICKS);
            rig.controls().release(InputConstants.KEY_RIGHT);
            rig.ticks(2);
            double body = Math.abs(Mth.degreesDifference(from, rig.ask(mc -> mc.player.getYRot())));
            double camera = Math.abs(Mth.degreesDifference(looked, rig.ask(mc -> mc.gameRenderer.mainCamera().yRot())));
            rig.note("The body turned " + Math.round(body) + " degrees and the camera " + Math.round(camera));
            rig.expect(body >= 20, "The arrow key turned the body by " + Math.round(body) + " degrees");
            rig.expect(camera < 2, "The camera turned with the body");
        } finally {
            rig.client().disable("FreeLook");
            rig.act(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
        }
    }

    // The keys are read once a frame in place of once a tick. A number key and the attack button
    // must still do their work.
    private void frameInput(Rig rig) {
        BlockPos block = home.north(2);
        rig.world().refill(block, block, "dirt");
        Props.hold(rig, 0);
        rig.client().enable("FrameInput");
        rig.controls().press(InputConstants.KEY_3);
        rig.ticks(SEEN_TICKS);
        int slot = rig.ask(mc -> mc.player.getInventory().getSelectedSlot());
        rig.controls().aimAtBlock(block, Direction.SOUTH);
        rig.ticks(2);
        rig.controls().holdMouse(ATTACK);
        try {
            rig.until("the dirt to break", MINE_TICKS * 2, mc -> mc.level.getBlockState(block).isAir());
        } finally {
            rig.controls().releaseMouse(ATTACK);
        }
        rig.expect(slot == 2, "The key for the third slot left slot " + slot + " in the hand");
    }

    private void menuClicker(Rig rig) {
        BlockPos chest = home.north(2);
        rig.world().command("setblock " + Props.at(chest) + " chest[facing=south]{Items:[{Slot:0b,"
            + "id:\"minecraft:diamond\",count:3}]}");
        Props.pick(rig, "MenuClicker", "Menus", "minecraft:generic_9x3");
        Props.text(rig, "MenuClicker", "Step 1", "shift 0");
        Props.hold(rig, 0);
        rig.client().enable("MenuClicker");
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAt(Vec3.atCenterOf(chest));
        rig.ticks(2);
        rig.controls().click(USE);
        try {
            Props.await(rig, "the diamonds to move into the backpack", ACT_TICKS,
                () -> Props.carried(rig, "diamond") == 3);
        } finally {
            rig.act(mc -> mc.player.closeContainer());
        }
    }

    private void shulkerPacker(Rig rig) {
        Props.hold(rig, 0, "shulker_box");
        Props.pack(rig, 0, "cobblestone 64");
        Props.pack(rig, 1, "cobblestone 64");
        rig.ticks(SEEN_TICKS);
        rig.client().enable("ShulkerPacker");
        int took = Props.await(rig, "the cobblestone to be packed away and the box picked up", ACT_TICKS * 4,
            () -> Props.carried(rig, "cobblestone") == 0 && Props.carried(rig, "shulker_box") == 1);
        rig.note("The box was packed and back in the backpack after about " + took + " ticks");
    }

    // Escape puts the screen away and the menu stays. The inventory key brings the screen back.
    private void chestLink(Rig rig) {
        BlockPos chest = home.north(2);
        rig.world().setBlock(chest, "ender_chest[facing=south]");
        Props.hold(rig, 0);
        rig.client().enable("ChestLink");
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAt(Vec3.atCenterOf(chest));
        rig.ticks(2);
        rig.controls().click(USE);
        rig.until("the ender chest to open", ACT_TICKS, mc -> mc.gui.screen() instanceof AbstractContainerScreen);
        rig.controls().press(InputConstants.KEY_ESCAPE);
        rig.ticks(SEEN_TICKS);
        boolean away = rig.ask(mc -> mc.gui.screen() == null);
        boolean kept = rig.ask(mc -> mc.player.containerMenu != mc.player.inventoryMenu);
        rig.controls().press(InputConstants.KEY_E);
        rig.ticks(SEEN_TICKS);
        boolean back = rig.ask(mc -> mc.gui.screen() instanceof AbstractContainerScreen
            && !(mc.gui.screen() instanceof InventoryScreen));
        rig.client().disable("ChestLink");
        rig.act(mc -> mc.gui.setScreen(null));
        rig.expect(away, "Escape left the screen up");
        rig.expect(kept, "Escape closed the ender chest for good");
        rig.expect(back, "The inventory key did not bring the ender chest back");
    }

    private void chestMemory(Rig rig) {
        BlockPos chest = home.north(2);
        rig.world().command("setblock " + Props.at(chest) + " chest[facing=south]{Items:[{Slot:0b,"
            + "id:\"minecraft:diamond\",count:3}]}");
        Props.hold(rig, 0);
        rig.client().enable("ChestMemory");
        List<String> before = rig.client().command("chests diamond");
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAt(Vec3.atCenterOf(chest));
        rig.ticks(2);
        rig.controls().click(USE);
        rig.until("the chest to open", ACT_TICKS, mc -> mc.gui.screen() instanceof AbstractContainerScreen);
        rig.ticks(SEEN_TICKS);
        rig.act(mc -> mc.player.closeContainer());
        rig.ticks(SEEN_TICKS);
        List<String> after = rig.client().command("chests diamond");
        rig.note("Asked for diamonds it said " + after);
        rig.expect(before.stream().noneMatch(line -> line.contains("found")), "It knew of the diamonds before the "
            + "chest was ever opened. That proves nothing");
        rig.expect(after.stream().anyMatch(line -> line.contains("found")), "It does not know the chest it just saw");
    }

    private void betterTooltips(Rig rig) {
        Props.hold(rig, 0, BEEF);
        rig.ticks(SEEN_TICKS);
        boolean plain = !tooltipSays(rig, "saturation");
        rig.client().enable("BetterTooltips");
        boolean told = tooltipSays(rig, "saturation");
        rig.expect(plain, "The tooltip named the saturation without BetterTooltips. That proves nothing");
        rig.expect(told, "The tooltip of the food says nothing about hunger and saturation");
    }

    // Rests the pointer on the first hotbar slot of the inventory and reads what the screen writes.
    private static boolean tooltipSays(Rig rig, String words) {
        rig.act(mc -> mc.gui.setScreen(new InventoryScreen(mc.player)));
        rig.ticks(2);
        int[] corner = rig.ask(mc -> new int[] {(mc.getWindow().getGuiScaledWidth() - INVENTORY_WIDTH) / 2,
            (mc.getWindow().getGuiScaledHeight() - INVENTORY_HEIGHT) / 2});
        rig.controls().pointer(corner[0] + SLOT_X, corner[1] + SLOT_Y);
        rig.ticks(SEEN_TICKS);
        boolean written = rig.frames().capture("tooltip " + words).shows(words);
        rig.controls().parkPointer();
        rig.act(mc -> mc.gui.setScreen(null));
        rig.ticks(2);
        return written;
    }
}
