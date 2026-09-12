package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.gui.PeekScreen;
import com.jellypudding.offlineclient.modules.render.BetterTooltips;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

public final class PeekCommand extends Command {

    public PeekCommand() {
        super("peek", "Shows what the container you hold has inside.", "peek");
    }

    @Override
    public void execute(String[] args) {
        if (OfflineClient.MC.player == null) {
            return;
        }
        ItemStack stack = OfflineClient.MC.player.getMainHandItem();
        if (stack.get(DataComponents.CONTAINER) == null) {
            ChatUtil.error("You are not holding a container.");
            return;
        }
        OfflineClient.MC.gui.setScreen(new PeekScreen(null, stack.getHoverName(),
            BetterTooltips.contentsOf(stack), BetterTooltips.tintOf(stack)));
    }
}
