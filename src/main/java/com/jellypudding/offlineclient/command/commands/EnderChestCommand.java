package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.gui.PeekScreen;
import com.jellypudding.offlineclient.modules.render.BetterTooltips;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

// BetterTooltips keeps a copy of the chest every time you open one.
public final class EnderChestCommand extends Command {

    private static final int TINT = 0x80100010;

    public EnderChestCommand() {
        super("enderchest", "Shows what your ender chest held last time you opened it.",
            "enderchest", "ec");
    }

    @Override
    public void execute(String[] args) {
        BetterTooltips tooltips = Modules.get(BetterTooltips.class);
        List<ItemStack> items = tooltips == null ? List.of() : tooltips.rememberedEnderChest();
        if (items.isEmpty()) {
            ChatUtil.error("Open your ender chest once first.");
            return;
        }
        OfflineClient.MC.gui.setScreen(new PeekScreen(null,
            Component.literal("Ender chest"), items, TINT));
    }
}
