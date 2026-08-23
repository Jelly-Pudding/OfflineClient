package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.core.BlockPos;

import java.util.List;

public final class WaypointCommand extends Command {

    public WaypointCommand() {
        super("waypoint", "Saves named coordinates and marks them in the world.",
            "waypoint <add|remove|list|clear> [name] [x y z]", "wp");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            ChatUtil.error("Usage: " + getUsage());
            return;
        }
        switch (args[0].toLowerCase()) {
            case "add", "set" -> add(args);
            case "remove", "delete", "del" -> remove(args);
            case "list" -> list();
            case "clear" -> clear();
            default -> ChatUtil.error("Usage: " + getUsage());
        }
    }

    private void add(String[] args) {
        if (args.length < 2) {
            ChatUtil.error("Usage: waypoint add <name> [x y z]");
            return;
        }
        if (OfflineClient.MC.player == null) {
            ChatUtil.error("You need to be in a world to mark a waypoint.");
            return;
        }
        BlockPos pos = OfflineClient.MC.player.blockPosition();
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        if (args.length >= 5) {
            try {
                x = Integer.parseInt(args[2]);
                y = Integer.parseInt(args[3]);
                z = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                ChatUtil.error("Those coordinates are not whole numbers.");
                return;
            }
        }
        String name = args[1];
        WaypointStore.get().add(new WaypointStore.Waypoint(name, x, y, z,
            WaypointStore.currentDimension(), WaypointStore.currentServer()));
        ChatUtil.message("§aSaved §b" + name + " §7at §f" + x + " " + y + " " + z);
    }

    private void remove(String[] args) {
        if (args.length < 2) {
            ChatUtil.error("Usage: waypoint remove <name>");
            return;
        }
        if (WaypointStore.get().remove(args[1])) {
            ChatUtil.message("§cRemoved waypoint §b" + args[1]);
        } else {
            ChatUtil.error("There is no waypoint called " + args[1] + ".");
        }
    }

    private void list() {
        List<WaypointStore.Waypoint> waypoints = WaypointStore.get().here();
        if (waypoints.isEmpty()) {
            ChatUtil.message("§7No waypoints in this world yet.");
            return;
        }
        ChatUtil.message("§3Waypoints here:");
        for (WaypointStore.Waypoint waypoint : waypoints) {
            ChatUtil.message("§b" + waypoint.name() + " §7at §f"
                + waypoint.x() + " " + waypoint.y() + " " + waypoint.z());
        }
    }

    private void clear() {
        WaypointStore.get().clear();
        ChatUtil.message("§cRemoved every waypoint.");
    }

    public static List<String> names() {
        return WaypointStore.get().here().stream().map(WaypointStore.Waypoint::name).toList();
    }
}
