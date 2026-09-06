package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;

public final class AuthorCommand extends Command {

    public AuthorCommand() {
        super("author", "Says who wrote the book you hold.", "author");
    }

    @Override
    public void execute(String[] args) {
        if (OfflineClient.MC.player == null) {
            return;
        }
        ItemStack stack = OfflineClient.MC.player.getMainHandItem();
        WrittenBookContent book = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (book == null) {
            ChatUtil.error("You are not holding a written book.");
            return;
        }
        ChatUtil.message("§b" + book.title().raw() + " §7by §b" + book.author());
    }
}
