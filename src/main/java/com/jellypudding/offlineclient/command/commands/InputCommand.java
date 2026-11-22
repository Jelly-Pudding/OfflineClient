package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.MacroLine;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InputHolds;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.StringJoiner;

public final class InputCommand extends Command {

    private static final String SWAP = "swap";
    private static final String DROP = "drop";
    private static final String LIST = "list";
    private static final String CLEAR = "clear";

    public InputCommand() {
        super("input", "Holds a movement or use or attack key for a time or presses swap or drop or a hotbar"
                + " slot once.",
            "input <forward|back|left|right|jump|sneak|sprint|use|attack> <ticks> or input <swap|drop|1 to 9>"
                + " or input <list|clear> [key]");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (args.length == 0 || args.length > 2) {
            usage();
            return;
        }
        String word = args[0].toLowerCase(Locale.ROOT);
        InputHolds.Key key = InputHolds.Key.named(word);
        if (key != null) {
            hold(key, args);
        } else if (word.equals(CLEAR)) {
            clear(args);
        } else if (args.length == 2) {
            usage();
        } else if (word.equals(LIST)) {
            list();
        } else if (!word.equals(SWAP) && !word.equals(DROP) && slotOf(word) < 0) {
            usage();
        } else if (Hands.slotsTaken()) {
            ChatUtil.error("A module is using your hands. " + word + " was not pressed.");
        } else if (word.equals(SWAP)) {
            swap(player);
        } else if (word.equals(DROP)) {
            String refused = DropCommand.throwHeld(player, false);
            if (refused != null) {
                ChatUtil.error(refused);
            }
        } else if (!player.isSpectator()) {
            player.getInventory().setSelectedSlot(slotOf(word));
        }
    }

    private void hold(InputHolds.Key key, String[] args) {
        OptionalInt ticks = args.length == 2 ? MacroLine.ticks(args[1]) : OptionalInt.empty();
        if (ticks.isEmpty() || ticks.getAsInt() < 1) {
            usage("input " + key.word() + " <ticks> such as 40 or 2s");
            return;
        }
        InputHolds.hold(key, ticks.getAsInt());
    }

    private void clear(String[] args) {
        if (args.length == 1) {
            int held = InputHolds.letGoAll();
            ChatUtil.message(held == 0 ? "§7No key is being held." : "§7Let go of §b" + Tally.counted(held, "key") + "§7.");
            return;
        }
        InputHolds.Key key = InputHolds.Key.named(args[1]);
        if (key == null) {
            usage("input clear [key]");
        } else if (InputHolds.letGo(key)) {
            ChatUtil.message("§7Let go of §b" + key.word() + "§7.");
        } else {
            ChatUtil.message("§b" + key.word() + " §7is not being held.");
        }
    }

    private static void list() {
        Map<InputHolds.Key, Integer> running = InputHolds.running();
        if (running.isEmpty()) {
            ChatUtil.message("§7No key is being held.");
            return;
        }
        StringJoiner held = new StringJoiner("§7 and ");
        for (InputHolds.Key key : InputHolds.Key.values()) {
            if (InputHolds.waiting(key)) {
                held.add("§b" + key.word() + " §7until a module is done with your hands");
            } else if (running.containsKey(key)) {
                held.add("§b" + key.word() + " §7for §f" + Tally.counted(running.get(key), "tick"));
            }
        }
        ChatUtil.message("§7Holding " + held + "§7.");
    }

    private static void swap(LocalPlayer player) {
        if (!player.isSpectator()) {
            player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
        }
    }

    private static int slotOf(String word) {
        int slot = word.length() == 1 ? word.charAt(0) - '1' : -1;
        return slot >= 0 && slot < InventoryUtil.HOTBAR_SIZE ? slot : -1;
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        List<String> keys = new ArrayList<>();
        for (InputHolds.Key key : InputHolds.Key.values()) {
            keys.add(key.word());
        }
        if (index == 2 && tokens[1].equalsIgnoreCase(CLEAR)) {
            return CommandManager.filter(current, keys);
        }
        if (index != 1) {
            return List.of();
        }
        keys.addAll(List.of(SWAP, DROP, LIST, CLEAR));
        return CommandManager.filter(current, keys);
    }
}
