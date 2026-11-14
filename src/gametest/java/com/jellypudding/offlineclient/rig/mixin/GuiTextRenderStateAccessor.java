package com.jellypudding.offlineclient.rig.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GuiTextRenderState.class)
public interface GuiTextRenderStateAccessor {

    @Accessor("font")
    Font rigFont();

    @Accessor("text")
    FormattedCharSequence rigText();

    @Accessor("x")
    int rigX();

    @Accessor("y")
    int rigY();

    @Accessor("color")
    int rigColor();
}
