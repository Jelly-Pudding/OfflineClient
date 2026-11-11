package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.gui.PeekScreen;
import com.jellypudding.offlineclient.render.ContainerPreview;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

import java.util.List;

// The main hand comes first and then the offhand and then the item frame under the crosshair.
public final class PeekCommand extends Command {

    public PeekCommand() {
        super("peek", "Shows what the container in your hand or in the item frame you point at has inside.", "peek");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        ItemStack stack = held(player);
        boolean framed = stack == null;
        if (framed) {
            stack = framed();
        }
        if (stack == null) {
            ChatUtil.error("Hold a container or point at one in an item frame.");
            return;
        }
        List<ItemStack> contents = ContainerPreview.contentsOf(stack);
        // Paper sends an item on show without what it holds unless the server is set up to send it.
        if (framed && ServerInfo.runsPaper() && contents.stream().allMatch(ItemStack::isEmpty)) {
            ChatUtil.message("§7That one reads as empty. This server may be hiding what a framed container holds.");
            return;
        }
        PeekScreen screen = new PeekScreen(null, stack.getHoverName(), contents, ContainerPreview.tintOf(stack));
        // The chat screen shuts itself once the command returns.
        OfflineClient.MC.schedule(() -> OfflineClient.MC.gui.setScreen(screen));
    }

    // Null when neither hand holds a container.
    private static ItemStack held(LocalPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.has(DataComponents.CONTAINER)) {
                return stack;
            }
        }
        return null;
    }

    // Null when the crosshair rests on anything but a frame with a container in it.
    private static ItemStack framed() {
        if (OfflineClient.MC.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof ItemFrame frame
            && frame.getItem().has(DataComponents.CONTAINER)) {
            return frame.getItem();
        }
        return null;
    }
}
