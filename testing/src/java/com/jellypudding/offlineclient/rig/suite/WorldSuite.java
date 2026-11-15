package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.rig.suite.Motion.Moved;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// One case for each module that breaks or places or works the world round the player. The
// server says which blocks stand afterwards and what the animals and the chest hold.
public final class WorldSuite implements Suite {

    private static final String NAME = "World";
    private static final int FORWARD = InputConstants.KEY_W;
    private static final int ATTACK = InputConstants.MOUSE_BUTTON_LEFT;
    private static final int USE = InputConstants.MOUSE_BUTTON_RIGHT;

    private static final String AIR = "air";
    private static final String STONE = "stone";
    private static final String PICKAXE = "diamond_pickaxe";
    private static final String COBBLESTONE = "cobblestone";

    private static final int FLOOR_REACH = 6;
    private static final int PERCH = 8;
    private static final int PICKAXE_SLOT = 3;
    private static final int SEEN_TICKS = 10;
    private static final int WALK_TICKS = 20;
    private static final int WORK_TICKS = 200;
    // Blocks of water each way from the middle of the pond. A cast lands a few blocks off its aim.
    private static final int POND = 5;
    private static final int LONG_BREAK = 200;
    private static final int DIAMONDS = 5;
    private static final double LOOKED_AWAY = 8;
    private static final double ENDER_EYES = 2.55;
    // The far corner of the map square as MapPrinter names it.
    private static final Pattern SQUARE = Pattern.compile("to (-?[0-9]+) (-?[0-9]+)");

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
        plays.put("Nuker breaks the blocks around you", this::nuker);
        plays.put("Scaffold lays a floor under you over a drop", this::scaffold);
        plays.put("Tunneller digs through a wall", this::tunneller);
        plays.put("VeinMiner mines the whole vein", this::veinMiner);
        plays.put("AutoTool takes the pickaxe out for stone", this::autoTool);
        plays.put("FastBreak breaks a block sooner", this::fastBreak);
        plays.put("TillAura tills the soil around you", this::tillAura);
        plays.put("BonemealAura grows the crop beside you", this::bonemealAura);
        plays.put("LiquidFiller fills a spring with a block", this::liquidFiller);
        plays.put("AutoBreed feeds the animals around you", this::autoBreed);
        plays.put("AutoShearer shears a sheep", this::autoShearer);
        plays.put("AutoMount climbs onto a horse", this::autoMount);
        plays.put("ChestStealer empties a chest", this::chestStealer);
        plays.put("ChestAura empties the chest beside you", this::chestAura);
        plays.put("AirPlace places a block in mid air", this::airPlace);
        plays.put("PacketMine breaks the block you click", this::packetMine);
        plays.put("Arson sets the wood round you alight", this::arson);
        plays.put("BuildRandom places blocks round you", this::buildRandom);
        plays.put("AutoFish casts into the water", this::autoFish);
        plays.put("Excavator digs the box between two clicked corners", this::excavator);
        plays.put("Filler fills the box between two clicked corners", this::filler);
        plays.put("TrailMaker lays blocks behind you", this::trailMaker);
        plays.put("InstantBunker walls you in", this::instantBunker);
        plays.put("Collisions keeps you out of a web", this::collisions);
        plays.put("ToolAura makes a path with the shovel you hold", this::toolAura);
        plays.put("WaxAura waxes copper", this::waxAura);
        plays.put("AutoNametag names the wolf beside you", this::autoNametag);
        plays.put("EndermanLook turns your eyes off an enderman", this::endermanLook);
        plays.put("AutoSmelter loads the furnace you open", this::autoSmelter);
        plays.put("AutoSign writes the sign you place", this::autoSign);
        plays.put("BrushReader names the find you brush", this::brushReader);
        plays.put("MapPrinter asks for the blocks of its station and turns down one where the picture goes",
            this::mapPrinter);
        plays.put("SpawnProofer lights the dark ground at night", this::spawnProofer);
        plays.put("Painter covers the block it is told to cover", this::painter);
        plays.put("BlockSwap swaps a path for grass", this::blockSwap);
        plays.put("TreeAura plants a sapling and feeds it", this::treeAura);
        plays.put("Flamethrower sets a cow alight", this::flamethrower);
        plays.put("AutoInteract milks the cow beside you", this::autoInteract);
        plays.put("EChestFarmer turns ender chests into obsidian", this::eChestFarmer);
        plays.put("OpenWaterESP tells open water from a puddle", this::openWaterEsp);
        return plays;
    }

    // The floor turns to bedrock first. Nuker would dig the player into the ground long before
    // it got to the blocks set out for it.
    private void nuker(Rig rig) {
        World world = rig.world();
        world.fill(home.offset(-FLOOR_REACH, -1, -FLOOR_REACH), home.offset(FLOOR_REACH, -1, FLOOR_REACH), "bedrock");
        List<BlockPos> blocks = List.of(home.north(2), home.east(2), home.west(2));
        blocks.forEach(pos -> world.setBlock(pos, "dirt"));
        rig.client().enable("Nuker");
        int took = Props.await(rig, "the three blocks to break", WORK_TICKS,
            () -> blocks.stream().allMatch(pos -> Props.is(rig, pos, AIR)));
        rig.note("Three blocks of dirt were gone after about " + took + " ticks");
    }

    // The player stands on one block high in the air and walks off it.
    private void scaffold(Rig rig) {
        World world = rig.world();
        world.setBlock(home.above(PERCH - 1), STONE);
        world.teleport(home.getX() + 0.5, home.getY() + PERCH, home.getZ() + 0.5, 180, 0);
        Props.hold(rig, 0, COBBLESTONE + " 64");
        rig.client().enable("Scaffold");
        Moved moved = Motion.hold(rig, WALK_TICKS, FORWARD);
        Vec3 at = world.where();
        rig.note(String.format(Locale.ROOT, "It walked %.1f blocks out and ended %.1f blocks lower", moved.across(),
            -moved.rise()));
        rig.expect(moved.across() >= 3, "The player did not walk out over the drop");
        rig.expect(Math.abs(moved.rise()) < 0.1, "The player fell");
        rig.expect(Props.is(rig, BlockPos.containing(at).below(), COBBLESTONE), "No cobblestone lies under the player");
    }

    // The module walks by itself. A wall three thick stands in its way.
    private void tunneller(Rig rig) {
        World world = rig.world();
        world.fill(home.offset(-1, 0, -4), home.offset(1, 2, -2), STONE);
        Props.hold(rig, 0, PICKAXE);
        rig.client().enable("Tunneller");
        Props.await(rig, "the tunnel through the wall", WORK_TICKS, () -> Props.is(rig, home.offset(0, 0, -4), AIR)
            && Props.is(rig, home.offset(0, 1, -4), AIR));
        rig.client().disable("Tunneller");
        for (int deep = 2; deep <= 4; deep++) {
            rig.expect(Props.is(rig, home.offset(0, 0, -deep), AIR) && Props.is(rig, home.offset(0, 1, -deep), AIR),
                "The tunnel is not open " + deep + " blocks in");
        }
    }

    private void veinMiner(Rig rig) {
        World world = rig.world();
        world.fill(home.offset(-2, 0, -2), home.offset(2, 0, -2), "coal_ore");
        Props.hold(rig, 0, PICKAXE);
        rig.client().enable("VeinMiner");
        BlockPos first = home.offset(0, 0, -2);
        rig.controls().aimAtBlock(first, Direction.SOUTH);
        rig.controls().holdMouse(ATTACK);
        rig.until("the first block of ore to break", LONG_BREAK, mc -> mc.level.getBlockState(first).isAir());
        rig.controls().releaseMouse(ATTACK);
        int took = Props.await(rig, "the rest of the vein to break", WORK_TICKS, () -> {
            for (int across = -2; across <= 2; across++) {
                if (!Props.is(rig, home.offset(across, 0, -2), AIR)) {
                    return false;
                }
            }
            return true;
        });
        rig.note("The other four blocks were gone about " + took + " ticks after the first");
    }

    private void autoTool(Rig rig) {
        World world = rig.world();
        BlockPos block = home.north(2);
        world.setBlock(block, STONE);
        world.hotbar(PICKAXE_SLOT, PICKAXE);
        Props.hold(rig, 0);
        rig.client().enable("AutoTool");
        rig.controls().aimAtBlock(block, Direction.SOUTH);
        rig.controls().holdMouse(ATTACK);
        rig.ticks(SEEN_TICKS);
        String slot = world.facts().get("slot");
        rig.controls().releaseMouse(ATTACK);
        rig.expect(Integer.toString(PICKAXE_SLOT).equals(slot),
            "The server has the player on slot " + slot + " and the pickaxe lies in slot " + PICKAXE_SLOT);
    }

    // A log takes three seconds by hand. A server lets a block go a little sooner than that
    // and no more.
    private void fastBreak(Rig rig) {
        int plain = broken(rig);
        rig.client().set("FastBreak", "Speed", "1.3");
        rig.client().enable("FastBreak");
        int fast = broken(rig);
        rig.note("The log broke in " + fast + " ticks against " + plain);
        rig.expect(fast <= plain * 0.9, "FastBreak broke the log no sooner");
    }

    // Ticks the player needs to break a log by hand. The server must agree it is gone.
    private int broken(Rig rig) {
        BlockPos block = home.north(2);
        rig.world().setBlock(block, "oak_log");
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAtBlock(block, Direction.SOUTH);
        rig.controls().holdMouse(ATTACK);
        int took = rig.until("the log to break", LONG_BREAK, mc -> mc.level.getBlockState(block).isAir());
        rig.controls().releaseMouse(ATTACK);
        rig.ticks(SEEN_TICKS);
        rig.expect(Props.is(rig, block, AIR), "The server still has the log the game broke");
        return took;
    }

    private void tillAura(Rig rig) {
        Props.hold(rig, 0, "diamond_hoe");
        rig.client().enable("TillAura");
        int took = Props.await(rig, "farmland beside the player", WORK_TICKS,
            () -> Props.is(rig, home.offset(0, -1, -1), "farmland")
                && Props.is(rig, home.offset(1, -1, 0), "farmland"));
        rig.note("The soil north and east of the player was tilled after about " + took + " ticks");
    }

    private void bonemealAura(Rig rig) {
        World world = rig.world();
        BlockPos crop = home.north();
        world.setBlock(crop.below(), "farmland");
        world.setBlock(crop, "wheat");
        Props.hold(rig, 0, "bone_meal 32");
        rig.client().enable("BonemealAura");
        int took = Props.await(rig, "the wheat to ripen", WORK_TICKS, () -> world.attempt("execute if block "
            + crop.getX() + " " + crop.getY() + " " + crop.getZ() + " wheat[age=7]"));
        rig.note("The wheat was ripe after about " + took + " ticks");
    }

    private void liquidFiller(Rig rig) {
        World world = rig.world();
        BlockPos spring = home.offset(0, -1, -2);
        world.setBlock(spring, "water");
        Props.hold(rig, 0, COBBLESTONE + " 16");
        rig.client().enable("LiquidFiller");
        Props.await(rig, "the spring to be filled", WORK_TICKS, () -> Props.is(rig, spring, COBBLESTONE));
    }

    // Two cows that cannot walk off. Each one fed costs one wheat.
    private void autoBreed(Rig rig) {
        Props.summon(rig, "cow", home.north(2), Props.STILL_MOB);
        Props.summon(rig, "cow", home.east(2), Props.STILL_MOB);
        Props.hold(rig, 0, "wheat 8");
        rig.client().enable("AutoBreed");
        Props.await(rig, "both cows to be fed", WORK_TICKS, () -> Props.carried(rig, "wheat") == 6);
    }

    private void autoShearer(Rig rig) {
        Props.summon(rig, "sheep", home.north(2), Props.STILL_MOB);
        Props.hold(rig, 0, "shears");
        rig.client().enable("AutoShearer");
        Props.await(rig, "the sheep to be shorn", WORK_TICKS,
            () -> Props.living(rig, "sheep,nbt={Sheared:1b}") == 1);
    }

    private void autoMount(Rig rig) {
        Props.summon(rig, "horse", home.north(2), "{Tame:1b,NoAI:1b,PersistenceRequired:1b}");
        Props.hold(rig, 0);
        rig.client().enable("AutoMount");
        rig.until("the player to sit on the horse", WORK_TICKS, mc -> mc.player.isPassenger());
        rig.client().disable("AutoMount");
        rig.world().command("ride " + rig.world().player() + " dismount");
    }

    private void chestStealer(Rig rig) {
        BlockPos chest = fullChest(rig);
        Props.hold(rig, 0);
        rig.client().enable("ChestStealer");
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAtBlock(chest, Direction.SOUTH);
        rig.controls().click(USE);
        int took = Props.await(rig, "the chest to be emptied", WORK_TICKS,
            () -> Props.carried(rig, "diamond") == DIAMONDS && Props.carried(rig, "gold_ingot") == 3);
        rig.note("Everything was out of the chest after about " + took + " ticks");
        rig.act(mc -> {
            if (mc.gui.screen() != null) {
                mc.player.closeContainer();
            }
        });
    }

    // A chest two blocks ahead with five diamonds and three gold ingots in it.
    private BlockPos fullChest(Rig rig) {
        BlockPos chest = home.north(2);
        rig.world().command("setblock " + chest.getX() + " " + chest.getY() + " " + chest.getZ()
            + " chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:diamond\",count:" + DIAMONDS
            + "},{Slot:5b,id:\"minecraft:gold_ingot\",count:3}]}");
        return chest;
    }

    private void chestAura(Rig rig) {
        fullChest(rig);
        Props.hold(rig, 0);
        rig.client().enable("ChestAura");
        int took = Props.await(rig, "the chest to be emptied", WORK_TICKS,
            () -> Props.carried(rig, "diamond") == DIAMONDS && Props.carried(rig, "gold_ingot") == 3);
        rig.note("Everything was out of the chest after about " + took + " ticks");
    }

    // The player looks straight ahead where nothing stands to place a block against.
    private void airPlace(Rig rig) {
        Props.hold(rig, 0, COBBLESTONE + " 8");
        rig.client().enable("AirPlace");
        rig.ticks(SEEN_TICKS);
        rig.controls().click(USE);
        Props.await(rig, "a block in the air ahead", WORK_TICKS / 4, () -> {
            for (int ahead = 1; ahead <= FLOOR_REACH; ahead++) {
                if (Props.is(rig, home.offset(0, 1, -ahead), COBBLESTONE)) {
                    return true;
                }
            }
            return false;
        });
    }

    // One tap on the block and the hand is free again.
    private void packetMine(Rig rig) {
        BlockPos block = home.north(2);
        rig.world().setBlock(block, STONE);
        Props.hold(rig, 0, PICKAXE);
        rig.client().enable("PacketMine");
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAtBlock(block, Direction.SOUTH);
        rig.controls().click(ATTACK);
        int took = Props.await(rig, "the block to break", WORK_TICKS, () -> Props.is(rig, block, AIR));
        rig.note("The block was gone about " + took + " ticks after one tap");
    }

    // Two posts of wood. The module leaves the level you stand on alone and lights what is higher.
    private void arson(Rig rig) {
        World world = rig.world();
        List<BlockPos> wood = List.of(home.north(3).above(), home.east(3).above());
        wood.forEach(pos -> world.fill(pos.below(), pos, "oak_planks"));
        Props.hold(rig, 0, "flint_and_steel");
        rig.client().enable("Arson");
        Props.await(rig, "fire on the wood", WORK_TICKS, () -> wood.stream().anyMatch(pos -> burns(rig, pos)));
    }

    // True when fire stands on the block or against one of its sides.
    private static boolean burns(Rig rig, BlockPos pos) {
        for (BlockPos beside : List.of(pos.above(), pos.north(), pos.east(), pos.south(), pos.west())) {
            if (Props.is(rig, beside, "fire")) {
                return true;
            }
        }
        return false;
    }

    private void buildRandom(Rig rig) {
        Props.hold(rig, 0, COBBLESTONE + " 64");
        rig.client().enable("BuildRandom");
        int took = Props.await(rig, "blocks to leave the hand", WORK_TICKS,
            () -> Props.carried(rig, COBBLESTONE) <= 60);
        rig.note("Four blocks were placed after about " + took + " ticks");
    }

    private void autoFish(Rig rig) {
        rig.world().fill(home.offset(-1, -2, -6), home.offset(1, -1, -3), "water");
        Props.hold(rig, 0, "fishing_rod");
        rig.controls().look(180, 25);
        rig.client().enable("AutoFish");
        Props.await(rig, "the bobber to fly", WORK_TICKS / 2, () -> Props.living(rig, "fishing_bobber") == 1);
    }

    private void excavator(Rig rig) {
        World world = rig.world();
        world.fill(home.offset(-1, 0, -2), home.offset(1, 0, -2), "dirt");
        Props.hold(rig, 0);
        rig.client().enable("Excavator");
        clickCorners(rig, home.offset(-1, 0, -2), Direction.SOUTH, home.offset(1, 0, -2));
        int took = Props.await(rig, "the three blocks to be dug out", WORK_TICKS, () -> {
            for (int across = -1; across <= 1; across++) {
                if (!Props.is(rig, home.offset(across, 0, -2), AIR)) {
                    return false;
                }
            }
            return true;
        });
        rig.note("The box was dug out after about " + took + " ticks");
    }

    // One corner is ground and the other the top of a post. The box between them holds air.
    private void filler(Rig rig) {
        World world = rig.world();
        world.fill(home.offset(1, 0, -2), home.offset(1, 1, -2), STONE);
        Props.hold(rig, 0, COBBLESTONE + " 64");
        rig.client().enable("Filler");
        clickCorners(rig, home.offset(-1, -1, -2), Direction.UP, home.offset(1, 1, -2));
        Props.await(rig, "the box to be filled", WORK_TICKS, () -> Props.is(rig, home.offset(0, 0, -2), COBBLESTONE)
            && Props.is(rig, home.offset(-1, 1, -2), COBBLESTONE));
    }

    // The first corner is clicked on the face named and the second on the face towards the player.
    private static void clickCorners(Rig rig, BlockPos first, Direction face, BlockPos second) {
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAtBlock(first, face);
        rig.controls().click(USE);
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAtBlock(second, Direction.SOUTH);
        rig.controls().click(USE);
        rig.ticks(SEEN_TICKS);
    }

    private void trailMaker(Rig rig) {
        Props.hold(rig, 0, COBBLESTONE + " 64");
        rig.client().enable("TrailMaker");
        Motion.hold(rig, WALK_TICKS, FORWARD);
        rig.ticks(SEEN_TICKS);
        int left = Props.carried(rig, COBBLESTONE);
        rig.note((64 - left) + " blocks were laid over a walk of " + WALK_TICKS + " ticks");
        rig.expect(left <= 62, "No trail was laid");
    }

    private void instantBunker(Rig rig) {
        Props.hold(rig, 0, "obsidian 64");
        rig.client().enable("InstantBunker");
        // The shell is five blocks wide and its walls stand two blocks from the player.
        Props.await(rig, "walls on all four sides", WORK_TICKS, () -> !rig.world().isAir(home.north(2))
            && !rig.world().isAir(home.east(2)) && !rig.world().isAir(home.south(2))
            && !rig.world().isAir(home.west(2)));
    }

    // A web two blocks ahead. A walk ends in it or stops at its face.
    private void collisions(Rig rig) {
        double into = walkedAtWeb(rig);
        rig.world().teleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 180, 0);
        rig.client().enable("Collisions");
        double kept = walkedAtWeb(rig);
        rig.note(String.format(Locale.ROOT, "The walk got %.2f blocks with Collisions and %.2f without", kept, into));
        rig.expect(into > 1.35, "The walk never got into the web without Collisions. That proves nothing");
        rig.expect(kept <= 1.25, "The walk got into the web");
    }

    private double walkedAtWeb(Rig rig) {
        rig.world().refill(home.north(2), home.north(2), "cobweb");
        return Motion.hold(rig, WALK_TICKS * 2, FORWARD).across();
    }

    private void toolAura(Rig rig) {
        Props.hold(rig, 0, "diamond_shovel");
        rig.client().enable("ToolAura");
        Props.await(rig, "a path beside the player", WORK_TICKS,
            () -> Props.is(rig, home.offset(0, -1, -1), "dirt_path")
                || Props.is(rig, home.offset(1, -1, 0), "dirt_path"));
    }

    private void waxAura(Rig rig) {
        BlockPos copper = home.north(2);
        rig.world().setBlock(copper, "copper_block");
        Props.hold(rig, 0, "honeycomb 8");
        rig.client().enable("WaxAura");
        Props.await(rig, "the copper to be waxed", WORK_TICKS, () -> Props.is(rig, copper, "waxed_copper_block"));
    }

    private void autoNametag(Rig rig) {
        Props.summon(rig, "wolf", home.north(2), Props.STILL_MOB);
        Props.hold(rig, 0, "name_tag[custom_name=\"Rex\"]");
        rig.client().enable("AutoNametag");
        Props.await(rig, "the wolf to be called Rex", WORK_TICKS, () -> Props.living(rig, "wolf,name=Rex") == 1);
    }

    private void endermanLook(Rig rig) {
        BlockPos spot = home.north(4);
        Props.summon(rig, "enderman", spot, Props.STILL_MOB);
        Vec3 eyes = new Vec3(spot.getX() + 0.5, spot.getY() + ENDER_EYES, spot.getZ() + 0.5);
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAt(eyes);
        rig.ticks(2);
        double before = angleTo(rig, eyes);
        rig.client().enable("EndermanLook");
        rig.ticks(SEEN_TICKS * 2);
        double after = angleTo(rig, eyes);
        rig.note(String.format(Locale.ROOT, "The server had the view %.1f degrees off its eyes and then %.1f", before,
            after));
        rig.expect(before < 2, "The view was not on the enderman to begin with. That proves nothing");
        rig.expect(after >= LOOKED_AWAY, "The server still has the view on the enderman's eyes");
    }

    // Degrees between the way the server has the player looking and the way to a point. An
    // enderman is angered by what the server sees.
    private static double angleTo(Rig rig, Vec3 point) {
        Map<String, String> held = rig.world().facts();
        Vec3 view = Vec3.directionFromRotation(Float.parseFloat(held.get("pitch")), Float.parseFloat(held.get("yaw")));
        Vec3 eyes = rig.ask(mc -> mc.player.getEyePosition());
        return Math.toDegrees(Math.acos(Math.clamp(view.dot(point.subtract(eyes).normalize()), -1, 1)));
    }

    private void autoSmelter(Rig rig) {
        World world = rig.world();
        BlockPos furnace = home.north(2);
        world.setBlock(furnace, "furnace[facing=south]");
        Props.pack(rig, 0, "raw_iron 8");
        Props.pack(rig, 1, "coal 8");
        Props.hold(rig, 0);
        rig.client().enable("AutoSmelter");
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAtBlock(furnace, Direction.SOUTH);
        rig.controls().click(USE);
        try {
            Props.await(rig, "ore and fuel in the furnace", WORK_TICKS,
                () -> world.attempt("execute if items block " + Props.at(furnace) + " container.0 raw_iron")
                    && world.attempt("execute if items block " + Props.at(furnace) + " container.1 coal"));
        } finally {
            rig.act(mc -> {
                if (mc.gui.screen() != null) {
                    mc.player.closeContainer();
                }
            });
        }
    }

    private void autoSign(Rig rig) {
        World world = rig.world();
        Props.hold(rig, 0, "oak_sign 4");
        rig.client().enable("AutoSign");
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAtBlock(home.offset(0, -1, -2), Direction.UP);
        rig.controls().click(USE);
        BlockPos sign = home.north(2);
        Props.await(rig, "words on the sign", WORK_TICKS,
            () -> world.count("data get block " + Props.at(sign) + " front_text.messages[0]") > 0);
        rig.expect(rig.ask(mc -> !(mc.gui.screen() instanceof AbstractSignEditScreen)),
            "The sign screen was left open");
    }

    private void brushReader(Rig rig) {
        BlockPos sand = home.north(2);
        rig.world().command("setblock " + Props.at(sand) + " suspicious_sand{item:{id:\"minecraft:emerald\",count:1}}");
        Props.hold(rig, 0, "brush");
        rig.client().enable("BrushReader");
        rig.ticks(SEEN_TICKS);
        int mark = rig.client().chatMark();
        rig.controls().aimAtBlock(sand, Direction.SOUTH);
        rig.controls().holdMouse(USE);
        rig.ticks(WALK_TICKS * 2);
        rig.controls().releaseMouse(USE);
        List<String> said = rig.client().saidSince(mark);
        rig.note("BrushReader said " + said);
        rig.expect(said.stream().anyMatch(line -> line.toLowerCase(Locale.ROOT).contains("emerald")),
            "The find was not named");
    }

    private void spawnProofer(Rig rig) {
        World world = rig.world();
        Props.hold(rig, 0, "torch 64");
        world.command("time set midnight");
        try {
            rig.client().enable("SpawnProofer");
            // One torch lights every spot in reach.
            int took = Props.await(rig, "a torch on the dark ground", WORK_TICKS,
                () -> Props.carried(rig, "torch") < 64);
            rig.note("A torch stood after about " + took + " ticks");
        } finally {
            world.attempt("time set noon");
        }
    }

    private void painter(Rig rig) {
        BlockPos block = home.north(2);
        rig.world().setBlock(block, "netherrack");
        Props.hold(rig, 0, COBBLESTONE + " 64");
        rig.client().enable("Painter");
        Props.await(rig, "a block on top of the netherrack", WORK_TICKS,
            () -> Props.is(rig, block.above(), COBBLESTONE));
    }

    private void blockSwap(Rig rig) {
        BlockPos path = home.offset(1, -1, -1);
        rig.world().setBlock(path, "dirt_path");
        Props.hold(rig, 0, "grass_block 16");
        rig.client().enable("BlockSwap");
        Props.await(rig, "grass where the path was", WORK_TICKS, () -> Props.is(rig, path, "grass_block"));
    }

    private void treeAura(Rig rig) {
        Props.hold(rig, 0, "oak_sapling 8");
        rig.world().hotbar(1, "bone_meal 64");
        rig.client().enable("TreeAura");
        Props.await(rig, "a sapling to be planted and fed", WORK_TICKS,
            () -> Props.carried(rig, "oak_sapling") < 8 && Props.carried(rig, "bone_meal") < 64);
    }

    private void flamethrower(Rig rig) {
        Props.summon(rig, "cow", home.north(2), Props.STILL_MOB);
        Props.hold(rig, 0, "flint_and_steel");
        rig.client().enable("Flamethrower");
        Props.await(rig, "the cow to burn", WORK_TICKS,
            () -> Props.living(rig, "cow") == 0 || Props.health(rig, "cow") < 10);
    }

    private void autoInteract(Rig rig) {
        Props.summon(rig, "cow", home.north(2), Props.STILL_MOB);
        Props.hold(rig, 0, "bucket");
        rig.client().enable("AutoInteract");
        Props.await(rig, "milk in the bucket", WORK_TICKS, () -> Props.holds(rig, "weapon.mainhand", "milk_bucket"));
    }

    // The chest goes on the block under the crosshair. It stands right beside the player. What a
    // chest drops two blocks off lies out of the player's reach.
    private void eChestFarmer(Rig rig) {
        Props.hold(rig, 0, "ender_chest 8");
        rig.world().hotbar(1, PICKAXE);
        rig.controls().aimAtBlock(home.offset(0, -1, -1), Direction.UP);
        rig.client().enable("EChestFarmer");
        int took = Props.await(rig, "obsidian from the first chest", WORK_TICKS * 3,
            () -> Props.carried(rig, "obsidian") >= 8);
        rig.note("The first eight obsidian were in the pack after about " + took + " ticks");
    }

    // Fishing only counts as open water with five blocks of water each way round the bobber.
    private void openWaterEsp(Rig rig) {
        World world = rig.world();
        BlockPos near = home.offset(-POND, -2, -3 - 2 * POND);
        BlockPos far = home.offset(POND, -1, -3);
        world.setBlock(home.offset(3, -1, -2), "water");
        world.fill(near, far, "water");
        try {
            Props.hold(rig, 0, "fishing_rod");
            rig.client().enable("OpenWaterESP");
            String wide = waterUnderBobber(rig, home.offset(0, -1, -3 - POND));
            String narrow = waterUnderBobber(rig, home.offset(3, -1, -2));
            rig.note("It called the pond " + wide + " and the puddle " + narrow);
            rig.expect("open".equals(wide), "The pond was not called open water");
            rig.expect("shallow".equals(narrow), "The puddle was not called shallow");
        } finally {
            // The pond reaches past what the stage puts back by itself.
            world.fill(near, far.below(), "dirt");
            world.fill(near.above(), far, "grass_block");
        }
    }

    // Casts onto the water and hands back what the module writes beside its name. The line is
    // reeled in again.
    private static String waterUnderBobber(Rig rig, BlockPos water) {
        Module esp = rig.client().module("OpenWaterESP");
        // Water takes no crosshair. The view is turned to its surface.
        rig.controls().aimAt(Vec3.atBottomCenterOf(water).add(0, 1, 0));
        rig.ticks(SEEN_TICKS);
        rig.controls().click(USE);
        try {
            rig.until("the bobber to settle on the water", WORK_TICKS, mc -> esp.getSuffix() != null);
            rig.ticks(SEEN_TICKS);
            return rig.ask(mc -> String.valueOf(esp.getSuffix()));
        } finally {
            rig.controls().click(USE);
            rig.ticks(SEEN_TICKS * 2);
        }
    }

    // The station is marked one right click at a time and stands outside the square the picture
    // fills. A whole print takes hours and is not played here.
    private void mapPrinter(Rig rig) {
        World world = rig.world();
        Module printer = rig.client().module("MapPrinter");
        int mark = rig.client().chatMark();
        rig.act(mc -> {
            ActionSetting row = (ActionSetting) printer.getSetting("Mark station");
            row.press();
            if (row.isArmed()) {
                row.press();
            }
        });
        awaitSaid(rig, mark, "Right click the cartography table");
        Matcher square = SQUARE.matcher(String.join(" ", rig.client().saidSince(mark)));
        rig.expect(square.find(), "MapPrinter did not name its map square");
        int edge = Integer.parseInt(square.group(1));
        BlockPos inside = new BlockPos(edge - 2, home.getY(), home.getZ());
        BlockPos table = new BlockPos(edge + 2, home.getY(), home.getZ());
        BlockPos chest = table.south();
        try {
            world.teleport(edge + 0.5, home.getY(), home.getZ() + 0.5, -90, 0);
            world.setBlock(inside, "cartography_table");
            world.setBlock(table, "cartography_table");
            world.setBlock(chest, "chest");
            rig.ticks(SEEN_TICKS);
            click(rig, inside);
            awaitSaid(rig, mark, "stands where the picture goes");
            click(rig, table);
            awaitSaid(rig, mark, "chest with empty maps");
            click(rig, chest);
            awaitSaid(rig, mark, "finished maps go into");
            rig.expect(rig.ask(mc -> mc.gui.screen() == null), "A marking click opened the block it marked");
        } finally {
            rig.client().disable("MapPrinter");
            for (BlockPos block : List.of(inside, table, chest)) {
                world.setBlock(block, AIR);
            }
        }
    }

    private static void click(Rig rig, BlockPos block) {
        rig.controls().aimAtBlock(block, Direction.UP);
        rig.ticks(2);
        rig.controls().click(USE);
        rig.ticks(2);
    }

    private static void awaitSaid(Rig rig, int mark, String words) {
        Props.await(rig, "the client to say " + words, WORK_TICKS,
            () -> rig.client().saidSince(mark).stream().anyMatch(line -> line.contains(words)));
    }
}
