package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.modules.player.AutoEat;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Changes the game mode on the client alone and sends nothing. The server ignores a creative
// inventory click and an abilities packet from a survival player but drops one who hangs in the
// air. Every ability is left as the server granted it apart from the one the creative inventory
// opens on. Modules that read that one such as AutoEat take a creative mode put on here for real.
public final class GameModeCommand extends Command {

    // The mode the server has the player in.
    private static final String REAL = "real";

    public GameModeCommand() {
        super("gamemode", "Sets the game mode your own game plays by. Use it to look through the creative"
                + " inventory or to put right a mode your game has wrong with real. The server keeps its own and"
                + " decides what happens.",
            "gamemode <survival|creative|adventure|spectator|real>", "gm");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        MultiPlayerGameMode game = OfflineClient.MC.gameMode;
        if (player == null || game == null) {
            return;
        }
        if (args.length != 1) {
            usage();
            return;
        }
        // The tab list holds the mode the server put the player in.
        GameType real = player.gameMode();
        String word = args[0].toLowerCase(Locale.ROOT);
        GameType mode = word.equals(REAL) ? real : GameType.byName(word, null);
        if (mode == null) {
            if (word.equals(REAL)) {
                ChatUtil.error("The server has not said which mode you are in.");
            } else {
                usage();
            }
            return;
        }
        // NoFall and the like keep reading whether the server can hurt you.
        Abilities abilities = player.getAbilities();
        Abilities.Packed granted = abilities.pack();
        game.setLocalMode(mode);
        boolean creativeInventory = abilities.instabuild;
        abilities.apply(granted);
        abilities.instabuild = creativeInventory;
        if (mode == real) {
            ChatUtil.message("§7Your game plays as §b" + mode.getName() + "§7 again like the server.");
            return;
        }
        ChatUtil.message("§7Your game plays as §b" + mode.getName() + "§7."
            + (real == null ? " The server" : " The server still has you in §b" + real.getName() + "§7 and")
            + " undoes what it does not allow. Flying and taking damage stay as the server has them.");
        if (mode.isCreative() && real != null && !real.isCreative()) {
            ChatUtil.message("§7The creative inventory shows every item but nothing you take from it reaches you.");
        }
        // The game draws the health and food bars only in survival and adventure.
        if (!mode.isSurvival() && (real == null || real.isSurvival())) {
            ChatUtil.message("§7Whilst it plays as §b" + mode.getName() + "§7 your health and food bars stay hidden"
                + (creativeInventory && Modules.enabled(AutoEat.class) ? " and AutoEat stops." : ".")
                + (real == null ? "" : " §f" + OfflineClient.INSTANCE.getCommandManager().getPrefix()
                    + "gamemode " + REAL + "§7 puts it back."));
        }
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index != 1) {
            return List.of();
        }
        List<String> modes = new ArrayList<>();
        for (GameType type : GameType.values()) {
            modes.add(type.getName());
        }
        modes.add(REAL);
        return CommandManager.filter(current, modes);
    }
}
