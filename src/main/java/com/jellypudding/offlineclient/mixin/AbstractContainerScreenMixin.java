package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.Conceal;
import com.jellypudding.offlineclient.modules.player.ChestStealer;
import com.jellypudding.offlineclient.modules.player.InventoryTweaks;
import com.jellypudding.offlineclient.modules.player.LootList;
import com.jellypudding.offlineclient.modules.player.MenuTools;
import com.jellypudding.offlineclient.modules.player.NoInteract;
import com.jellypudding.offlineclient.modules.player.ShulkerPacker;
import com.jellypudding.offlineclient.modules.render.BetterTooltips;
import com.jellypudding.offlineclient.modules.render.ItemHighlight;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.blaze3d.platform.InputConstants;
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
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin extends Screen {

    @Unique
    private static final int BUTTON_WIDTH = 40;
    @Unique
    private static final int BUTTON_HEIGHT = 14;
    // Room either side of a label inside its button and between two buttons.
    @Unique
    private static final int BUTTON_PADDING = 8;
    @Unique
    private static final int BUTTON_GAP = 2;
    @Unique
    private static final int SLOT_NUMBER_COLOR = 0xFFFFFF55;

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
    @Final
    protected int imageWidth;

    @Shadow
    public abstract AbstractContainerMenu getMenu();

    // One row of buttons above the container. Each runs its pass on click.
    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        if (Conceal.hides(Conceal.Part.SCREENS)) {
            return;
        }
        Screen screen = (Screen) (Object) this;
        int x = leftPos;
        ChestStealer stealer = Modules.get(ChestStealer.class);
        if (stealer != null && stealer.showsButtons() && stealer.handles(screen)) {
            x = offlineclient$button("Steal", x, stealer::stealNow);
            x = offlineclient$button("Dump", x, stealer::dumpNow);
        }
        ShulkerPacker packer = Modules.get(ShulkerPacker.class);
        if (packer != null && packer.showsButton(screen)) {
            x = offlineclient$button("Pack", x, () -> packer.setEnabled(true));
        }
        MenuTools tools = Modules.get(MenuTools.class);
        if (tools != null && tools.showsDesync(screen)) {
            x = offlineclient$button("Desync", x, () -> tools.desync(getMenu()));
        }
        if (tools != null && tools.showsButtons(screen)) {
            offlineclient$button("Resync", x, () -> tools.resync(getMenu()));
        }
    }

    // Hands back where the next button starts.
    @Unique
    private int offlineclient$button(String label, int x, Runnable press) {
        int width = Math.max(BUTTON_WIDTH, font.width(label) + BUTTON_PADDING);
        addRenderableWidget(Button.builder(Component.literal(label), button -> press.run())
            .bounds(x, topPos - BUTTON_HEIGHT - BUTTON_GAP, width, BUTTON_HEIGHT).build());
        return x + width + BUTTON_GAP;
    }

    // The paint goes down before the item and sits under it. The LootList row under the
    // pointer paints its slot over ItemHighlight's colour.
    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void onExtractSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
                               CallbackInfo ci) {
        if (Conceal.hides(Conceal.Part.SCREENS)) {
            return;
        }
        LootList list = Modules.get(LootList.class);
        ItemHighlight highlight = Modules.get(ItemHighlight.class);
        int color = list == null ? 0 : list.paintFor(slot);
        if (color == 0 && highlight != null) {
            color = highlight.colorFor(slot.getItem());
        }
        if (color != 0) {
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color);
        }
    }

    // The list goes beside the container once it is drawn and before the tooltip.
    @Inject(method = "extractContents", at = @At("TAIL"))
    private void afterExtractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
                                      CallbackInfo ci) {
        LootList list = Modules.get(LootList.class);
        if (list != null && !Conceal.hides(Conceal.Part.SCREENS)) {
            list.draw(graphics, font, leftPos, topPos, imageWidth, mouseX, mouseY);
        }
    }

    // The wheel scrolls the LootList whilst the pointer is over it. A hidden list keeps the place
    // it was last drawn at and takes no wheel or click.
    @Inject(method = "mouseScrolled(DDDD)Z", at = @At("HEAD"), cancellable = true)
    private void onMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY,
                                 CallbackInfoReturnable<Boolean> cir) {
        LootList list = Modules.get(LootList.class);
        if (list != null && !Conceal.hides(Conceal.Part.SCREENS) && list.scroll(mouseX, mouseY, scrollY)) {
            cir.setReturnValue(true);
        }
    }

    // The number goes on after the item and sits over it.
    @Inject(method = "extractSlot", at = @At("TAIL"))
    private void afterExtractSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
                                  CallbackInfo ci) {
        MenuTools tools = Modules.get(MenuTools.class);
        if (tools != null && tools.showsSlotNumbers() && !Conceal.hides(Conceal.Part.SCREENS)) {
            graphics.text(font, String.valueOf(slot.index), slot.x, slot.y, SLOT_NUMBER_COLOR, true);
        }
    }

    // A click that opens a container or book goes to BetterTooltips first. A left click on the
    // LootList moves stacks and a middle click on a slot sorts.
    @Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
        at = @At("HEAD"), cancellable = true)
    private void onMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        BetterTooltips tooltips = Modules.get(BetterTooltips.class);
        if (tooltips != null && tooltips.wantsToOpen(event) && offlineclient$openHovered(tooltips)) {
            cir.setReturnValue(true);
            return;
        }
        LootList list = Modules.get(LootList.class);
        if (list != null && event.button() == InputConstants.MOUSE_BUTTON_LEFT
            && !Conceal.hides(Conceal.Part.SCREENS) && list.click(event.x(), event.y(), getMenu())) {
            cir.setReturnValue(true);
            return;
        }
        InventoryTweaks tweaks = Modules.get(InventoryTweaks.class);
        if (tweaks != null && getMenu().getCarried().isEmpty() && tweaks.middleClicked(event, getMenu(), hoveredSlot)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
        at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        BetterTooltips tooltips = Modules.get(BetterTooltips.class);
        if (tooltips != null && tooltips.wantsToOpen(event) && offlineclient$openHovered(tooltips)) {
            cir.setReturnValue(true);
        }
    }

    // The clicks a player makes in the screen come through here whilst a module clicks the
    // game mode itself. NoInteract only stops the throws made by hand.
    @Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V",
        at = @At("HEAD"), cancellable = true)
    private void onSlotClicked(Slot slot, int slotId, int button, ContainerInput kind, CallbackInfo ci) {
        NoInteract noInteract = Modules.active(NoInteract.class);
        if (noInteract != null && noInteract.refusesThrow(getMenu(), slot == null ? slotId : slot.index, kind)) {
            ci.cancel();
        }
    }

    // Shift and a held left button move every stack the cursor is dragged over.
    @Inject(method = "mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z",
        at = @At("HEAD"))
    private void onMouseDragged(MouseButtonEvent event, double dragX, double dragY,
                                CallbackInfoReturnable<Boolean> cir) {
        InventoryTweaks tweaks = Modules.get(InventoryTweaks.class);
        if (tweaks == null || !tweaks.dragsStacks()
            || event.button() != InputConstants.MOUSE_BUTTON_LEFT || !event.hasShiftDown()) {
            return;
        }
        if (hoveredSlot == null || !hoveredSlot.hasItem() || !getMenu().getCarried().isEmpty()) {
            return;
        }
        MenuClicks.quickMove(getMenu(), hoveredSlot.index);
    }

    // Nothing opens whilst an item is on the cursor. A drop still lands.
    @Unique
    private boolean offlineclient$openHovered(BetterTooltips tooltips) {
        if (hoveredSlot == null || hoveredSlot.getItem().isEmpty() || !getMenu().getCarried().isEmpty()) {
            return false;
        }
        return tooltips.openContents(hoveredSlot.getItem());
    }
}
