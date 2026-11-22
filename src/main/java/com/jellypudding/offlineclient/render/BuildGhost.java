package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.BuildSite;
import com.jellypudding.offlineclient.util.ColorUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// The cells are looked over every few ticks and a block that went down in between drops out of the
// drawing at once.
public final class BuildGhost {

    private static final int RADIUS = 16;
    private static final int REBUILD_TICKS = 10;
    private static final int MOST_DRAWN = 2048;

    private static final int WRONG_COLOR = 0xFFFF4040;
    private static final float WRONG_FILL = 0.3f;
    private static final double BOX_MARGIN = 0.01;

    private enum Kind {
        EMPTY,
        // A block that needs another click such as a second slab.
        SHORT,
        WRONG
    }

    private record Mark(BlockPos pos, Kind kind, int hidden) {
    }

    private List<Mark> marks = List.of();
    private int age = REBUILD_TICKS;

    public void tick(BuildSite site, BuildSite.Rules rules, boolean markSpare) {
        if (++age >= REBUILD_TICKS) {
            rebuild(site, rules, markSpare);
        }
    }

    public void rebuild(BuildSite site, BuildSite.Rules rules, boolean markSpare) {
        age = 0;
        Vec3 eye = OfflineClient.MC.player.getEyePosition();
        Long2ObjectOpenHashMap<Kind> kinds = new Long2ObjectOpenHashMap<>();
        site.forEachWithin(eye, RADIUS, rules, (pos, state) -> {
            Kind kind = switch (state) {
                case MISSING -> OfflineClient.MC.level.getBlockState(pos).canBeReplaced() ? Kind.EMPTY : Kind.SHORT;
                case WRONG -> Kind.WRONG;
                case SPARE -> markSpare ? Kind.WRONG : null;
                default -> null;
            };
            if (kind != null) {
                kinds.put(pos.asLong(), kind);
            }
        });
        List<Mark> found = new ArrayList<>(kinds.size());
        kinds.long2ObjectEntrySet().forEach(entry -> found.add(new Mark(BlockPos.of(entry.getLongKey()),
            entry.getValue(), DrawBatch.sharedSides(kinds, entry.getLongKey(), entry.getValue()))));
        if (found.size() > MOST_DRAWN) {
            found.sort(Comparator.comparingDouble(mark -> mark.pos().distToCenterSqr(eye)));
            marks = new ArrayList<>(found.subList(0, MOST_DRAWN));
        } else {
            marks = found;
        }
    }

    public void draw(DrawBatch batch, BoxStyle style) {
        for (Mark mark : marks) {
            AABB box = DrawBatch.blockBox(mark.pos());
            if (mark.kind() == Kind.WRONG) {
                batch.outlineBoxPart(box, mark.hidden(), WRONG_COLOR, true);
                batch.solidBoxPart(box, mark.hidden(), ColorUtil.fade(WRONG_COLOR, WRONG_FILL), true);
            } else if (mark.kind() == Kind.SHORT
                || OfflineClient.MC.level.getBlockState(mark.pos()).canBeReplaced()) {
                style.drawJoined(batch, box, mark.hidden(), true);
            }
        }
    }

    public static void drawBox(DrawBatch batch, BoxStyle style, BoundingBox box) {
        batch.outlineBox(new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1,
            box.maxZ() + 1).inflate(BOX_MARGIN), style.lineColor(), true);
    }
}
