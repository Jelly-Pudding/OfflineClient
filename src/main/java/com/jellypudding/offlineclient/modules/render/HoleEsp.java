package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds one block wide holes with bedrock or obsidian on every side.
 * Those survive crystal blasts.
 */
public final class HoleEsp extends Module {

    private enum Wall {
        BEDROCK(0xFF50FF40),
        OBSIDIAN(0xFFFF4040),
        MIXED(0xFFFFA030);

        private final int color;

        Wall(int color) {
            this.color = color;
        }
    }

    private record Hole(AABB box, Wall wall) {
    }

    private final NumberSetting horizontal = new NumberSetting("Horizontal range",
        "How far sideways to look for holes.", 8, 1, 32, 1, " blocks").max(64);
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
    private final NumberSetting height = new NumberSetting("Height",
        "How tall the drawn box is.", 0.3, 0.1, 1, 0.1).min(0.05);
    private final BoolSetting fill = new BoolSetting("Fill",
        "Adds a faint tint inside each box.", true);
    private final BoolSetting throughWalls = new BoolSetting("Through walls",
        "Show holes behind blocks.", true);

    private final List<Hole> holes = new ArrayList<>();

    public HoleEsp() {
        super("HoleESP", "Highlights safe holes to stand in.", Category.RENDER);
        addSettings(horizontal, vertical, minHeight, doubles, ignoreOwn, webs, height, fill, throughWalls);
        searchTags("bedrock", "obsidian", "crystal");
    }

    @Override
    public String getSuffix() {
        return String.valueOf(holes.size());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        holes.clear();
        if (!inGame()) {
            return;
        }
        BlockPos center = mc.player.blockPosition();
        int h = horizontal.getInt();
        int v = vertical.getInt();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        List<BlockPos> seen = new ArrayList<>();

        for (int dx = -h; dx <= h; dx++) {
            for (int dz = -h; dz <= h; dz++) {
                for (int dy = -v; dy <= v; dy++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!isOpen(cursor)) {
                        continue;
                    }
                    BlockPos pos = cursor.immutable();
                    if (ignoreOwn.isOn() && pos.equals(center)) {
                        continue;
                    }
                    if (seen.contains(pos)) {
                        continue;
                    }
                    check(pos, seen);
                }
            }
        }
    }

    /**
     * Looks at a hole candidate. Every side but the top must be bedrock or
     * obsidian. One side may be another open block for a double hole and
     * then that block's other sides must be safe too.
     */
    private void check(BlockPos pos, List<BlockPos> seen) {
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
                // The other half of a double hole. Its own sides must be safe too.
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

    /** True for a standable spot. The block and the ones above it have no collision. */
    private boolean isOpen(BlockPos pos) {
        int needed = minHeight.getInt();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
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

    /** What kind of wall a block makes. Null for blocks that do not survive a crystal. */
    private Wall wallAt(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        Block block = state.getBlock();
        boolean breakable = block.defaultDestroyTime() >= 0;
        if (!breakable && state.blocksMotion()) {
            return Wall.BEDROCK;
        }
        if (breakable && block.getExplosionResistance() >= 600 && state.blocksMotion()) {
            return Wall.OBSIDIAN;
        }
        return null;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        boolean through = throughWalls.isOn();
        for (Hole hole : holes) {
            batch.outlineBox(hole.box(), hole.wall().color, through);
            if (fill.isOn()) {
                batch.solidBox(hole.box(), ColorUtil.withAlpha(hole.wall().color, 50), through);
            }
        }
    }
}
