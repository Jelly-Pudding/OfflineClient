package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.modules.player.ChestMemory;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EnderChest;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;

// With no item named it looks for what you hold.
public final class ChestsCommand extends Command {

    // More rows than this push the nearest ones out of chat.
    private static final int MOST_ROWS = 10;

    public ChestsCommand() {
        super("chests", "Lists the remembered containers that hold an item.", "chests [item]");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || args.length > 1) {
            usage();
            return;
        }
        ChestMemory memory = Modules.get(ChestMemory.class);
        if (memory == null || !memory.isEnabled()) {
            ChatUtil.error("Switch ChestMemory on to remember containers.");
            return;
        }
        Item item = args.length == 1 ? CommandManager.registryEntry(BuiltInRegistries.ITEM, args[0])
            : player.getMainHandItem().getItem();
        if (item == null || item == Items.AIR) {
            ChatUtil.error(args.length == 1 ? "There is no item called " + args[0] + "." : "Name an item or hold one.");
            return;
        }
        String name = ChatUtil.words(item);
        List<ChestMemory.Hit> hits = memory.search(item);
        int ender = memory.count(EnderChest.INSTANCE.contents(), item);
        if (hits.isEmpty() && ender == 0) {
            ChatUtil.message("§7No remembered container holds §f" + name + "§7.");
            return;
        }
        long now = System.currentTimeMillis();
        if (!hits.isEmpty()) {
            ChatUtil.message("§bChestMemory §7found §f" + name + "§7 in "
                + Tally.counted(hits.size(), "container") + ".");
        }
        for (ChestMemory.Hit hit : hits.subList(0, Math.min(MOST_ROWS, hits.size()))) {
            ChatUtil.component(Component.literal("§f" + hit.count() + "§7 " + Notice.away(player.position(), hit.pos())
                + " at ").append(ChatUtil.walkLink(hit.pos()))
                .append("§7 seen " + ChatUtil.ago(now - hit.seen()) + "."));
        }
        if (hits.size() > MOST_ROWS) {
            ChatUtil.message("§7And " + (hits.size() - MOST_ROWS) + " more further away.");
        }
        if (ender > 0) {
            ChatUtil.message("§f" + ender + "§7 in your ender chest.");
        }
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index != 1) {
            return List.of();
        }
        return CommandManager.filter(current, CommandManager.registryPaths(BuiltInRegistries.ITEM));
    }
}
