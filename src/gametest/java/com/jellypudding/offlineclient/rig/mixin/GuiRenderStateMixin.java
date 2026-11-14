package com.jellypudding.offlineclient.rig.mixin;

import com.jellypudding.offlineclient.rig.core.FrameTap;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Lets the rig read what a frame of the interface holds.
@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateMixin {

    @Inject(method = "addText", at = @At("HEAD"))
    private void rigSeesText(GuiTextRenderState text, CallbackInfo ci) {
        FrameTap.text(text);
    }

    @Inject(method = "addGuiElement", at = @At("HEAD"))
    private void rigSeesElement(GuiElementRenderState element, CallbackInfo ci) {
        FrameTap.element(element);
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void rigSeesFrameEnd(CallbackInfo ci) {
        FrameTap.frameEnded();
    }
}
