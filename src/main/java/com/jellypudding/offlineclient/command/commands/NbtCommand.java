package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.world.item.ItemStack;

// Copies the component data out to be pasted somewhere readable.
public final class NbtCommand extends Command {

    // Chat swallows anything much longer than this.
    private static final int PRINT_LIMIT = 220;

    public NbtCommand() {
        super("nbt", "Copies the component data of the item you hold.", "nbt", "viewnbt");
    }

    @Override
    public void execute(String[] args) {
        if (OfflineClient.MC.player == null) {
            return;
        }
        ItemStack stack = OfflineClient.MC.player.getMainHandItem();
        if (stack.isEmpty()) {
            ChatUtil.error("Your hand is empty.");
            return;
        }
        String text = stack.getComponents().toString();
        OfflineClient.MC.keyboardHandler.setClipboard(text);
        ChatUtil.message("§7Copied §b" + text.length() + " §7characters to the clipboard.");
        ChatUtil.message("§8" + (text.length() > PRINT_LIMIT
            ? text.substring(0, PRINT_LIMIT) + "..." : text));
    }
}
