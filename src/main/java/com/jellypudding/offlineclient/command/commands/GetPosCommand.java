package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Dimension;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

// Typed coordinates belong to the dimension you are in unless a side is named.
// In the end they are read as overworld ones. Every printed spot copies itself when clicked.
public final class GetPosCommand extends Command {

    private static final String COPY = "copy";
    private static final String OVERWORLD = "overworld";
    private static final String NETHER = "nether";

    public GetPosCommand() {
        super("getpos", "Prints where you are and the matching spot in the other dimension. Typed coordinates are converted instead.",
            "getpos [copy] or getpos <x z> [overworld|nether]", "pos", "coords", "convert", "portal");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase(COPY)) {
            String spot = BlockUtil.text(player.blockPosition());
            OfflineClient.MC.keyboardHandler.setClipboard(spot);
            ChatUtil.message("§7Copied §b" + spot + "§7.");
            return;
        }
        if (args.length == 1 || args.length > 3) {
            usage();
            return;
        }
        boolean inNether = player.level().dimension() == Level.NETHER;
        if (args.length == 0) {
            String spot = BlockUtil.text(player.blockPosition());
            ChatUtil.component(ChatUtil.copyOnClick(Component.literal("§7You are at §b" + spot), spot));
            if (inNether || player.level().dimension() == Level.OVERWORLD) {
                showAcross(player.getX(), player.getZ(), inNether);
            }
            return;
        }
        Vec3 spot = flatCoordinates(args, 0, player.position());
        if (spot == null) {
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
        showAcross(spot.x, spot.z, fromNether);
    }

    // A click copies the spot on the far side.
    private static void showAcross(double x, double z, boolean fromNether) {
        String other = Dimension.acrossPortal(x, !fromNether) + " " + Dimension.acrossPortal(z, !fromNether);
        ChatUtil.component(ChatUtil.copyOnClick(Component.literal("§7" + (fromNether ? "Nether" : "Overworld")
            + " §b" + Mth.floor(x) + " " + Mth.floor(z) + " §7is " + (fromNether ? "Overworld" : "Nether")
            + " §b" + other), other));
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return switch (index) {
            case 1 -> CommandManager.filter(current, List.of(COPY));
            case 3 -> CommandManager.filter(current, List.of(OVERWORLD, NETHER));
            default -> List.of();
        };
    }
}
