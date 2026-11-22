package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.path.Travel;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class WaypointsTab implements TabView.Source {

    private static final int SPOT_WORDS = 4;

    @Override
    public List<TabView.Entry> entries(String query) {
        List<WaypointStore.Waypoint> here = new ArrayList<>(WaypointStore.get().here());
        here.sort(Comparator.comparing(WaypointStore.Waypoint::name, String.CASE_INSENSITIVE_ORDER));
        List<TabView.Entry> rows = new ArrayList<>();
        for (WaypointStore.Waypoint waypoint : here) {
            if (GuiSources.matches(waypoint.name(), query)) {
                rows.add(new TabView.Entry(waypoint.name(),
                    BlockUtil.text(waypoint.pos()),
                    "Walk to " + waypoint.name() + " with the pathfinder."));
            }
        }
        return rows;
    }

    @Override
    public String emptyText(String query) {
        if (query.isEmpty()) {
            return "no waypoints in this world yet";
        }
        Mark mark = mark(query);
        if (mark == null) {
            return "type a name or a name and x y z";
        }
        return "press enter to mark " + mark.name()
            + (mark.spot() == null ? " where you stand" : " at " + BlockUtil.text(mark.spot()));
    }

    // The list that edits them opens by command. Only the short name of the command fits the line.
    @Override
    public String footer() {
        return "click to walk there or type " + OfflineClient.INSTANCE.getCommandManager().getPrefix()
            + "wp to edit";
    }

    @Override
    public String addHint() {
        return "type a name and press enter";
    }

    @Override
    public List<TabView.Hint> hints(String query) {
        if (!query.isEmpty() || !WaypointStore.get().onServer().isEmpty()) {
            return List.of();
        }
        String prefix = OfflineClient.INSTANCE.getCommandManager().getPrefix();
        return List.of(
            new TabView.Hint("Mark a waypoint",
                "Type a name and press enter to mark where you stand. Add x y z to mark another spot.",
                List.of("home")),
            new TabView.Hint("Edit a waypoint",
                "Type " + prefix + "waypoint in chat for the list. Each waypoint there can be renamed and"
                    + " moved and given a look of its own.", List.of()));
    }

    @Override
    public void add(String text) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            ChatUtil.error("You need to be in a world to mark a waypoint.");
            return;
        }
        Mark mark = mark(text);
        if (mark == null) {
            ChatUtil.error("Type a name or a name and x y z.");
            return;
        }
        BlockPos pos = mark.spot() == null ? player.blockPosition() : mark.spot();
        WaypointStore.get().mark(mark.name(), pos, Modules.nextWaypointColour());
        ChatUtil.message("§aSaved §b" + mark.name() + " §7at §f" + BlockUtil.text(pos));
    }

    @Override
    public void activate(String name) {
        WaypointStore.Waypoint waypoint = WaypointStore.get().find(name);
        if (waypoint == null || !Travel.to(waypoint.pos())) {
            return;
        }
        Screen open = OfflineClient.MC.gui.screen();
        if (open != null) {
            open.onClose();
        }
    }

    @Override
    public String dropHint(String name) {
        return "Remove the waypoint " + name;
    }

    @Override
    public void drop(String name) {
        WaypointStore.get().remove(name);
    }

    // A name alone has no spot and marks where you stand.
    private record Mark(String name, BlockPos spot) {
    }

    private static Mark mark(String text) {
        String[] words = text.trim().split("\\s+");
        if (words.length == 1) {
            return new Mark(words[0], null);
        }
        if (words.length != SPOT_WORDS) {
            return null;
        }
        try {
            return new Mark(words[0], BlockPos.containing(Double.parseDouble(words[1]),
                Double.parseDouble(words[2]), Double.parseDouble(words[3])));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
