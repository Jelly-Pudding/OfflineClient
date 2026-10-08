package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.render.Blur;
import com.jellypudding.offlineclient.modules.render.ItemEsp;
import com.jellypudding.offlineclient.modules.render.NoHurtCam;
import com.jellypudding.offlineclient.render.BodyRenderer;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    // Each body the client draws gets a picture beside the game's own. Two bodies in one frame
    // would otherwise share the game's one entity picture.
    @ModifyArg(method = "<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;"
        + "Lnet/minecraft/client/resources/model/ModelManager;Lnet/minecraft/client/renderer/item/ItemModelResolver;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;<init>(Lnet/minecraft/client/renderer/state/gui/GuiRenderState;"
                + "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;Ljava/util/List;)V"),
        index = 2)
    private List<PictureInPictureRenderer<?>> addBodyRenderers(List<PictureInPictureRenderer<?>> renderers) {
        List<PictureInPictureRenderer<?>> all = new ArrayList<>(renderers);
        all.addAll(BodyRenderer.forEveryBody(Minecraft.getInstance().getEntityRenderDispatcher()));
        return all;
    }

    // The whole hurt tilt is one call.
    @Inject(method = "bobHurt(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
        at = @At("HEAD"), cancellable = true)
    private void onBobHurt(CallbackInfo ci) {
        if (Modules.enabled(NoHurtCam.class)) {
            ci.cancel();
        }
    }

    // ItemESP holds the view still to keep its tracers from wobbling.
    @Inject(method = "bobView(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
        at = @At("HEAD"), cancellable = true)
    private void onBobView(CallbackInfo ci) {
        ItemEsp items = Modules.get(ItemEsp.class);
        if (items != null && items.holdsViewStill()) {
            ci.cancel();
        }
    }

    // The menu blur radius comes from the Blur module in place of the video option.
    @ModifyArg(method = "render()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;update(IIDJFILnet/minecraft/world/phys/Vec3;Z)V"),
        index = 5)
    private int onBlurRadius(int radius) {
        Blur blur = Modules.get(Blur.class);
        return blur == null ? radius : blur.radius(radius);
    }
}
