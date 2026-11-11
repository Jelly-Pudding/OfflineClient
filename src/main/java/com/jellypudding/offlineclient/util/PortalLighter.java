package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// Lights the nether portal frames a build has standing. The game itself says whether a frame is
// whole. A fire in the bottom of it then opens the portal whichever way the player faces.
public final class PortalLighter {

    private static final Minecraft MC = OfflineClient.MC;

    public enum Outcome {
        // No whole frame of a build is in reach.
        NO_FRAME,
        // A frame waits and nothing at hand starts a fire.
        NO_LIGHTER,
        LIT
    }

    private final Ignition ignition;
    private final InventoryUtil.SlotSwap slots = new InventoryUtil.SlotSwap();

    public PortalLighter(Ignition ignition) {
        this.ignition = ignition;
    }

    // Lights one whole frame of the sites within the range of the eyes. The slot the lighter was
    // taken from is given back at once.
    public Outcome light(List<BuildSite> sites, BuildSite.Rules rules, double range, boolean rotate) {
        BlockPos inside = insideOfFrame(sites, rules, range);
        if (inside == null) {
            return Outcome.NO_FRAME;
        }
        if (!ignition.atHand()) {
            return Outcome.NO_LIGHTER;
        }
        ignition.use(slots, hand -> Ignition.strike(Ignition.fireClick(inside, Direction.DOWN), hand, rotate));
        slots.restore();
        return Outcome.LIT;
    }

    // The empty cell on the floor of a whole frame. The floor is obsidian a build wanted and has
    // standing. Null when there is none in reach or the dimension takes no portal.
    private static BlockPos insideOfFrame(List<BuildSite> sites, BuildSite.Rules rules, double range) {
        if (MC.level.dimension() != Level.OVERWORLD && MC.level.dimension() != Level.NETHER) {
            return null;
        }
        Vec3 eye = MC.player.getEyePosition();
        BlockPos[] found = {null};
        for (BuildSite site : sites) {
            if (!site.mayHold(Blocks.OBSIDIAN)) {
                continue;
            }
            site.forEachWithin(eye, range, rules, (pos, state) -> {
                if (found[0] != null || state != BuildSite.State.DONE || !BlockUtil.state(pos).is(Blocks.OBSIDIAN)
                    || !BlockUtil.reaches(pos, range)) {
                    return;
                }
                BlockPos above = pos.above();
                if (BlockUtil.state(above).isAir()
                    && PortalShape.findEmptyPortalShape(MC.level, above, Direction.Axis.X).isPresent()) {
                    found[0] = above;
                }
            });
        }
        return found[0];
    }
}
