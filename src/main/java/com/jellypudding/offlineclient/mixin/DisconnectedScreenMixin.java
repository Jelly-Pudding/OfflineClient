package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.NameStore;
import com.jellypudding.offlineclient.gui.FootButtons;
import com.jellypudding.offlineclient.modules.misc.AutoReconnect;
import com.jellypudding.offlineclient.modules.misc.Conceal;
import com.jellypudding.offlineclient.modules.misc.PacketLogger;
import com.jellypudding.offlineclient.modules.misc.Webhook;
import com.jellypudding.offlineclient.util.ConnectionLog;
import com.jellypudding.offlineclient.util.Leave;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Words about a kick the client asked for or about a turned away name go under the reason. The game
// log fills the space above.
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin extends Screen {

    private DisconnectedScreenMixin(Component title) {
        super(title);
    }

    @Unique
    private Button offlineclient$reconnect;

    @Unique
    private Button offlineclient$toggle;

    @Shadow
    @Final
    private DisconnectionDetails details;

    @Shadow
    @Final
    private LinearLayout layout;

    @Unique
    private Component offlineclient$note;

    // Init runs once for each screen. A resize only centres the layout again.
    @Inject(method = "init", at = @At("HEAD"))
    private void onOpened(CallbackInfo ci) {
        offlineclient$note = offlineclient$takeNote();
        PacketLogger logger = Modules.active(PacketLogger.class);
        if (logger != null) {
            logger.kickReport(details.reason());
        }
        Webhook webhook = Modules.active(Webhook.class);
        if (webhook != null) {
            webhook.dropped(details.reason());
        }
    }

    // A kick the client asked for is explained. A server that checked your account turned the saved
    // name away.
    @Unique
    private static Component offlineclient$takeNote() {
        Component leaveNote = Leave.takeNote();
        String turnedAway = NameStore.get().checkedName();
        if (leaveNote != null || turnedAway == null) {
            return leaveNote;
        }
        return Component.literal("\n\n§bThis server checks accounts and turns away the name " + turnedAway
            + ". Type " + OfflineClient.INSTANCE.getCommandManager().getPrefix()
            + "name reset or pick your account name in the Names tab.");
    }

    @ModifyArg(method = "init", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/components/MultiLineTextWidget;<init>"
            + "(Lnet/minecraft/network/chat/Component;Lnet/minecraft/client/gui/Font;)V"), index = 0)
    private Component addNote(Component reason) {
        return offlineclient$note == null ? reason : reason.copy().append(offlineclient$note);
    }

    // The game log fills the space above the message. The rejoin under the next saved name sits under
    // the vanilla button.
    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        AutoReconnect module = Modules.get(AutoReconnect.class);
        if (module == null) {
            return;
        }
        if (module.showsConnectLog()) {
            addRenderableOnly((graphics, mouseX, mouseY, delta) -> ConnectionLog.draw(graphics, font, width,
                layout.getY()));
        }
        if (Conceal.hides(Conceal.Part.SCREENS) || !AutoReconnect.canReconnect()) {
            return;
        }
        if (module.showsButtons()) {
            offlineclient$reconnect = addRenderableWidget(FootButtons.upper(this, Component.literal("Reconnect"),
                button -> module.reconnectNow()));
            offlineclient$toggle = addRenderableWidget(FootButtons.lower(this, offlineclient$toggleText(module),
                button -> {
                    module.toggleFromScreen();
                    offlineclient$toggle.setMessage(offlineclient$toggleText(module));
                }));
        }
        String next = module.showsNameButton() ? NameStore.get().next() : null;
        if (next != null) {
            Button join = layout.addChild(Button.builder(Component.literal("Join as " + next),
                button -> module.reconnectAs(next)).width(Button.BIG_WIDTH).build());
            layout.arrangeElements();
            addRenderableWidget(join);
            repositionElements();
        }
    }

    @Unique
    private static Component offlineclient$toggleText(AutoReconnect module) {
        return Component.literal("Auto reconnect " + (module.isEnabled() ? "on" : "off"));
    }

    @Override
    public void tick() {
        super.tick();
        AutoReconnect module = Modules.get(AutoReconnect.class);
        if (offlineclient$reconnect == null || module == null) {
            return;
        }
        double left = module.secondsLeft();
        offlineclient$reconnect.setMessage(Component.literal(
            left < 0 ? "Reconnect" : "Reconnect (" + left + ")"));
    }
}
