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
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.Holes;
import com.jellypudding.offlineclient.util.PlacingRounds;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// A hole with somebody already in it cannot be filled since the server
// refuses a block inside a player. HoleFiller seals a hole before an enemy reaches it.
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
        "Only fills holes an enemy could reach. Off fills every hole in range.", true);
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
    private final PlacingRounds rounds = new PlacingRounds(1);
    private final BoolSetting genuineOnly = new BoolSetting("Genuine holes",
        "Only fills holes that are blast proof on every side.", true);
    private final BoolSetting doubles = new BoolSetting("Two wide holes",
        "Also counts a hole whose one open side is the other half of a two wide hole.", true)
        .under(genuineOnly);
    private final BoolSetting ownHole = new BoolSetting("Keep own hole",
        "Never fills the hole you stand in or next to.", true);
    private final BoolSetting swing = SwingMode.useSetting(true);
    private final BoolSetting render = new BoolSetting("Render",
        "Draws the holes waiting to be filled.", true);
    private final BoxStyle boxStyle = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH).under(render);
    private final ColorSetting nextColor = new ColorSetting("Next colour",
        "The holes going in this round.", 270, false).under(render);
    private final ColorSetting laterColor = new ColorSetting("Later colour",
        "The holes still waiting.", 270, 0.4f, 0.9f, false).under(render);

    private final List<Player> enemies = new ArrayList<>();
    private final List<BlockPos> holes = new ArrayList<>();

    public HoleFiller() {
        super("HoleFiller", "Seals the holes around an enemy before they can hide in one.", Category.COMBAT);
        addSettings(range, wallsRange, searchRadius, blocks, nearEnemies, targetRange, predict,
            leadTicks, ignoreSafe, onlyMoving, fillKey, rounds.perRound(), rounds.delay(), genuineOnly, doubles,
            ownHole, rounds.rotate(), swing, render);
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
        rounds.forget();
        enemies.clear();
        holes.clear();
    }

    @Override
    protected void onDisable() {
        rounds.restore();
        enemies.clear();
        holes.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        holes.clear();
        if (Hands.tied()) {
            enemies.clear();
            return;
        }
        boolean everything = !nearEnemies.isOn() || fillKey.isHeld();
        collectEnemies();
        if (!everything && enemies.isEmpty()) {
            rounds.restore();
            return;
        }
        collectHoles(everything);
        rounds.place(holes, blocks, SwingMode.of(swing.isOn()));
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
            if (onlyMoving.isOn() && !EntityUtil.isMoving(EntityUtil.velocityOf(player))) {
                continue;
            }
            if (ignoreSafe.isOn() && walledIn(player)) {
                continue;
            }
            enemies.add(player);
        }
    }

    // True when the player already stands in a one block hole that is blast proof on every side.
    private boolean walledIn(Player player) {
        return !Holes.hole(player.blockPosition(), this::blastProof, this::open, false).isEmpty();
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
        return BlockUtil.distanceTo(pos)
            <= BlockUtil.reachFor(Vec3.upFromBottomCenterOf(pos, 1), range.getValue(), wallsRange.getValue());
    }

    // Open and empty. A hole with a player in it is not open.
    private boolean open(BlockPos pos) {
        return BlockUtil.state(pos.above()).isAir() && BlockUtil.blockFits(pos);
    }

    // True when the spot is really a hole.
    private boolean floored(BlockPos pos) {
        if (!genuineOnly.isOn()) {
            return BlockUtil.isSolid(pos.below());
        }
        return !Holes.hole(pos, this::blastProof, this::open, doubles.isOn()).isEmpty();
    }

    private boolean blastProof(BlockPos pos) {
        return BlockUtil.isSolid(pos)
            && BlockUtil.isBlastProof(pos);
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
            if (!EntityUtil.isMoving(pace)) {
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
        int next = rounds.perRound().getInt();
        for (int i = 0; i < holes.size(); i++) {
            int color = i < next ? nextColor.getColor() : laterColor.getColor();
            boxStyle.draw(batch, holes.get(i), color, false);
        }
    }
}
