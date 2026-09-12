package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.modules.render.XRay;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class XRayCommand extends Command {

    public XRayCommand() {
        super("xray", "Adds a block to the XRay list or takes it off.", "xray [block]");
    }

    @Override
    public void execute(String[] args) {
        XRay xray = XRay.get();
        if (xray == null || OfflineClient.MC.player == null) {
            return;
        }
        Block block = args.length == 0 ? lookedAt() : named(args[0]);
        if (block == null) {
            ChatUtil.error(args.length == 0
                ? "Point at a block or name one." : "There is no block called " + args[0]);
            return;
        }
        boolean added = xray.toggleBlock(block);
        ChatUtil.message("§b" + BuiltInRegistries.BLOCK.getKey(block).getPath()
            + (added ? " §7added." : " §7removed."));
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
}
