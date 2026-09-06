package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.modules.misc.BetterChat;
import com.jellypudding.offlineclient.modules.render.Blur;
import com.jellypudding.offlineclient.event.events.ChatSendEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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

    // Which of the shown completions the arrow keys have picked.
    @Unique
    private int offlineclient$picked;

    // The text the pick was made for. Typing anything else starts over at the top.
    @Unique
    private String offlineclient$pickedFor = "";

    // Vanilla completion only knows server commands.
    // Tab takes the picked completion and up or down arrows move the pick through the list.
    @Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
        at = @At("HEAD"),
        cancellable = true)
    private void onTabComplete(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        int key = event.key();
        if (key != GLFW.GLFW_KEY_TAB && key != GLFW.GLFW_KEY_UP && key != GLFW.GLFW_KEY_DOWN) {
            return;
        }
        String text = input.getValue();
        String prefix = OfflineClient.INSTANCE.getCommandManager().getPrefix();
        if (!text.startsWith(prefix) || text.length() <= prefix.length()) {
            return;
        }
        List<String> options = OfflineClient.INSTANCE.getCommandManager().complete(text);
        // With nothing to choose between the arrows keep walking the chat history.
        if (options.isEmpty() || (key != GLFW.GLFW_KEY_TAB && options.size() < 2)) {
            return;
        }
        cir.setReturnValue(true);
        int picked = offlineclient$pickFor(text, options.size());

        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            offlineclient$picked = Math.floorMod(picked + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), options.size());
            return;
        }

        int lastSpace = text.lastIndexOf(' ');
        String head = lastSpace == -1 ? prefix : text.substring(0, lastSpace + 1);
        String typed = text.substring(head.length());
        String common = offlineclient$commonPrefix(options);
        // A pick or a single match completes whole. Otherwise the shared start fills in first.
        if (options.size() == 1 || picked > 0 || common.length() <= typed.length()) {
            input.setValue(head + options.get(picked) + " ");
        } else {
            input.setValue(head + common);
        }
        input.moveCursorToEnd(false);
    }

    // The pick for this text. Any change to the text puts it back on the first option.
    @Unique
    private int offlineclient$pickFor(String text, int count) {
        if (!text.equals(offlineclient$pickedFor)) {
            offlineclient$pickedFor = text;
            offlineclient$picked = 0;
        }
        offlineclient$picked = Math.min(offlineclient$picked, count - 1);
        return offlineclient$picked;
    }

    // The chat draws no background of its own so the blur has to be asked for here.
    @Inject(method = "extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
        at = @At("HEAD"))
    private void onExtractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY,
                                     float partialTicks, CallbackInfo ci) {
        Blur blur = Modules.get(Blur.class);
        if (blur != null && blur.wants((Screen) (Object) this)) {
            blur.blurHere(context);
        }
    }

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

        int picked = offlineclient$pickFor(text, options.size());
        // The list scrolls to keep the pick among the rows shown.
        int shown = Math.min(8, options.size());
        int first = Math.clamp(picked - shown + 1, 0, options.size() - shown);
        String overflow = options.size() > shown
            ? "and " + (options.size() - shown) + " more" : null;
        int boxWidth = overflow == null ? 0 : minecraft.font.width(overflow);
        for (int i = first; i < first + shown; i++) {
            boxWidth = Math.max(boxWidth, minecraft.font.width(options.get(i)));
        }
        int x = 4;
        int bottom = height - 16;
        int top = bottom - shown * 10 - 2;
        // The overflow line sits a row above the options and needs covering too.
        context.fill(x - 2, overflow == null ? top - 2 : top - 14,
            x + boxWidth + 4, bottom, 0xE8101018);
        context.guiRenderState.up();
        for (int i = 0; i < shown; i++) {
            int index = first + i;
            context.text(minecraft.font, options.get(index), x, top + i * 10,
                index == picked ? 0xFF00E5FF : 0xFFB0B0C0, false);
        }
        if (overflow != null) {
            context.text(minecraft.font, overflow, x, top - 12, 0xFF707080, false);
        }
    }

    @Unique
    private static String offlineclient$commonPrefix(List<String> options) {
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

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        BetterChat betterChat = Modules.get(BetterChat.class);
        if (betterChat != null && betterChat.liftsBoxLimit()) {
            input.setMaxLength(Integer.MAX_VALUE);
        }
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
        if (event.isCancelled()) {
            // The command goes into chat history but never to the server.
            ci.cancel();
            if (addToHistory) {
                minecraft.gui.hud.getChat().addRecentChat(message);
            }
            return;
        }

        BetterChat betterChat = Modules.get(BetterChat.class);
        String rewritten = betterChat == null ? message : betterChat.rewrite(message);
        if (rewritten.equals(message)) {
            return;
        }

        // The typed line is what goes into the history. The dressed one goes to the server.
        ci.cancel();
        if (addToHistory) {
            minecraft.gui.hud.getChat().addRecentChat(message);
        }
        minecraft.player.connection.sendChat(rewritten);
    }
}
