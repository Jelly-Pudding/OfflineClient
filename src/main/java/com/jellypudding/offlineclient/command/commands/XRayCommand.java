package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.modules.render.XRay;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

// The bare name toggles the module like any other. This adds blocks to its list.
public final class XRayCommand extends Command {

    // Stands for the block under the crosshair.
    private static final String THIS = "this";

    public XRayCommand() {
        super("xray", "Adds a block to the XRay list or takes it off. Use this for the block you point at.",
            "xray <block|this>");
    }

    @Override
    public void execute(String[] args) {
        XRay xray = XRay.get();
        if (xray == null || OfflineClient.MC.player == null) {
            return;
        }
        if (args.length != 1) {
            usage();
            return;
        }
        boolean aimed = args[0].equalsIgnoreCase(THIS);
        Block block = aimed ? lookedAt() : named(args[0]);
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
        if (!(OfflineClient.MC.hitResult instanceof BlockHitResult hit)
            || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return OfflineClient.MC.level.getBlockState(hit.getBlockPos()).getBlock();
    }

    private static Block named(String text) {
        Identifier id = Identifier.tryParse(text.contains(":") ? text : "minecraft:" + text);
        return id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        options.add(THIS);
        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
            options.add(id.getPath());
        }
        return CommandManager.filter(current, options);
    }
}
