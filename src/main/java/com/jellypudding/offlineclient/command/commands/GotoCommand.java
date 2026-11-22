package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.command.PlaceCommand;
import com.jellypudding.offlineclient.path.Travel;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;

public final class GotoCommand extends PlaceCommand {

    private static final String STOP = "stop";
    private static final String THEN = "then";
    private static final String WAIT = "wait";
    private static final String LOOP = "loop";

    public GotoCommand() {
        super("goto", "Walks to a spot or a player or a waypoint or the block you point at. Places joined by"
            + " then are walked in turn with a wait at each and loop starts over after the last.",
            "goto " + PLACE + " [then ...] [wait seconds] [loop] or goto stop", "walkto");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase(STOP)) {
            if (!Travel.walking()) {
                ChatUtil.error("You are not walking anywhere.");
            } else {
                Travel.stop();
                ChatUtil.tell("Goto", "stopped.");
            }
            return;
        }
        List<String> words = new ArrayList<>(Arrays.asList(args));
        boolean loop = takeLast(words, LOOP);
        int wait = takeWait(words);
        if (wait < 0) {
            return;
        }
        loop |= takeLast(words, LOOP);
        List<Travel.Stop> stops = stops(words, player);
        if (stops == null) {
            return;
        }
        if (loop && stops.size() < 2) {
            ChatUtil.error("A loop wants two stops or more.");
            return;
        }
        Travel.along(stops, wait, loop);
    }

    private static boolean takeLast(List<String> words, String word) {
        boolean there = words.size() > 1 && words.getLast().equalsIgnoreCase(word);
        if (there) {
            words.removeLast();
        }
        return there;
    }

    private static int takeWait(List<String> words) {
        int at = words.size() - 2;
        if (at < 0 || !words.get(at).equalsIgnoreCase(WAIT)) {
            return 0;
        }
        OptionalDouble seconds = number(words.get(at + 1));
        if (seconds.isEmpty() || seconds.getAsDouble() < 0) {
            if (seconds.isPresent()) {
                ChatUtil.error("The wait cannot be less than nought.");
            }
            return -1;
        }
        words.subList(at, words.size()).clear();
        return (int) Math.min(Integer.MAX_VALUE, Math.round(seconds.getAsDouble() * SharedConstants.TICKS_PER_SECOND));
    }

    private List<Travel.Stop> stops(List<String> words, LocalPlayer player) {
        List<Travel.Stop> stops = new ArrayList<>();
        List<String> place = new ArrayList<>();
        boolean route = words.size() > 1;
        for (int i = 0; i <= words.size(); i++) {
            if (i < words.size() && !(route && words.get(i).equalsIgnoreCase(THEN))) {
                place.add(words.get(i));
                continue;
            }
            Travel.Stop stop = place(place.toArray(String[]::new), player);
            if (stop == null) {
                return null;
            }
            stops.add(stop);
            place.clear();
        }
        return stops;
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index < 1 || tokens[index - 1].equalsIgnoreCase(WAIT)) {
            return List.of();
        }
        int word = 0;
        while (word < index - 1 && !tokens[index - 1 - word].equalsIgnoreCase(THEN)) {
            word++;
        }
        List<String> options = placeOptions(word);
        if (index == 1) {
            options.add(STOP);
        }
        if (word > 0) {
            options.addAll(List.of(THEN, WAIT, LOOP));
        }
        return CommandManager.filter(current, options);
    }
}
