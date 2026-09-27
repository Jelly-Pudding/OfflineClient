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
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

// Marks the block each player stands in the way crystal fighters check who sits safe.
// A hole walled in blast proof blocks and a burrow keep a crystal off the feet.
public final class FeetEsp extends Module {

    private enum Cover { HOLE, BURROWED, OPEN }

    private record Mark(AABB box, Cover cover) {
    }

    // Feet resting on a block top sit this close to it.
    private static final double STANDING_GAP = 1.0E-3;

    private final NumberSetting range = new NumberSetting("Range",
        "How far away a player is marked.", 16, 4, 64, 1, " blocks").min(1);
    private final BoolSetting self = new BoolSetting("Self",
        "Also marks the block you stand in.", false);
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Friends get no mark.", false);
    private final NumberSetting height = new NumberSetting("Height",
        "How tall the drawn box is.", 0.2, 0.05, 1, 0.05).min(0.01);
    private final BoxStyle style = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH);
    private final ColorSetting holeColor = new ColorSetting("Hole colour",
        "Colour under a player walled in by blast proof blocks.", 120, false);
    private final ColorSetting burrowColor = new ColorSetting("Burrowed colour",
        "Colour under a player standing inside a blast proof block.", 280, false);
    private final ColorSetting openColor = new ColorSetting("Open colour",
        "Colour under a player a crystal at the feet can reach.", 0, false);
    private final BoolSetting throughWalls = new BoolSetting("Through walls",
        "Shows the marks behind blocks.", true);

    private final List<Mark> marks = new ArrayList<>();

    public FeetEsp() {
        super("FeetESP", "Marks the block each nearby player stands in and whether it is a safe hole.",
            Category.RENDER);
        addSettings(range, self, ignoreFriends, height);
        addSettings(style.settings());
        addSettings(holeColor, burrowColor, openColor, throughWalls);
        searchTags("feet", "hole", "burrow", "surround", "crystal");
    }

    @Override
    public String getSuffix() {
        return count(marks.size());
    }

    @Override
    protected void onDisable() {
        marks.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        marks.clear();
        if (!inGame()) {
            return;
        }
        for (Player player : mc.level.players()) {
            if (wanted(player)) {
                marks.add(markOf(player));
            }
        }
    }

    private boolean wanted(Player player) {
        if (player == mc.player) {
            return self.isOn();
        }
        if (player.isSpectator() || !player.isAlive() || Modules.isBot(player)
            || mc.player.distanceTo(player) > range.getValue()) {
            return false;
        }
        return !(ignoreFriends.isOn() && EntityUtil.isFriend(player));
    }

    private Mark markOf(Player player) {
        BlockPos feet = player.blockPosition();
        Cover cover = burrowed(player, feet) ? Cover.BURROWED
            : BlockUtil.inHole(feet) ? Cover.HOLE : Cover.OPEN;
        AABB box = new AABB(feet.getX(), feet.getY(), feet.getZ(),
            feet.getX() + 1, feet.getY() + height.getValue(), feet.getZ() + 1);
        return new Mark(box, cover);
    }

    // Standing on top of a low block such as an ender chest is no burrow. The block has
    // to reach above the feet.
    private boolean burrowed(Player player, BlockPos feet) {
        BlockState state = BlockUtil.state(feet);
        if (!BlockUtil.wallOf(state).holds()) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(mc.level, feet);
        return !shape.isEmpty() && feet.getY() + shape.max(Direction.Axis.Y) > player.getY() + STANDING_GAP;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (marks.isEmpty()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        boolean through = throughWalls.isOn();
        for (Mark mark : marks) {
            int color = switch (mark.cover()) {
                case HOLE -> holeColor.getColor();
                case BURROWED -> burrowColor.getColor();
                case OPEN -> openColor.getColor();
            };
            style.draw(batch, mark.box(), color, through);
        }
    }
}
