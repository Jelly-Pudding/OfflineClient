package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.player.ChestStealer;
import com.jellypudding.offlineclient.modules.player.InventoryTweaks;
import com.jellypudding.offlineclient.modules.render.BetterTooltips;
import com.jellypudding.offlineclient.modules.render.ItemHighlight;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin extends Screen {

    private static final int BUTTON_WIDTH = 40;
    private static final int BUTTON_HEIGHT = 14;

    private AbstractContainerScreenMixin(Component title) {
        super(title);
    }

    @Shadow
    protected Slot hoveredSlot;

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Shadow
    public abstract AbstractContainerMenu getMenu();

    // Two buttons above the container that run one pass on click.
    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        ChestStealer stealer = Modules.get(ChestStealer.class);
        if (stealer == null || !stealer.showsButtons()
            || !stealer.handles((Screen) (Object) this)) {
            return;
        }
        int y = topPos - BUTTON_HEIGHT - 2;
        addRenderableWidget(Button.builder(Component.literal("Steal"),
                button -> stealer.stealNow())
            .bounds(leftPos, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.literal("Dump"),
                button -> stealer.dumpNow())
            .bounds(leftPos + BUTTON_WIDTH + 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    // The paint goes down before the item and sits under it.
    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void onExtractSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
                               CallbackInfo ci) {
        ItemHighlight highlight = Modules.get(ItemHighlight.class);
        if (highlight == null) {
            return;
        }
        int color = highlight.colorFor(slot.getItem());
        if (color != 0) {
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color);
        }
    }

    @Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
        at = @At("HEAD"), cancellable = true)
    private void onMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        BetterTooltips tooltips = Modules.get(BetterTooltips.class);
        if (tooltips != null && tooltips.wantsToOpen(event) && openHovered(tooltips)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
        at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        BetterTooltips tooltips = Modules.get(BetterTooltips.class);
        if (tooltips != null && tooltips.wantsToOpen(event) && openHovered(tooltips)) {
            cir.setReturnValue(true);
        }
    }

    // Shift and a held left button move every stack the cursor is dragged over.
    @Inject(method = "mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z",
        at = @At("HEAD"))
    private void onMouseDragged(MouseButtonEvent event, double dragX, double dragY,
                                CallbackInfoReturnable<Boolean> cir) {
        InventoryTweaks tweaks = Modules.get(InventoryTweaks.class);
        if (tweaks == null || !tweaks.dragsStacks()
            || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || !event.hasShiftDown()) {
            return;
        }
        if (hoveredSlot == null || !hoveredSlot.hasItem() || !getMenu().getCarried().isEmpty()) {
            return;
        }
        OfflineClient.MC.gameMode.handleContainerInput(getMenu().containerId, hoveredSlot.index, 0,
            ContainerInput.QUICK_MOVE, OfflineClient.MC.player);
    }

    // Nothing opens whilst an item is on the cursor so a drop still lands.
    private boolean openHovered(BetterTooltips tooltips) {
        if (hoveredSlot == null || hoveredSlot.getItem().isEmpty() || !getMenu().getCarried().isEmpty()) {
            return false;
        }
        return tooltips.openContents(hoveredSlot.getItem());
    }
}
