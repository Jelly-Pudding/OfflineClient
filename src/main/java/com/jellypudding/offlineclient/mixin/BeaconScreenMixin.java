package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.modules.misc.BetterBeacons;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.BeaconScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

// Every effect is offered whatever the pyramid is worth. A tier of nought keeps
// a button live and a tier of three greys it out below a full pyramid.
@Mixin(BeaconScreen.class)
public abstract class BeaconScreenMixin extends AbstractContainerScreen<BeaconMenu> {

    private static final int BUTTON_SIZE = 24;
    private static final int COLUMNS = 3;
    private static final int PRIMARY_TIER = 0;
    private static final int SECONDARY_TIER = 3;

    private BeaconScreenMixin(BeaconMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Shadow
    private void addBeaconButton(AbstractWidget button) {
    }

    @Inject(method = "init",
        at = @At(value = "INVOKE", target = "Ljava/util/List;clear()V", shift = At.Shift.AFTER),
        cancellable = true)
    private void onInit(CallbackInfo ci) {
        if (!Modules.enabled(BetterBeacons.class)) {
            return;
        }
        List<Holder<MobEffect>> effects = new ArrayList<>();
        for (List<Holder<MobEffect>> tier : BeaconBlockEntity.BEACON_EFFECTS) {
            effects.addAll(tier);
        }
        offlineclient$grid(effects, leftPos + 20, topPos + 22, true, PRIMARY_TIER);
        offlineclient$grid(effects, leftPos + 116, topPos + 22, false, SECONDARY_TIER);
        BeaconScreen screen = (BeaconScreen) (Object) this;
        addBeaconButton(screen.new BeaconConfirmButton(leftPos + 164, topPos + 107));
        addBeaconButton(screen.new BeaconCancelButton(leftPos + 190, topPos + 107));
        ci.cancel();
    }

    @Unique
    private void offlineclient$grid(List<Holder<MobEffect>> effects, int x, int y,
                                    boolean primary, int tier) {
        for (int i = 0; i < effects.size(); i++) {
            int column = i % COLUMNS;
            int row = i / COLUMNS;
            BeaconScreen screen = (BeaconScreen) (Object) this;
            addBeaconButton(screen.new BeaconPowerButton(x + column * BUTTON_SIZE,
                y + row * BUTTON_SIZE, effects.get(i), primary, tier));
        }
    }
}
