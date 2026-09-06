package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;

// A hole with somebody already in it cannot be filled since the server
// refuses a block inside a player. This seals holes before an enemy reaches one.
public final class HoleFiller extends Module {

    private final NumberSetting range = new NumberSetting("Range",
        "How far you can reach to place.", 4.5, 1, 6, 0.1, " blocks");
    private final NumberSetting wallsRange = new NumberSetting("Walls range",
        "Shorter reach for a hole you cannot see.", 4.5, 0, 6, 0.1, " blocks");
    private final NumberSetting searchRadius = new NumberSetting("Search radius",
        "How far around you holes are looked for.", 5, 1, 6, 1, " blocks");
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "Blocks to fill with in order of preference.", BuiltInRegistries.BLOCK,
        List.of(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.NETHERITE_BLOCK,
            Blocks.RESPAWN_ANCHOR, Blocks.COBWEB));
    private final BoolSetting nearEnemies = new BoolSetting("Near enemies only",
        "Only fill holes an enemy could reach. Off fills every hole in range.", true);
    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How close an enemy has to be to a hole for it to count.", 5, 1, 12, 0.5, " blocks")
        .under(nearEnemies);
    private final BoolSetting predict = new BoolSetting("Predict",
        "Fills the holes a moving enemy is heading for first.", true)
        .under(nearEnemies);
    private final NumberSetting leadTicks = new NumberSetting("Lead ticks",
        "How far ahead a moving enemy is projected.", 8, 1, 30, 1, " ticks")
        .under(predict);
    private final BoolSetting ignoreSafe = new BoolSetting("Ignore safe",
        "Skips enemies already walled in on every side.", true)
        .under(nearEnemies);
    private final BoolSetting onlyMoving = new BoolSetting("Only moving",
        "Skips enemies standing still.", false)
        .under(nearEnemies);
    private final KeybindSetting fillKey = new KeybindSetting("Fill key",
        "Fills every hole in range whilst this key is held.", KeybindSetting.UNBOUND);
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many blocks to place in one round.", 2, 1, 4, 1);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between placing rounds.", 1, 0, 10, 1, " ticks");
    private final BoolSetting genuineOnly = new BoolSetting("Genuine holes",
        "Only fill holes that are blast proof on every side.", true);
    private final BoolSetting doubles = new BoolSetting("Two wide holes",
        "Also count a hole whose one open side is the other half of a two wide hole.", true)
        .under(genuineOnly);
    private final BoolSetting ownHole = new BoolSetting("Keep own hole",
        "Never fill the hole you stand in or next to.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards each block.", true);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final BoolSetting render = new BoolSetting("Render",
        "Draws the holes waiting to be filled.", true);
    private final BoxStyle boxStyle = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH).under(render);
    private final ColorSetting nextColor = new ColorSetting("Next colour",
        "The holes going in this round.", 270, false).under(render);
    private final ColorSetting laterColor = new ColorSetting("Later colour",
        "The holes still waiting.", 270, 0.4f, 0.9f, false).under(render);

    private final List<Player> enemies = new ArrayList<>();
    private final List<BlockPos> holes = new ArrayList<>();
    private int timer;
    private final SlotSwap slots = new SlotSwap();

    public HoleFiller() {
        super("HoleFiller", "Seals the holes around an enemy before they can hide in one.", Category.COMBAT);
        addSettings(range, wallsRange, searchRadius, blocks, nearEnemies, targetRange, predict,
            leadTicks, ignoreSafe, onlyMoving, fillKey, perTick, delay, genuineOnly, doubles,
            ownHole, rotate, swing, render);
        addSettings(boxStyle.settings());
        addSettings(nextColor, laterColor);
        searchTags("hole", "obsidian", "crystal", "fill");
    }

    @Override
    public String getSuffix() {
        return count(holes.size());
    }

    @Override
    protected void onEnable() {
        timer = 0;
        slots.forget();
        enemies.clear();
        holes.clear();
    }

    @Override
    protected void onDisable() {
        slots.restore();
        enemies.clear();
        holes.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        holes.clear();
        if (!inGame() || mc.player.isSpectator()) {
            enemies.clear();
            return;
        }
        boolean everything = !nearEnemies.isOn() || fillKey.isHeld();
        collectEnemies();
        if (!everything && enemies.isEmpty()) {
            slots.restore();
            return;
        }
        collectHoles(everything);
        if (holes.isEmpty()) {
            slots.restore();
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        int slot = BlockUtil.findRankedBlockSlot(blocks.getValue(), block -> true);
        if (slot == -1) {
            slots.restore();
            return;
        }
        slots.select(slot);

        int placed = 0;
        for (BlockPos pos : holes) {
            if (placed >= perTick.getInt()) {
                break;
            }
            if (BlockUtil.placeAny(pos, rotate.isOn(), false)) {
                swing.getValue().swing(InteractionHand.MAIN_HAND);
                placed++;
            }
        }
        if (placed > 0) {
            timer = delay.getInt();
        }
        slots.restore();
    }

    private void collectEnemies() {
        enemies.clear();
        double reach = range.getValue() + targetRange.getValue();
        for (Player player : mc.level.players()) {
            if (!EntityUtil.isEnemy(player) || player.isCreative()) {
                continue;
            }
            if (mc.player.distanceTo(player) > reach) {
                continue;
            }
            if (onlyMoving.isOn() && EntityUtil.velocityOf(player).horizontalDistanceSqr() < 0.0004) {
                continue;
            }
            if (ignoreSafe.isOn() && walledIn(player)) {
                continue;
            }
            enemies.add(player);
        }
    }

    // True when the player already stands in a hole that is blast proof on every side.
    private boolean walledIn(Player player) {
        BlockPos feet = player.blockPosition();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (!blastProof(feet.relative(side))) {
                return false;
            }
        }
        return blastProof(feet.below());
    }

    private void collectHoles(boolean everything) {
        BlockPos own = mc.player.blockPosition();
        // positionsWithin hands them back nearest first. Predicted holes are pulled forward.
        List<BlockPos> soon = new ArrayList<>();
        for (BlockPos pos : BlockUtil.positionsWithin(searchRadius.getValue())) {
            if (ownHole.isOn() && own.distManhattan(pos) <= 1) {
                continue;
            }
            if (!open(pos) || !floored(pos) || !inReach(pos)) {
                continue;
            }
            if (everything) {
                holes.add(pos.immutable());
            } else if (headedFor(pos)) {
                soon.add(pos.immutable());
            } else if (nearAnEnemy(pos)) {
                holes.add(pos.immutable());
            }
        }
        holes.addAll(0, soon);
    }

    private boolean inReach(BlockPos pos) {
        Vec3 top = Vec3.upFromBottomCenterOf(pos, 1);
        double reach = BlockUtil.canSee(top) ? range.getValue() : wallsRange.getValue();
        return BlockUtil.distanceTo(pos) <= reach;
    }

    // Open and empty. A hole with a player in it is not open.
    private boolean open(BlockPos pos) {
        if (!BlockUtil.isReplaceable(pos) || !BlockUtil.state(pos.above()).isAir()) {
            return false;
        }
        return mc.level.isUnobstructed(Blocks.OBSIDIAN.defaultBlockState(), pos, CollisionContext.empty());
    }

    // True when the spot is really a hole.
    private boolean floored(BlockPos pos) {
        if (!BlockUtil.isSolid(pos.below())) {
            return false;
        }
        if (!genuineOnly.isOn()) {
            return true;
        }
        if (!blastProof(pos.below())) {
            return false;
        }
        BlockPos gap = null;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos wall = pos.relative(side);
            if (blastProof(wall)) {
                continue;
            }
            if (gap != null || !doubles.isOn()) {
                return false;
            }
            gap = wall;
        }
        return gap == null || isOtherHalf(gap, pos);
    }

    // The far half of a two wide hole. Walled on its own three sides.
    private boolean isOtherHalf(BlockPos half, BlockPos pos) {
        if (!open(half) || !blastProof(half.below())) {
            return false;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos wall = half.relative(side);
            if (!wall.equals(pos) && !blastProof(wall)) {
                return false;
            }
        }
        return true;
    }

    private boolean blastProof(BlockPos pos) {
        return BlockUtil.isSolid(pos)
            && BlockUtil.state(pos).getBlock().getExplosionResistance() >= BlockUtil.BLAST_PROOF;
    }

    private boolean nearAnEnemy(BlockPos pos) {
        Vec3 top = Vec3.upFromBottomCenterOf(pos, 1);
        for (Player enemy : enemies) {
            if (enemy.position().distanceTo(top) <= targetRange.getValue()) {
                return true;
            }
        }
        return false;
    }

    // True when a moving enemy will be standing on the spot shortly.
    private boolean headedFor(BlockPos pos) {
        if (!predict.isOn()) {
            return false;
        }
        Vec3 top = Vec3.upFromBottomCenterOf(pos, 1);
        double lead = leadTicks.getValue();
        for (Player enemy : enemies) {
            Vec3 pace = EntityUtil.velocityOf(enemy);
            if (pace.horizontalDistanceSqr() < 0.0004) {
                continue;
            }
            Vec3 ahead = enemy.position().add(pace.x * lead, 0, pace.z * lead);
            if (ahead.distanceTo(top) < 1.5) {
                return true;
            }
        }
        return false;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        int next = perTick.getInt();
        for (int i = 0; i < holes.size(); i++) {
            int color = i < next ? nextColor.getColor() : laterColor.getColor();
            boxStyle.draw(batch, holes.get(i), color, false);
        }
    }
}
