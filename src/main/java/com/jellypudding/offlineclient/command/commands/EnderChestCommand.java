package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.gui.PeekScreen;
import com.jellypudding.offlineclient.modules.player.ChestLink;
import com.jellypudding.offlineclient.modules.render.BetterTooltips;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

// ChestLink may keep the chest itself open. BetterTooltips keeps a copy every time you open one.
public final class EnderChestCommand extends Command {

    private static final int TINT = 0x80100010;

    public EnderChestCommand() {
        super("enderchest", "Shows the ender chest ChestLink keeps open or what yours held last time.",
            "enderchest", "ec");
    }

    @Override
    public void execute(String[] args) {
        ChestLink link = Modules.get(ChestLink.class);
        if (link != null && link.showEnderChest()) {
            return;
        }
        BetterTooltips tooltips = Modules.get(BetterTooltips.class);
        List<ItemStack> items = tooltips == null ? List.of() : tooltips.rememberedEnderChest();
        if (items.isEmpty()) {
            ChatUtil.error("Open your ender chest once first.");
            return;
        }
        // The chat screen shuts itself once the command returns.
        OfflineClient.MC.schedule(() -> OfflineClient.MC.gui.setScreen(new PeekScreen(null,
            Component.literal("Ender chest"), items, TINT)));
    }
}
