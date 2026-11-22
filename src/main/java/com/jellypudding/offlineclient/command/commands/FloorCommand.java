package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Hop;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.OptionalInt;

public final class FloorCommand extends Command {

    private static final double SAME_FLOOR = 0.5;
    private static final int HEIGHT_PLACES = 1;

    private enum Search { UP, DOWN, TOP }

    private final Search search;

    private FloorCommand(String name, String description, String usage, Search search, String... aliases) {
        super(name, description, usage, aliases);
        this.search = search;
    }

    public static FloorCommand up() {
        return new FloorCommand("up", "Takes you up through the ceiling to the next floor.", "up [floors]",
            Search.UP);
    }

    public static FloorCommand down() {
        return new FloorCommand("down", "Takes you down through the floor to the next space below.",
            "down [floors]", Search.DOWN);
    }

    public static FloorCommand top() {
        return new FloorCommand("top", "Takes you onto the highest block above you.", "top", Search.TOP,
            "highest");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        int arguments = search == Search.TOP ? 0 : 1;
        if (player == null || args.length > arguments) {
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
        Entity mover = Hop.mover(player);
        Vec3 spot = search == Search.TOP ? highest(mover) : findFloor(mover, floors);
        if (spot == null) {
            ChatUtil.error("There is no floor " + (search == Search.DOWN ? "below" : "above") + " you.");
            return;
        }
        hopTo(spot, "§7Moved " + (spot.y > mover.getY() ? "up" : "down") + " §b"
            + ChatUtil.decimal(Math.abs(spot.y - mover.getY()), HEIGHT_PLACES) + " §7blocks.");
    }

    private Vec3 findFloor(Entity mover, int floors) {
        int direction = search == Search.UP ? 1 : -1;
        double feet = mover.getY();
        double last = feet;
        int found = 0;
        Level level = mover.level();
        int steps = Math.min(Hop.MAX_TRIP,
            direction > 0 ? level.getMaxY() - Mth.floor(feet) : Mth.floor(feet) - level.getMinY());
        for (int step = 1; step <= steps; step++) {
            double floor = floorUnder(mover, Math.floor(feet) + step * direction);
            if (Double.isNaN(floor) || (floor - feet) * direction < SAME_FLOOR
                || Math.abs(floor - last) < SAME_FLOOR) {
                continue;
            }
            last = floor;
            if (++found == floors) {
                return new Vec3(mover.getX(), floor, mover.getZ());
            }
        }
        return null;
    }

    private static Vec3 highest(Entity mover) {
        double feet = mover.getY();
        int top = Math.min(mover.level().getMaxY(), Mth.floor(feet) + Hop.MAX_TRIP);
        for (int height = top; height > feet; height--) {
            double floor = floorUnder(mover, height);
            if (!Double.isNaN(floor) && floor - feet >= SAME_FLOOR) {
                return new Vec3(mover.getX(), floor, mover.getZ());
            }
        }
        return null;
    }

    // NaN when there is no room or nothing to land on or the landing is lava or powder snow.
    private static double floorUnder(Entity mover, double y) {
        Level level = mover.level();
        AABB box = mover.getBoundingBox().move(0, y - mover.getY(), 0);
        if (!level.noCollision(mover, box)) {
            return Double.NaN;
        }
        double top = Double.NaN;
        for (VoxelShape shape : level.getBlockCollisions(mover, box.expandTowards(0, -1, 0))) {
            double shapeTop = shape.bounds().maxY;
            if (Double.isNaN(top) || shapeTop > top) {
                top = shapeTop;
            }
        }
        if (Double.isNaN(top)) {
            return Double.NaN;
        }
        AABB landed = box.move(0, top - y, 0);
        if (BlockUtil.touchesLava(level, landed) || BlockUtil.touchesPowderSnow(level, landed)
            || !Hop.fits(mover, new Vec3(mover.getX(), top, mover.getZ()))) {
            return Double.NaN;
        }
        return top;
    }
}
