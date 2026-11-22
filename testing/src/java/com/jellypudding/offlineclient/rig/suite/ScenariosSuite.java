package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.util.Hop;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

// Each case plays one thing a player reported the way the player did it and then looks at what
// the world shows. The server of the single player world checks every move as any server does.
public final class ScenariosSuite implements Suite {

    private static final String NAME = "Scenarios";
    private static final WorldCreationUiState.SelectedGameMode CREATIVE =
        WorldCreationUiState.SelectedGameMode.CREATIVE;
    private static final String SURVIVAL = "survival";
    private static final int USE = InputConstants.MOUSE_BUTTON_RIGHT;
    private static final int SETTLE_TICKS = 20;

    private static final String LEAVES = "oak_leaves[persistent=true]";
    private static final double LONG_AIM = 32;
    private static final int TRIP_TICKS = 200;
    private static final double ON_TOP = 0.6;

    private static final String NO_FALL = "NoFall";
    private static final int BUCKET_SLOT = 4;
    // A fall from here kills a player with nothing to break it.
    private static final int DEADLY_FALL = 30;
    private static final int FALL_TICKS = 200;
    private static final int COLLECT_TICKS = 60;
    private static final float FULL_HEALTH = 20;

    private static final int RUN_TICKS = 20;
    // Speed ships at a pace of one and six tenths of a sprint. A walk by hand is slower than a sprint.
    private static final double FASTER = 1.4;

    private static final String FARM = "AutoFarm";
    private static final String SOW = "Sow empty soil";
    private static final String TILL = "Till the soil";
    private static final int SWORD_SLOT = 0;
    private static final int HOE_SLOT = 1;
    private static final int SEED_SLOT = 3;
    private static final int TILL_TICKS = 40;
    private static final int SOW_TICKS = 60;
    private static final int HAND_BACK_TICKS = 40;
    private static final int FIELD = 3;
    // Water this far east wets the east of the field and leaves the west dry.
    private static final int WATER_EAST = 5;
    private static final int WATER_REACH = 4;
    private static final int FARM_LIMIT = 900;
    private static final int QUIET_TICKS = 60;
    private static final int STEP_TICKS = 10;
    private static final int FEWEST_CROPS = 3;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        List<Case> wanted = rig.wanted(NAME, List.of(
            Case.of("ClickTp puts you on top of a tree when you click the side of its top block",
                ScenariosSuite::clickTpTree),
            Case.of("ClickTp puts you beside a wall when a block sits on the one you click",
                ScenariosSuite::clickTpWall),
            Case.of("NoFall Place breaks a deadly fall with water and puts the hotbar back",
                each -> noFallPlace(each, true)),
            Case.of("NoFall Place stays on the bucket with Switch back off", each -> noFallPlace(each, false)),
            Case.of("Speed makes a run by hand faster whilst no walk is going", ScenariosSuite::speedByHand),
            Case.of("AutoFarm gives the hoe back after it sows what you tilled", ScenariosSuite::farmGivesBack),
            Case.of("AutoFarm tills wet soil and sows it and leaves dry soil alone", ScenariosSuite::farmTills)));
        if (wanted.isEmpty()) {
            return;
        }
        rig.openWorld(CREATIVE);
        Stage stage = new Stage(rig);
        for (Case each : wanted) {
            stage.reset(false);
            rig.run(NAME, each);
            if (!stage.worldStands()) {
                stage.reopen(CREATIVE);
            }
        }
    }

    // A small oak stands north of the player. From the ground only the sides of its top block
    // can be clicked.
    private static void clickTpTree(Rig rig) {
        World world = rig.world();
        BlockPos home = home(rig);
        world.gameMode(SURVIVAL);
        BlockPos base = home.offset(0, 0, -4);
        world.fill(base.offset(-2, 2, -2), base.offset(2, 3, 2), LEAVES);
        world.fill(base.offset(-1, 4, -1), base.offset(1, 4, 1), LEAVES);
        world.fill(base.offset(-1, 5, 0), base.offset(1, 5, 0), LEAVES);
        world.fill(base.offset(0, 5, -1), base.offset(0, 5, 1), LEAVES);
        world.fill(base, base.offset(0, 3, 0), "oak_log");
        world.teleport(home.getX() + 0.5, home.getY(), home.getZ() + 3.5, 180, 0);
        BlockPos top = base.offset(0, 5, 1);
        Vec3 stands = clickSouthFace(rig, top, "the top leaf");
        rig.expect(stands.distanceTo(Vec3.atBottomCenterOf(top.above())) < ON_TOP,
            "The player does not stand on top of the block that was clicked");
    }

    // A pillar two blocks high stands north of the player. Nobody fits on its lower block and a
    // click on the side of that block lands on the ground beside it.
    private static void clickTpWall(Rig rig) {
        World world = rig.world();
        BlockPos home = home(rig);
        world.gameMode(SURVIVAL);
        BlockPos low = home.offset(0, 0, -5);
        world.fill(low, low.above(), "stone");
        Vec3 stands = clickSouthFace(rig, low, "the lower block");
        rig.expect(stands.distanceTo(Vec3.atBottomCenterOf(low.south())) < ON_TOP,
            "The player does not stand on the ground beside the face that was clicked");
    }

    // Uses the hand on the south face of a block with ClickTp on and hands back where the trip ended.
    private static Vec3 clickSouthFace(Rig rig, BlockPos block, String called) {
        rig.client().enable("ClickTp");
        rig.controls().aimAt(Vec3.atCenterOf(block).add(0, 0, 0.5));
        HitResult aimed = rig.ask(mc -> mc.player.pick(LONG_AIM, 1, false));
        rig.expect(aimed instanceof BlockHitResult hit && hit.getBlockPos().equals(block)
            && hit.getDirection() == Direction.SOUTH, "The crosshair is not on the south face of " + called);
        rig.controls().click(USE);
        rig.ticks(2);
        rig.until("the trip to end", TRIP_TICKS, mc -> !Hop.travelling());
        rig.ticks(SETTLE_TICKS);
        Vec3 stands = rig.ask(mc -> mc.player.position());
        rig.note("Clicked the south face of " + called + " at " + block.toShortString() + " and stands at "
            + String.format(Locale.ROOT, "%.2f %.2f %.2f", stands.x, stands.y, stands.z));
        Stage.expectAlive(rig);
        return stands;
    }

    private static void noFallPlace(Rig rig, boolean switchBack) {
        World world = rig.world();
        BlockPos home = home(rig);
        world.gameMode(SURVIVAL);
        world.hotbar(SWORD_SLOT, "diamond_sword");
        world.hotbar(BUCKET_SLOT, "water_bucket");
        hold(rig, SWORD_SLOT);
        rig.client().set(NO_FALL, "Mode", "place");
        rig.client().set(NO_FALL, "Switch back", switchBack ? "on" : "off");
        rig.client().enable(NO_FALL);
        world.command("tp " + world.player() + " " + (home.getX() + 0.5) + " " + (home.getY() + DEADLY_FALL)
            + " " + (home.getZ() + 0.5));
        rig.until("the fall to begin", SETTLE_TICKS, mc -> mc.player.getY() > home.getY() + 2 && !mc.player.onGround());
        int fell = rig.until("the landing", FALL_TICKS, mc -> mc.player.onGround() || mc.player.isInWater());
        rig.ticks(COLLECT_TICKS);
        Stage.expectAlive(rig);
        float health = world.health();
        int slot = rig.ask(mc -> InventoryUtil.selectedSlot());
        String bucket = rig.ask(mc -> mc.player.getInventory().getItem(BUCKET_SLOT).getItem().toString());
        boolean wet = rig.ask(mc -> !mc.level.getFluidState(home).isEmpty());
        rig.note("Fell for " + fell + " ticks and has " + health + " health and holds slot " + slot + " and slot "
            + BUCKET_SLOT + " holds " + bucket);
        rig.expect(health == FULL_HEALTH, "The fall cost " + (FULL_HEALTH - health) + " health");
        rig.expect(!wet, "The water was left on the ground");
        rig.expect(bucket.contains("water_bucket"), "The water did not go back into the bucket");
        rig.expect(slot == (switchBack ? SWORD_SLOT : BUCKET_SLOT), switchBack
            ? "The hotbar did not go back to the sword" : "The hotbar left the bucket with Switch back off");
    }

    private static void speedByHand(Rig rig) {
        World world = rig.world();
        BlockPos home = home(rig);
        world.gameMode(SURVIVAL);
        double plain = runAhead(rig);
        world.teleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 180, 0);
        rig.client().enable("Speed");
        double fast = runAhead(rig);
        rig.note(String.format(Locale.ROOT, "%.1f blocks in a second with Speed on and %.1f without", fast, plain));
        rig.expect(fast > plain * FASTER, "Speed did not make the run faster");
    }

    private static double runAhead(Rig rig) {
        Vec3 from = rig.ask(mc -> mc.player.position());
        rig.controls().hold(InputConstants.KEY_W);
        rig.ticks(RUN_TICKS);
        rig.controls().release(InputConstants.KEY_W);
        Vec3 to = rig.ask(mc -> mc.player.position());
        rig.ticks(SETTLE_TICKS);
        return to.subtract(from).horizontalDistance();
    }

    // The player tills one block of grass by hand with the hoe. AutoFarm sows it with seeds from
    // another slot and the hoe must come back afterwards.
    private static void farmGivesBack(Rig rig) {
        World world = rig.world();
        BlockPos home = home(rig);
        world.gameMode(SURVIVAL);
        world.hotbar(HOE_SLOT, "diamond_hoe");
        world.hotbar(SEED_SLOT, "wheat_seeds 16");
        hold(rig, HOE_SLOT);
        rig.client().set(FARM, SOW, "on");
        rig.client().enable(FARM);
        BlockPos soil = home.offset(0, -1, -2);
        rig.controls().aimAtBlock(soil, Direction.UP);
        rig.controls().click(USE);
        rig.until("the hoe to till the grass", TILL_TICKS, mc -> is(mc, soil, Blocks.FARMLAND));
        rig.until("AutoFarm to sow the new farmland", SOW_TICKS, mc -> is(mc, soil.above(), Blocks.WHEAT));
        int waited = rig.until("the hotbar to go back to the hoe", HAND_BACK_TICKS,
            mc -> InventoryUtil.selectedSlot() == HOE_SLOT);
        rig.note("The hoe was back in hand " + waited + " ticks after the seed went in");
    }

    // Water lies east of the player. AutoFarm may till and sow the soil the water reaches and
    // nothing west of it. The sword held at the start must be back in hand at the end.
    private static void farmTills(Rig rig) {
        World world = rig.world();
        BlockPos home = home(rig);
        world.gameMode(SURVIVAL);
        BlockPos water = home.offset(WATER_EAST, -1, 0);
        world.setBlock(water, "water");
        world.hotbar(SWORD_SLOT, "diamond_sword");
        world.hotbar(HOE_SLOT, "diamond_hoe");
        world.hotbar(SEED_SLOT, "wheat_seeds 64");
        hold(rig, SWORD_SLOT);
        rig.client().set(FARM, SOW, "on");
        rig.client().set(FARM, TILL, "on");
        rig.client().enable(FARM);
        int crops = 0;
        int quiet = 0;
        for (int waited = 0; waited < FARM_LIMIT && quiet < QUIET_TICKS; waited += STEP_TICKS) {
            rig.ticks(STEP_TICKS);
            int now = rig.ask(mc -> count(mc, home, Blocks.WHEAT, 0));
            quiet = now == crops ? quiet + STEP_TICKS : 0;
            crops = now;
        }
        int bare = rig.ask(mc -> count(mc, home, Blocks.FARMLAND, -1)) - crops;
        int dry = rig.ask(mc -> {
            int found = 0;
            for (BlockPos pos : field(home, -1)) {
                boolean wet = Math.abs(pos.getX() - water.getX()) <= WATER_REACH
                    && Math.abs(pos.getZ() - water.getZ()) <= WATER_REACH;
                if (!wet && is(mc, pos, Blocks.FARMLAND)) {
                    found++;
                }
            }
            return found;
        });
        rig.note(crops + " crops stand on soil AutoFarm tilled");
        rig.expect(crops >= FEWEST_CROPS, "AutoFarm tilled and sowed " + crops + " blocks of wet soil in reach");
        rig.expect(bare == 0, bare + " blocks of farmland were left with no seed");
        rig.expect(dry == 0, dry + " blocks of soil the water does not reach were tilled");
        rig.expect(rig.ask(mc -> InventoryUtil.selectedSlot()) == SWORD_SLOT,
            "The hotbar did not go back to the sword once the work was done");
    }

    private static BlockPos home(Rig rig) {
        return rig.ask(mc -> mc.player.blockPosition());
    }

    private static void hold(Rig rig, int slot) {
        rig.act(mc -> mc.player.getInventory().setSelectedSlot(slot));
        rig.ticks(2);
    }

    private static boolean is(Minecraft mc, BlockPos pos, Block block) {
        return mc.level.getBlockState(pos).is(block);
    }

    private static Iterable<BlockPos> field(BlockPos home, int layer) {
        return BlockPos.betweenClosed(home.offset(-FIELD, layer, -FIELD), home.offset(FIELD, layer, FIELD));
    }

    private static int count(Minecraft mc, BlockPos home, Block block, int layer) {
        int found = 0;
        for (BlockPos pos : field(home, layer)) {
            if (is(mc, pos, block)) {
                found++;
            }
        }
        return found;
    }
}
