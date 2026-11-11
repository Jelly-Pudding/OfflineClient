package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

// Standing inside a box given by two of its corners. A corner names a block and both edges
// count as inside.
final class Region extends Condition {

    private static final int POINT = 3;

    private final TextSetting first;
    private final TextSetting second;
    private final TypedNumbers from;
    private final TypedNumbers to;
    private final EnumSetting<Within> within;

    Region(Frame frame) {
        super(frame, "Inside a box");
        first = add(new TextSetting(frame.name("Inside a box"),
            "You stand inside the box that runs from this corner to the other one. Click to type it as x y z.",
            "").secret());
        second = add(new TextSetting(frame.name("Box other corner"),
            "The corner across the box from the first. Click to type it as x y z.", "").secret());
        from = new TypedNumbers(first, POINT, POINT);
        to = new TypedNumbers(second, POINT, POINT);
        within = add(Within.row(frame.name("Box dimension")));
        add(fromHere(frame.name("Box corner here"),
            "Writes the block you stand in and the dimension you are in into the first corner.",
            () -> writeHere(first, within)));
        add(fromHere(frame.name("Box other corner here"),
            "Writes the block you stand in and the dimension you are in into the other corner.",
            () -> writeHere(second, within)));
    }

    // Null whilst either corner is not three numbers.
    private BoundingBox box() {
        double[] a = from.get();
        double[] b = to.get();
        return a == null || b == null ? null
            : BoundingBox.fromCorners(BlockPos.containing(a[0], a[1], a[2]), BlockPos.containing(b[0], b[1], b[2]));
    }

    @Override
    public boolean holds() {
        BoundingBox box = box();
        return box != null && within.getValue().isHere() && box.isInside(mc.player.blockPosition());
    }

    @Override
    public String reason() {
        return "You were inside the box from " + first.getValue().trim() + " to " + second.getValue().trim() + ".";
    }

    @Override
    public String problem() {
        return box() == null ? "Inside a box needs both corners typed as x y z." : null;
    }
}
