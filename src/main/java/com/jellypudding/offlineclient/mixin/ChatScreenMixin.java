package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.ChatSendEvent;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {

    private ChatScreenMixin(OfflineClient client, Component title) {
        super(title);
    }

    @Shadow
    public abstract String normalizeChatMessage(String message);

    @Shadow
    protected EditBox input;

    /**
     * TAB completes client commands. Vanilla completion only knows
     * server commands.
     */
    @Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
        at = @At("HEAD"),
        cancellable = true)
    private void onTabComplete(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.key() != GLFW.GLFW_KEY_TAB) {
            return;
        }
        String text = input.getValue();
        String prefix = OfflineClient.INSTANCE.getCommandManager().getPrefix();
        if (!text.startsWith(prefix) || text.length() <= prefix.length()) {
            return;
        }
        cir.setReturnValue(true);

        List<String> options = OfflineClient.INSTANCE.getCommandManager().complete(text);
        if (options.isEmpty()) {
            return;
        }

        int lastSpace = text.lastIndexOf(' ');
        String head = lastSpace == -1 ? prefix : text.substring(0, lastSpace + 1);

        if (options.size() == 1) {
            input.setValue(head + options.get(0) + " ");
        } else {
            String common = commonPrefix(options);
            if (common.length() > text.length() - head.length()) {
                input.setValue(head + common);
            }
            int shown = Math.min(8, options.size());
            String more = options.size() > shown ? " §8and " + (options.size() - shown) + " more" : "";
            ChatUtil.message("§7" + String.join(" §8/ §7", options.subList(0, shown)) + more);
        }
        input.moveCursorToEnd(false);
    }

    /**
     * Live suggestions above the chat box while typing a client command.
     * The vanilla popup only knows server commands.
     */
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
        at = @At("TAIL"))
    private void onRender(GuiGraphicsExtractor context, int mouseX, int mouseY,
                          float partialTicks, CallbackInfo ci) {
        String text = input == null ? "" : input.getValue();
        String prefix = OfflineClient.INSTANCE.getCommandManager().getPrefix();
        if (!text.startsWith(prefix) || text.length() <= prefix.length()) {
            return;
        }
        List<String> options = OfflineClient.INSTANCE.getCommandManager().complete(text);
        if (options.isEmpty()) {
            return;
        }

        int shown = Math.min(8, options.size());
        int boxWidth = 0;
        for (int i = 0; i < shown; i++) {
            boxWidth = Math.max(boxWidth, minecraft.font.width(options.get(i)));
        }
        int x = 4;
        int bottom = height - 16;
        int top = bottom - shown * 10 - 2;
        context.fill(x - 2, top - 2, x + boxWidth + 4, bottom, 0xE8101018);
        context.guiRenderState.up();
        for (int i = 0; i < shown; i++) {
            context.text(minecraft.font, options.get(i), x, top + i * 10,
                i == 0 ? 0xFF00E5FF : 0xFFB0B0C0, false);
        }
        if (options.size() > shown) {
            context.text(minecraft.font, "and " + (options.size() - shown) + " more",
                x, top - 12, 0xFF707080, false);
        }
        context.guiRenderState.up();
        context.text(minecraft.font, "TAB completes", x + boxWidth + 10, bottom - 11,
            0xFF505060, false);
    }

    private static String commonPrefix(List<String> options) {
        String common = options.get(0);
        for (String option : options) {
            int i = 0;
            while (i < common.length() && i < option.length()
                && Character.toLowerCase(common.charAt(i)) == Character.toLowerCase(option.charAt(i))) {
                i++;
            }
            common = common.substring(0, i);
        }
        return common;
    }

    @Inject(method = "handleChatInput(Ljava/lang/String;Z)V",
        at = @At("HEAD"),
        cancellable = true)
    private void onSendMessage(String message, boolean addToHistory, CallbackInfo ci) {
        if ((message = normalizeChatMessage(message)).isEmpty()) {
            return;
        }

        ChatSendEvent event = new ChatSendEvent(message);
        OfflineClient.INSTANCE.getEventBus().post(event);
        if (!event.isCancelled()) {
            return;
        }

        // The command goes into chat history but never to the server.
        ci.cancel();
        if (addToHistory) {
            minecraft.gui.hud.getChat().addRecentChat(message);
        }
    }
}
