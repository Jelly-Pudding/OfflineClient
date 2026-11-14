package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.rig.suite.Motion.Moved;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private static final int LONG_BREAK = 200;
    private static final int DIAMONDS = 5;

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
}
