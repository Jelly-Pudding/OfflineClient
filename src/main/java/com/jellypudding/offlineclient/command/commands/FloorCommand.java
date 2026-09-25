package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Hop;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.math.BigDecimal;
import java.util.OptionalInt;

// Takes the player through the ceiling or the floor to the next place to stand.
public final class FloorCommand extends Command {

    // A floor this close to the last one found is the same floor.
    private static final double SAME_FLOOR = 0.5;

    private final int direction;
    private final String way;

    private FloorCommand(String name, String description, int direction, String way) {
        super(name, description, name + " [floors]");
        this.direction = direction;
        this.way = way;
    }

    public static FloorCommand up() {
        return new FloorCommand("up", "Takes you up through the ceiling to the next floor.", 1, "above");
    }

    public static FloorCommand down() {
        return new FloorCommand("down", "Takes you down through the floor to the next space below.", -1,
            "below");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || args.length > 1) {
            usage();
            return;
        }
        int floors = 1;
        if (args.length == 1) {
            OptionalInt wanted = wholeNumber(args[0]);
            if (wanted.isEmpty()) {
                return;
            }
            if (wanted.getAsInt() < 1) {
                ChatUtil.error("Pick one floor or more.");
                return;
            }
            floors = wanted.getAsInt();
        }
        Vec3 spot = findFloor(player, floors);
        if (spot == null) {
            ChatUtil.error("There is no floor " + way + " you.");
            return;
        }
        hopTo(spot, "§7Moved " + (direction > 0 ? "up" : "down") + " §b"
            + plain(Math.abs(spot.y - player.getY())) + " §7blocks.");
    }

    // The chosen floor in the direction of travel. Null when the world runs out first.
    private Vec3 findFloor(LocalPlayer player, int floors) {
        double feet = player.getY();
        double last = feet;
        int found = 0;
        Level level = player.level();
        int steps = Math.min(Hop.MAX_TRIP,
            direction > 0 ? level.getMaxY() - Mth.floor(feet) : Mth.floor(feet) - level.getMinY());
        for (int step = 1; step <= steps; step++) {
            double floor = floorUnder(player, Math.floor(feet) + step * direction);
            if (Double.isNaN(floor) || (floor - feet) * direction < SAME_FLOOR
                || Math.abs(floor - last) < SAME_FLOOR) {
                continue;
            }
            last = floor;
            if (++found == floors) {
                return new Vec3(player.getX(), floor, player.getZ());
            }
        }
        return null;
    }

    // Where the box comes to rest when dropped from the given height by up to one
    // block. NaN when it has no room there or nothing to land on or would land in lava.
    private static double floorUnder(LocalPlayer player, double y) {
        Level level = player.level();
        AABB box = player.getBoundingBox().move(0, y - player.getY(), 0);
        if (!level.noCollision(player, box)) {
            return Double.NaN;
        }
        double top = Double.NaN;
        for (VoxelShape shape : level.getBlockCollisions(player, box.expandTowards(0, -1, 0))) {
            double shapeTop = shape.bounds().maxY;
            if (Double.isNaN(top) || shapeTop > top) {
                top = shapeTop;
            }
        }
        if (Double.isNaN(top) || touchesLava(level, box.move(0, top - y, 0))) {
            return Double.NaN;
        }
        return top;
    }

    // A whole number reads without its point and anything else keeps one place.
    private static String plain(double blocks) {
        return BigDecimal.valueOf(Math.round(blocks * 10) / 10.0).stripTrailingZeros().toPlainString();
    }

    private static boolean touchesLava(Level level, AABB box) {
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(box.minX), Mth.floor(box.minY),
            Mth.floor(box.minZ), Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ))) {
            if (level.getFluidState(pos).is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }
}
