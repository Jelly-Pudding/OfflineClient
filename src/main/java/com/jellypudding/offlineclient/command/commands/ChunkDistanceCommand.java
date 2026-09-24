package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.OptionInstance;

import java.util.OptionalInt;
import java.util.function.Supplier;

// Sets a distance in chunks past the reach of its vanilla slider.
public final class ChunkDistanceCommand extends Command {

    private final String label;
    private final Supplier<OptionInstance<Integer>> option;
    private final int min;
    private final int max;

    private ChunkDistanceCommand(String name, String description, String alias, String label,
                                 Supplier<OptionInstance<Integer>> option, int min, int max) {
        super(name, description, name + " [chunks]", alias);
        this.label = label;
        this.option = option;
        this.min = min;
        this.max = max;
    }

    public static ChunkDistanceCommand render() {
        return new ChunkDistanceCommand("renderdistance",
            "Sets the render distance past the vanilla slider.", "rd", "Render distance",
            () -> OfflineClient.MC.options.renderDistance(), 2, 64);
    }

    public static ChunkDistanceCommand simulation() {
        return new ChunkDistanceCommand("simulationdistance",
            "Sets how far the world keeps ticking around you.", "sv", "Simulation distance",
            () -> OfflineClient.MC.options.simulationDistance(), 5, 32);
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            ChatUtil.message("§7" + label + " is §b" + option.get().get() + " §7chunks.");
            return;
        }
        if (args.length != 1) {
            usage();
            return;
        }
        OptionalInt chunks = wholeNumber(args[0]);
        if (chunks.isEmpty()) {
            return;
        }
        int value = chunks.getAsInt();
        if (value < min || value > max) {
            ChatUtil.error("Pick between " + min + " and " + max + " chunks.");
            return;
        }
        option.get().set(value);
        ChatUtil.message("§7" + label + " is now §b" + value + " §7chunks.");
    }
}
