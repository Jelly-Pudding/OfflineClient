package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.ClosedAreas;
import com.jellypudding.offlineclient.modules.movement.NoReturn;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

// Closes and opens the areas NoReturn keeps you out of. The list numbers every area of this
// server and remove takes one of those numbers.
public final class NoReturnCommand extends Command {

    public NoReturnCommand() {
        super("noreturn", "Closes or opens the areas NoReturn keeps you out of.",
            "noreturn <add [x z [radius]]|list|remove <number>|clear>");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        NoReturn noReturn = Modules.get(NoReturn.class);
        if (player == null || noReturn == null || args.length == 0) {
            usage();
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> add(args, player, noReturn);
            case "list" -> list(noReturn);
            case "remove" -> remove(args);
            case "clear" -> ChatUtil.tell("NoReturn", Tally.cleared(ClosedAreas.get().openAll(), "area") + ".");
            default -> usage();
        }
    }

    private void add(String[] args, LocalPlayer player, NoReturn noReturn) {
        if (args.length != 1 && args.length != 3 && args.length != 4) {
            usage("noreturn add [x z [radius]]");
            return;
        }
        Vec3 spot = args.length > 1 ? flatCoordinates(args, 1, player.position()) : player.position();
        if (spot == null) {
            return;
        }
        int radius = noReturn.radius();
        if (args.length == 4) {
            OptionalInt typed = wholeNumber(args[3]);
            if (typed.isEmpty()) {
                return;
            }
            radius = Math.max(0, typed.getAsInt());
        }
        if (!ClosedAreas.get().close(SectionPos.blockToSectionCoord(spot.x),
            SectionPos.blockToSectionCoord(spot.z), radius)) {
            ChatUtil.error("An area round that chunk is closed already.");
            return;
        }
        ChatUtil.tell("NoReturn", "closed the area round §f" + Math.round(spot.x) + " " + Math.round(spot.z) + "§7."
            + (noReturn.isEnabled() ? "" : " Switch NoReturn on to keep out of it."));
    }

    private static void list(NoReturn noReturn) {
        List<ClosedAreas.Area> areas = ClosedAreas.get().onServer();
        if (areas.isEmpty()) {
            ChatUtil.tell("NoReturn", "has no closed areas on this server.");
            return;
        }
        int number = 1;
        for (ClosedAreas.Area area : areas) {
            int x = SectionPos.sectionToBlockCoord(area.chunkX(), SectionPos.SECTION_HALF_SIZE);
            int z = SectionPos.sectionToBlockCoord(area.chunkZ(), SectionPos.SECTION_HALF_SIZE);
            String size = "closed " + Tally.counted(noReturn.reach(area), "chunk") + " round"
                + (area.radius() > 0 ? "" : " to match your render distance");
            ChatUtil.message("§f" + number++ + " §7" + ServerInfo.dimensionName(area.dimension()) + " §f" + x + " " + z
                + " §7" + size);
        }
    }

    private void remove(String[] args) {
        if (args.length != 2) {
            usage("noreturn remove <number>");
            return;
        }
        OptionalInt number = wholeNumber(args[1]);
        if (number.isEmpty()) {
            return;
        }
        List<ClosedAreas.Area> areas = ClosedAreas.get().onServer();
        if (number.getAsInt() < 1 || number.getAsInt() > areas.size()) {
            ChatUtil.error("There is no closed area " + number.getAsInt() + ". Type "
                + OfflineClient.INSTANCE.getCommandManager().getPrefix() + "noreturn list to see them.");
            return;
        }
        ClosedAreas.get().open(areas.get(number.getAsInt() - 1));
        ChatUtil.tell("NoReturn", "opened area §f" + number.getAsInt() + "§7.");
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, List.of("add", "list", "remove", "clear"));
        }
        return List.of();
    }
}
