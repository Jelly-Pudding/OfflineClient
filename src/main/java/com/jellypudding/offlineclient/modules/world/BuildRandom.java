package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

// Scatters whatever you hold over the ground around you. Fire and lava and spawn
// eggs work as well as blocks once the held item check is off.
public final class BuildRandom extends Module {

    // The vanilla wait between two placements.
    private static final int PLACE_DELAY = 4;

    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes a block may go.", 5, 1, 6, 0.05).min(1).max(6);
    private final NumberSetting attempts = new NumberSetting("Attempts",
        "Random spots tried each tick before giving up. More builds faster and lags more.",
        128, 1, 1024, 1).min(1);
    private final BoolSetting checkItem = new BoolSetting("Check held item",
        "Only places whilst you hold a block.", true);
    private final BoolSetting lineOfSight = new BoolSetting("Line of sight",
        "Never places behind a wall.", false);
    private final EnumSetting<FaceMode> faceTarget = FaceMode.setting(FaceMode.SERVER);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.PACKET);
    private final BoolSetting fastPlace = new BoolSetting("Fast place",
        "Ignores the vanilla wait between placements.", false);
    private final BoolSetting whilstBreaking = new BoolSetting("Whilst breaking",
        "Keeps placing whilst you mine a block. Impossible by hand and easy to spot.", false);
    private final BoolSetting whilstRiding = new BoolSetting("Whilst riding",
        "Keeps placing whilst you ride something. Impossible by hand and easy to spot.", false);
    private final BoolSetting indicator = new BoolSetting("Indicator",
        "Draws a box where the last block was placed.", true);
    private final BoxStyle indicatorStyle = new BoxStyle(BoxStyle.Shape.BOTH, 110);

    private BlockPos lastPlaced;

    public BuildRandom() {
        super("BuildRandom", "Places your held block at random spots around you.", Category.WORLD);
        addSettings(range, attempts, checkItem, lineOfSight, faceTarget, swing, fastPlace,
            whilstBreaking, whilstRiding, indicator);
        addSettings(indicatorStyle.settings());
        searchTags("random build", "place random", "scatter");
    }

    @Override
    protected void onDisable() {
        lastPlaced = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        lastPlaced = null;
        if (!inGame() || (!fastPlace.isOn() && mc.rightClickDelay > 0)) {
            return;
        }
        if (checkItem.isOn() && !(mc.player.getMainHandItem().getItem() instanceof BlockItem)) {
            return;
        }
        if (!whilstBreaking.isOn() && mc.gameMode.isDestroying()) {
            return;
        }
        if (!whilstRiding.isOn() && mc.player.isPassenger()) {
            return;
        }
        BlockPos eyes = BlockPos.containing(mc.player.getEyePosition());
        int reach = (int) Math.ceil(range.getValue());
        int span = reach * 2 + 1;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < attempts.getInt(); i++) {
            BlockPos pos = eyes.offset(random.nextInt(span) - reach, random.nextInt(span) - reach,
                random.nextInt(span) - reach);
            if (tryPlace(pos)) {
                return;
            }
        }
    }

    private boolean tryPlace(BlockPos pos) {
        if (!BlockUtil.isReplaceable(pos)) {
            return false;
        }
        Direction support = BlockUtil.findPlaceSupport(pos);
        if (support == null) {
            return false;
        }
        Vec3 hit = BlockUtil.hitPoint(pos.relative(support), support.getOpposite());
        double limit = range.getValue() * range.getValue();
        if (mc.player.getEyePosition().distanceToSqr(hit) > limit) {
            return false;
        }
        if (lineOfSight.isOn() && !BlockUtil.canSee(hit)) {
            return false;
        }
        faceTarget.getValue().face(hit, RotationPriority.PLACE);
        if (!BlockUtil.place(pos, support, false, false)) {
            return false;
        }
        swing.getValue().swing(InteractionHand.MAIN_HAND);
        mc.rightClickDelay = PLACE_DELAY;
        lastPlaced = pos;
        return true;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (lastPlaced != null && indicator.isOn()) {
            indicatorStyle.draw(event.getBatch(), lastPlaced, false);
        }
    }
}
