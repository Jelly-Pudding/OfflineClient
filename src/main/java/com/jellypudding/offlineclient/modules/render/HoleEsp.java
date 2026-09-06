package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Finds one block wide holes walled in bedrock or obsidian. Those survive crystal blasts.
public final class HoleEsp extends Module {

    private enum Wall {
        BEDROCK,
        OBSIDIAN,
        MIXED
    }

    private record Hole(AABB box, Wall wall) {
    }

    // Reused by every column test. One scan probes thousands of positions.
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos under = new BlockPos.MutableBlockPos();

    private final NumberSetting horizontal = new NumberSetting("Horizontal range",
        "How far sideways to look for holes.", 8, 1, 16, 1, " blocks").max(32);
    private final NumberSetting vertical = new NumberSetting("Vertical range",
        "How far up and down to look for holes.", 4, 1, 16, 1, " blocks").max(64);
    private final NumberSetting minHeight = new NumberSetting("Min height",
        "How many air blocks the hole must have above its floor.", 3, 1, 5, 1).min(1);
    private final BoolSetting doubles = new BoolSetting("Doubles",
        "Also show two block wide holes you can stand across.", true);
    private final BoolSetting ignoreOwn = new BoolSetting("Ignore own",
        "Hide the hole you are standing in.", false);
    private final BoolSetting webs = new BoolSetting("Webs",
        "Count holes that have cobwebs in them.", false);
    private final NumberSetting refresh = new NumberSetting("Refresh",
        "Ticks between scans.", 2, 1, 20, 1, " ticks").min(1);
    private final NumberSetting height = new NumberSetting("Height",
        "How tall the drawn box is.", 0.3, 0.1, 1, 0.1).min(0.05);
    private final BoxStyle style = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH);
    private final BoolSetting topFace = new BoolSetting("Top face",
        "Tint the top of each box.", true).visibleWhen(style::drawsSides);
    private final BoolSetting bottomFace = new BoolSetting("Bottom face",
        "Tint the floor of each box.", false).visibleWhen(style::drawsSides);
    private final ColorSetting bedrockTop = new ColorSetting("Bedrock top",
        "Colour at the top of a bedrock hole.", 96, 1f, 1f, false);
    private final ColorSetting bedrockBottom = new ColorSetting("Bedrock bottom",
        "Colour the sides of a bedrock hole fade down to.", 96, 1f, 1f, false);
    private final ColorSetting obsidianTop = new ColorSetting("Obsidian top",
        "Colour at the top of an obsidian hole.", 0, 1f, 1f, false);
    private final ColorSetting obsidianBottom = new ColorSetting("Obsidian bottom",
        "Colour the sides of an obsidian hole fade down to.", 0, 1f, 1f, false);
    private final ColorSetting mixedTop = new ColorSetting("Mixed top",
        "Colour at the top of a hole walled in bedrock and obsidian.", 30, 1f, 1f, false);
    private final ColorSetting mixedBottom = new ColorSetting("Mixed bottom",
        "Colour the sides of a mixed hole fade down to.", 30, 1f, 1f, false);
    private final BoolSetting throughWalls = new BoolSetting("Through walls",
        "Show holes behind blocks.", true);

    private final List<Hole> holes = new ArrayList<>();
    private int timer;

    public HoleEsp() {
        super("HoleESP", "Highlights safe holes to stand in.", Category.RENDER);
        addSettings(horizontal, vertical, minHeight, doubles, ignoreOwn, webs, refresh, height);
        addSettings(style.settings());
        addSettings(topFace, bottomFace, bedrockTop, bedrockBottom, obsidianTop, obsidianBottom,
            mixedTop, mixedBottom, throughWalls);
        searchTags("bedrock", "obsidian", "crystal");
    }

    @Override
    public String getSuffix() {
        return count(holes.size());
    }

    @Override
    protected void onEnable() {
        timer = 0;
        holes.clear();
    }

    @Override
    protected void onDisable() {
        holes.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            holes.clear();
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        timer = refresh.getInt();
        scan();
    }

    private void scan() {
        holes.clear();
        BlockPos center = mc.player.blockPosition();
        int h = horizontal.getInt();
        int v = vertical.getInt();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Set<BlockPos> seen = new HashSet<>();

        for (int dx = -h; dx <= h; dx++) {
            for (int dz = -h; dz <= h; dz++) {
                for (int dy = -v; dy <= v; dy++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    // A blast proof floor is rare.
                    if (wallAt(under.setWithOffset(cursor, Direction.DOWN)) == null) {
                        continue;
                    }
                    if (!isOpen(cursor)) {
                        continue;
                    }
                    if ((ignoreOwn.isOn() && cursor.equals(center)) || seen.contains(cursor)) {
                        continue;
                    }
                    check(cursor.immutable(), seen);
                }
            }
        }
    }

    // Every side but the top must be bedrock or obsidian.
    // One side may be an open block for a double hole if its own sides are safe too.
    private void check(BlockPos pos, Set<BlockPos> seen) {
        int bedrock = 0;
        int obsidian = 0;
        BlockPos partner = null;

        for (Direction side : Direction.values()) {
            if (side == Direction.UP) {
                continue;
            }
            BlockPos next = pos.relative(side);
            Wall wall = wallAt(next);
            if (wall == Wall.BEDROCK) {
                bedrock++;
            } else if (wall == Wall.OBSIDIAN) {
                obsidian++;
            } else if (side == Direction.DOWN || partner != null || !doubles.isOn() || !isOpen(next)) {
                return;
            } else {
                // The other half of a double hole.
                for (Direction other : Direction.values()) {
                    if (other == Direction.UP || other == side.getOpposite()) {
                        continue;
                    }
                    Wall otherWall = wallAt(next.relative(other));
                    if (otherWall == Wall.BEDROCK) {
                        bedrock++;
                    } else if (otherWall == Wall.OBSIDIAN) {
                        obsidian++;
                    } else {
                        return;
                    }
                }
                partner = next;
            }
        }

        int total = bedrock + obsidian;
        if (partner == null && total != 5) {
            return;
        }
        if (partner != null && total != 8) {
            return;
        }

        Wall kind = obsidian == 0 ? Wall.BEDROCK : bedrock == 0 ? Wall.OBSIDIAN : Wall.MIXED;
        double top = height.getValue();
        AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
            pos.getX() + 1, pos.getY() + top, pos.getZ() + 1);
        if (partner != null) {
            box = box.minmax(new AABB(partner.getX(), partner.getY(), partner.getZ(),
                partner.getX() + 1, partner.getY() + top, partner.getZ() + 1));
            seen.add(partner);
        }
        seen.add(pos);
        holes.add(new Hole(box, kind));
    }

    // The block and the ones above it must have no collision.
    private boolean isOpen(BlockPos pos) {
        int needed = minHeight.getInt();
        BlockPos.MutableBlockPos cursor = probe;
        for (int i = 0; i < needed; i++) {
            cursor.set(pos.getX(), pos.getY() + i, pos.getZ());
            BlockState state = mc.level.getBlockState(cursor);
            if (state.getBlock() == Blocks.COBWEB) {
                if (!webs.isOn()) {
                    return false;
                }
                continue;
            }
            if (!state.getCollisionShape(mc.level, cursor).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // Null for blocks that do not survive a crystal.
    private Wall wallAt(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        Block block = state.getBlock();
        boolean breakable = block.defaultDestroyTime() >= 0;
        if (!breakable && BlockUtil.blocksMotion(state)) {
            return Wall.BEDROCK;
        }
        if (breakable && block.getExplosionResistance() >= BlockUtil.BLAST_PROOF && BlockUtil.blocksMotion(state)) {
            return Wall.OBSIDIAN;
        }
        return null;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        boolean through = throughWalls.isOn();
        float share = style.fillShare();
        for (Hole hole : holes) {
            int top = topColor(hole.wall());
            int bottom = bottomColor(hole.wall());
            AABB box = hole.box();
            if (style.drawsSides()) {
                batch.gradientSides(box, ColorUtil.fade(bottom, 0), ColorUtil.fade(top, share), through);
                if (topFace.isOn()) {
                    batch.solidFace(box, Direction.UP, ColorUtil.fade(top, share), through);
                }
                if (bottomFace.isOn()) {
                    batch.solidFace(box, Direction.DOWN, ColorUtil.fade(bottom, share), through);
                }
            }
            if (style.drawsLines()) {
                batch.outlineBox(box, top, through);
                batch.outlineFace(box, Direction.DOWN, bottom, through);
            }
        }
    }

    private int topColor(Wall wall) {
        return switch (wall) {
            case BEDROCK -> bedrockTop.getColor();
            case OBSIDIAN -> obsidianTop.getColor();
            case MIXED -> mixedTop.getColor();
        };
    }

    private int bottomColor(Wall wall) {
        return switch (wall) {
            case BEDROCK -> bedrockBottom.getColor();
            case OBSIDIAN -> obsidianBottom.getColor();
            case MIXED -> mixedBottom.getColor();
        };
    }
}
