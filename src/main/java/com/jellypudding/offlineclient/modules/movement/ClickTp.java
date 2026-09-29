package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.LeftClickEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Hop;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

// Teleports you onto the block you click. A long way is covered a hop at a time and a
// wall on the way is climbed over. A spot with no room gives way to the nearest one.
public final class ClickTp extends Module {

    public enum Trigger { USE, ATTACK, BOTH }

    private static final float LANDING_HUE = 120;
    private static final float BLOCKED_HUE = 0;

    private final NumberSetting range = new NumberSetting("Range",
        "How far away the clicked block may be.", 100, 1, 200, 1, " blocks");
    private final EnumSetting<Trigger> trigger = new EnumSetting<>("Click",
        "Which mouse button teleports you.", Trigger.USE)
        .describe(Trigger.USE, "The use button. A door or a chest or a block in your hand keeps its meaning.")
        .describe(Trigger.ATTACK, "The attack button. A hit on a mob or a player still lands.")
        .describe(Trigger.BOTH, "Either button.");
    private final BoolSetting water = new BoolSetting("Land on water",
        "Stops on the surface of water instead of sinking to the bottom.", false);
    private final BoolSetting showLanding = new BoolSetting("Show landing",
        "Outlines where a click would put you.", true);
    private final BoxStyle landingStyle = new BoxStyle("Landing", BoxStyle.Shape.LINES, LANDING_HUE)
        .under(showLanding);
    private final BoxStyle blockedStyle = new BoxStyle("Blocked", BoxStyle.Shape.LINES, BLOCKED_HUE)
        .under(showLanding);

    // Where a click lands. The spot is null when nothing near the aim has room.
    private record Landing(Vec3 aimed, Vec3 spot) {
    }

    private Landing preview;

    public ClickTp() {
        super("ClickTp", "Teleports you to the block you click.", Category.MOVEMENT);
        addSettings(range, trigger, water, showLanding);
        addSettings(landingStyle.settings());
        addSettings(blockedStyle.settings());
        searchTags("teleport", "click teleport", "tp", "fluids");
    }

    @Override
    protected void onDisable() {
        preview = null;
    }

    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (!event.isCancelled() && trigger.isAny(Trigger.USE, Trigger.BOTH) && ready() && useIsFree()
            && teleport(false)) {
            event.cancel();
        }
    }

    @Subscribe
    private void onLeftClick(LeftClickEvent event) {
        if (!event.isCancelled() && trigger.isAny(Trigger.ATTACK, Trigger.BOTH) && ready() && !onEntity()
            && teleport(true)) {
            event.cancel();
        }
    }

    // True when the click was taken for a teleport.
    private boolean teleport(boolean attack) {
        Landing landing = landing(attack);
        if (landing == null) {
            return false;
        }
        if (landing.spot() == null) {
            ChatUtil.error("There is no room to stand there.");
            return true;
        }
        Hop.travel(landing.spot(), result -> {
            if (result != Hop.Result.MOVED) {
                ChatUtil.error(result.problem());
            }
        });
        return true;
    }

    private boolean ready() {
        return inGame() && !mc.player.isDeadOrDying() && !Hop.travelling() && mc.gui.screen() == null;
    }

    // A use on an entity or a placement or an item such as food keeps its normal meaning.
    private boolean useIsFree() {
        if (onEntity() || mc.player.getMainHandItem().getUseAnimation() != ItemUseAnimation.NONE) {
            return false;
        }
        return !(mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && mc.player.getMainHandItem().getItem() instanceof BlockItem);
    }

    private boolean onEntity() {
        return mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY;
    }

    // The preview follows whichever button would teleport now.
    @Subscribe
    private void onTick(TickEvent event) {
        if (!showLanding.isOn() || !ready() || onEntity()) {
            preview = null;
            return;
        }
        boolean attack = trigger.isAny(Trigger.ATTACK, Trigger.BOTH);
        boolean use = trigger.isAny(Trigger.USE, Trigger.BOTH) && useIsFree();
        preview = attack || use ? landing(attack) : null;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        Landing shown = preview;
        if (shown == null || mc.player == null) {
            return;
        }
        Entity mover = Hop.mover(mc.player);
        if (shown.spot() == null) {
            blockedStyle.draw(event.getBatch(), Hop.boxAt(mover, shown.aimed()), false);
        } else {
            landingStyle.draw(event.getBatch(), Hop.boxAt(mover, shown.spot()), false);
        }
    }

    // Where a click would put the mover. Null when the click keeps its own meaning.
    private Landing landing(boolean attack) {
        BlockHitResult hit = lookRay();
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        // A right click on a door or a chest keeps its normal meaning.
        if (!attack && BlockUtil.opensOnClick(state)) {
            return null;
        }
        Direction side = hit.getDirection();
        Vec3 aimed = new Vec3(pos.getX() + 0.5 + side.getStepX(), pos.getY() + top(pos, state),
            pos.getZ() + 0.5 + side.getStepZ());
        return new Landing(aimed, Hop.nearestFit(Hop.mover(mc.player), aimed));
    }

    // The height a mover stands at on the block. Water with nothing solid in it holds it
    // at the surface.
    private double top(BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(mc.level, pos);
        if (shape.isEmpty() && water.isOn() && !state.getFluidState().isEmpty()) {
            return state.getFluidState().getHeight(mc.level, pos);
        }
        if (shape.isEmpty()) {
            shape = state.getShape(mc.level, pos);
        }
        return shape.isEmpty() ? 1 : shape.max(Direction.Axis.Y);
    }

    private BlockHitResult lookRay() {
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 from = camera.position();
        Vec3 to = from.add(Vec3.directionFromRotation(camera.xRot(), camera.yRot())
            .scale(range.getValue()));
        ClipContext.Fluid fluid = water.isOn() ? ClipContext.Fluid.WATER : ClipContext.Fluid.NONE;
        return mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, fluid, mc.player));
    }
}
