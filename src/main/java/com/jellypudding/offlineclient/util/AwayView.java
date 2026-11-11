package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.render.Freecam;
import com.jellypudding.offlineclient.modules.render.RemoteView;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// A view that has left the player's eyes. Freecam flies one about and RemoteView rides one.
// The hooks for the camera and the hand and the crosshair ask here for whichever is on.
public interface AwayView {

    enum InteractFrom { PLAYER, CAMERA }

    static EnumSetting<InteractFrom> interactFromSetting() {
        return new EnumSetting<>("Interact from",
            "Where clicks are aimed from.", InteractFrom.PLAYER)
            .describe(InteractFrom.PLAYER, "Clicks go where your body looks.")
            .describe(InteractFrom.CAMERA,
                "Clicks go where the camera looks. The server still checks reach from your body.");
    }

    static BoolSetting hideHandSetting() {
        return new BoolSetting("Hide hand",
            "Hides the held item whilst the camera is away.", true);
    }

    // The view that is away. Null whilst you look out of your own eyes. Asked several times
    // a frame.
    static AwayView active() {
        Freecam freecam = Modules.active(Freecam.class);
        return freecam != null ? freecam : Modules.active(RemoteView.class);
    }

    // The player's own model is only on screen in third person or whilst a view is away.
    static boolean ownBodyVisible() {
        return active() != null || !OfflineClient.MC.options.getCameraType().isFirstPerson();
    }

    // Only one view can be away at a time. The one switched on last sends the other home.
    static void claim(AwayView taker) {
        AwayView other = taker instanceof Freecam ? Modules.active(RemoteView.class) : Modules.active(Freecam.class);
        if (other != null) {
            other.setEnabled(false);
        }
    }

    boolean isEnabled();

    void setEnabled(boolean enabled);

    Vec3 getCamPos(float partialTicks);

    float getCamYaw();

    float getCamPitch();

    boolean hidesHand();

    boolean interactsFromCamera();

    // The entity the camera sits inside. The crosshair ray starts in its hitbox and has to
    // skip it. Null for a camera that floats free.
    default Entity carrier() {
        return null;
    }

    // The crosshair ray cast from the camera instead of the eyes. Blocks and
    // entities use their own reach and the nearer hit wins like vanilla.
    default HitResult pick(LocalPlayer player, float partialTicks) {
        Vec3 start = getCamPos(partialTicks);
        Vec3 direction = Vec3.directionFromRotation(getCamPitch(), getCamYaw());

        double blockRange = player.blockInteractionRange();
        HitResult result = OfflineClient.MC.level.clip(new CrosshairClip(start, start.add(direction.scale(blockRange)),
            ClipContext.Fluid.NONE, player));

        double entityRange = player.entityInteractionRange();
        Vec3 entityEnd = start.add(direction.scale(entityRange));
        AABB bounds = new AABB(start, entityEnd).inflate(1);
        Entity carrier = carrier();
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(player, start, entityEnd, bounds,
            entity -> entity != carrier && !entity.isSpectator() && entity.isPickable(), entityRange * entityRange);
        if (entityHit == null) {
            return result;
        }
        boolean nearer = result.getType() == HitResult.Type.MISS
            || entityHit.getLocation().distanceToSqr(start) < result.getLocation().distanceToSqr(start);
        return nearer ? entityHit : result;
    }
}
