package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

// Typed coordinates belong to the dimension you are in unless a side is named.
// In the end they are read as overworld ones.
public final class GetPosCommand extends Command {

    private static final String OVERWORLD = "overworld";
    private static final String NETHER = "nether";

    public GetPosCommand() {
        super("getpos", "Prints where you are and the matching spot in the other dimension. Typed coordinates are converted instead.",
            "getpos [x z] [overworld|nether]", "pos", "coords", "convert", "portal");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (args.length == 1 || args.length > 3) {
            usage();
            return;
        }
        boolean inNether = player.level().dimension() == Level.NETHER;
        if (args.length == 0) {
            BlockPos pos = player.blockPosition();
            ChatUtil.message("§7You are at §b" + BlockUtil.text(pos));
            if (inNether || player.level().dimension() == Level.OVERWORLD) {
                showAcross(pos.getX(), pos.getZ(), inNether);
            }
            return;
        }
        OptionalDouble x = coordinate(args[0], player.getX());
        OptionalDouble z = x.isPresent() ? coordinate(args[1], player.getZ()) : OptionalDouble.empty();
        if (z.isEmpty()) {
            return;
        }
        boolean fromNether = inNether;
        if (args.length == 3) {
            switch (args[2].toLowerCase(Locale.ROOT)) {
                case OVERWORLD -> fromNether = false;
                case NETHER -> fromNether = true;
                default -> {
                    usage();
                    return;
                }
            }
        }
        showAcross(Mth.floor(x.getAsDouble()), Mth.floor(z.getAsDouble()), fromNether);
    }

    private static void showAcross(int x, int z, boolean fromNether) {
        int otherX = WaypointStore.acrossPortal(x, !fromNether);
        int otherZ = WaypointStore.acrossPortal(z, !fromNether);
        ChatUtil.message("§7" + (fromNether ? "Nether" : "Overworld") + " §b" + x + " " + z
            + " §7is " + (fromNether ? "Overworld" : "Nether") + " §b" + otherX + " " + otherZ);
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 3 ? CommandManager.filter(current, List.of(OVERWORLD, NETHER)) : List.of();
    }
}
