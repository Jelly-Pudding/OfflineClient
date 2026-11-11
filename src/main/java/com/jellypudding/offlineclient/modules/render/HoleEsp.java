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
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Holes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Finds one block wide holes walled in bedrock or obsidian. Those survive crystal blasts.
public final class HoleEsp extends Module {

    private enum Kind {
        BEDROCK,
        OBSIDIAN,
        MIXED
    }

    private record Hole(AABB box, Kind kind) {
    }

    // Reused by every column test. One scan probes thousands of positions.
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos under = new BlockPos.MutableBlockPos();

    private final NumberSetting horizontal = new NumberSetting("Horizontal range",
        "How far sideways to look for holes.", 8, 1, 16, 1, " blocks");
    private final NumberSetting vertical = new NumberSetting("Vertical range",
        "How far up and down to look for holes.", 4, 1, 16, 1, " blocks");
    private final NumberSetting minHeight = new NumberSetting("Min height",
        "How many air blocks the hole must have above its floor.", 3, 1, 5, 1);
    private final BoolSetting doubles = new BoolSetting("Doubles",
        "Also shows two block wide holes you can stand across.", true);
    private final BoolSetting ignoreOwn = new BoolSetting("Ignore own",
        "Hides the hole you are standing in.", false);
    private final BoolSetting webs = new BoolSetting("Webs",
        "Counts holes that have cobwebs in them.", false);
    private final NumberSetting refresh = new NumberSetting("Refresh",
        "Ticks between scans.", 2, 1, 20, 1, " ticks");
    private final NumberSetting height = new NumberSetting("Height",
        "How tall the drawn box is.", 0.3, 0.1, 1, 0.1).min(0.05);
    private final BoxStyle style = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH);
    private final BoolSetting topFace = new BoolSetting("Top face",
        "Tints the top of each box.", true).visibleWhen(style::drawsSides);
    private final BoolSetting bottomFace = new BoolSetting("Bottom face",
        "Tints the floor of each box.", false).visibleWhen(style::drawsSides);
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
        "Shows holes behind blocks.", true);

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

    private void check(BlockPos pos, Set<BlockPos> seen) {
        List<BlockPos> cells = Holes.hole(pos, wall -> wallAt(wall) != null, this::isOpen, doubles.isOn());
        if (cells.isEmpty()) {
            return;
        }
        seen.addAll(cells);
        AABB box = AABB.encapsulatingFullBlocks(cells.getFirst(), cells.getLast())
            .setMaxY(pos.getY() + height.getValue());
        holes.add(new Hole(box, kindOf(cells)));
    }

    private Kind kindOf(List<BlockPos> cells) {
        List<BlockPos> walls = new ArrayList<>(Holes.ringAround(cells));
        cells.forEach(cell -> walls.add(cell.below()));
        long bedrock = walls.stream().filter(wall -> wallAt(wall) == Kind.BEDROCK).count();
        return bedrock == walls.size() ? Kind.BEDROCK : bedrock == 0 ? Kind.OBSIDIAN : Kind.MIXED;
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
    private Kind wallAt(BlockPos pos) {
        return switch (Holes.wallOf(mc.level.getBlockState(pos))) {
            case UNBREAKABLE -> Kind.BEDROCK;
            case BLAST_PROOF -> Kind.OBSIDIAN;
            case OPEN, WEAK -> null;
        };
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        boolean through = throughWalls.isOn();
        float share = style.fillShare();
        for (Hole hole : holes) {
            int top = topColor(hole.kind());
            int bottom = bottomColor(hole.kind());
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

    private int topColor(Kind wall) {
        return switch (wall) {
            case BEDROCK -> bedrockTop.getColor();
            case OBSIDIAN -> obsidianTop.getColor();
            case MIXED -> mixedTop.getColor();
        };
    }

    private int bottomColor(Kind wall) {
        return switch (wall) {
            case BEDROCK -> bedrockBottom.getColor();
            case OBSIDIAN -> obsidianBottom.getColor();
            case MIXED -> mixedBottom.getColor();
        };
    }
}
