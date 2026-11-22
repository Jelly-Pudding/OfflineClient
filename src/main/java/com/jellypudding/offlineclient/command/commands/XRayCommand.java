package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.modules.render.XRay;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;

public final class XRayCommand extends Command {

    private static final String THIS = "this";

    public XRayCommand() {
        super("xray", "Adds a block to the XRay list or takes it off. Use this for the block you point at.",
            "xray <block|this>");
    }

    @Override
    public void execute(String[] args) {
        XRay xray = Modules.get(XRay.class);
        if (xray == null || OfflineClient.MC.player == null) {
            return;
        }
        if (args.length != 1) {
            usage();
            return;
        }
        boolean aimed = args[0].equalsIgnoreCase(THIS);
        Block block = aimed ? lookedAt() : CommandManager.registryEntry(BuiltInRegistries.BLOCK, args[0]);
        if (block == null) {
            ChatUtil.error(aimed ? "Point at a block first." : "There is no block called " + args[0]);
            return;
        }
        boolean added = xray.toggleBlock(block);
        ChatUtil.message("§b" + BuiltInRegistries.BLOCK.getKey(block).getPath()
            + (added ? " §7added to XRay." : " §7taken off XRay."));
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
    }

    private static Block lookedAt() {
        BlockHitResult hit = BlockUtil.aimedBlock();
        if (hit == null) {
            return null;
        }
        return OfflineClient.MC.level.getBlockState(hit.getBlockPos()).getBlock();
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>(CommandManager.registryPaths(BuiltInRegistries.BLOCK));
        options.add(THIS);
        return CommandManager.filter(current, options);
    }
}
