package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.CornerPicker;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

// Hands a module that works on a box both of its corners by typing. A corner out of sight or
// out of reach cannot be marked with the bind. The module is switched on afresh for the box and
// says the rest itself.
public final class CornersCommand extends Command {

    private static final int SIZE_WORDS = 3;
    private static final int CORNER_WORDS = 6;

    public CornersCommand() {
        super("corners", "Types the two corners of the box for Excavator or Filler or TemplateTool. Three"
                + " numbers make a box that long and wide and high in front of you. A height below nought goes"
                + " down from under your feet.",
            "corners [module] <x y z> <x y z> or corners [module] <length> <width> <height>", "box");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        Module named = args.length == 0 ? null : taker(args[0]);
        String[] numbers = named == null ? args : Arrays.copyOfRange(args, 1, args.length);
        if (numbers.length != SIZE_WORDS && numbers.length != CORNER_WORDS) {
            usage();
            return;
        }
        Module module = named == null ? onlyOneOn() : named;
        if (module == null) {
            ChatUtil.error("Name the module the box is for. It can be " + String.join(" or ", takerNames()) + ".");
            return;
        }
        BlockPos[] corners = numbers.length == SIZE_WORDS ? ahead(player, numbers) : typed(player, numbers);
        if (corners == null || !fits(player.level(), corners)) {
            return;
        }
        // Whatever box it was on is dropped. A corner would otherwise join one half marked.
        module.setEnabled(false);
        module.setEnabled(true);
        if (module.isEnabled()) {
            CornerPicker.Taker taker = (CornerPicker.Taker) module;
            taker.markCorner(corners[0]);
            taker.markCorner(corners[1]);
        }
    }

    // Null for a word that names no module taking corners.
    private static Module taker(String name) {
        return OfflineClient.INSTANCE.getModuleManager().get(name) instanceof CornerPicker.Taker taker
            ? (Module) taker : null;
    }

    // The module to take a box typed without a name. Null unless exactly one of them is on.
    private static Module onlyOneOn() {
        Module only = null;
        for (CornerPicker.Taker taker : Modules.all(CornerPicker.Taker.class)) {
            if (((Module) taker).isEnabled()) {
                if (only != null) {
                    return null;
                }
                only = (Module) taker;
            }
        }
        return only;
    }

    private static List<String> takerNames() {
        List<String> names = new ArrayList<>();
        for (CornerPicker.Taker taker : Modules.all(CornerPicker.Taker.class)) {
            names.add(((Module) taker).getName().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    // Two corners as typed. Null after telling the player what was wrong.
    private static BlockPos[] typed(LocalPlayer player, String[] numbers) {
        Vec3 first = coordinates(numbers, 0, player.position());
        Vec3 second = first == null ? null : coordinates(numbers, SIZE_WORDS, player.position());
        return second == null ? null : new BlockPos[] {BlockPos.containing(first), BlockPos.containing(second)};
    }

    // A box that starts at the block in front of your feet. It runs its length the way you face
    // with its width spread evenly to both sides. Null after telling the player what was wrong.
    private static BlockPos[] ahead(LocalPlayer player, String[] numbers) {
        int[] size = new int[SIZE_WORDS];
        for (int i = 0; i < SIZE_WORDS; i++) {
            OptionalInt number = wholeNumber(numbers[i]);
            if (number.isEmpty()) {
                return null;
            }
            size[i] = number.getAsInt();
        }
        int length = size[0];
        int width = size[1];
        int height = size[2];
        if (length < 1 || width < 1 || height == 0) {
            ChatUtil.error("The length and the width have to be one or more and the height cannot be nought.");
            return null;
        }
        Direction facing = player.getDirection();
        Direction right = facing.getClockWise();
        BlockPos start = player.blockPosition().relative(facing);
        BlockPos near = start.relative(right.getOpposite(), (width - 1) / 2);
        BlockPos far = start.relative(facing, length - 1).relative(right, width / 2);
        return height > 0 ? new BlockPos[] {near, far.above(height - 1)}
            : new BlockPos[] {near.below(), far.above(height)};
    }

    // False after telling the player the box is outside the world or too large.
    private static boolean fits(Level level, BlockPos[] corners) {
        for (BlockPos corner : corners) {
            if (level.isOutsideBuildHeight(corner)) {
                ChatUtil.error("That box reaches outside the height of the world.");
                return false;
            }
        }
        long blocks = 1;
        for (Direction.Axis axis : Direction.Axis.values()) {
            blocks *= Math.abs((long) corners[0].get(axis) - corners[1].get(axis)) + 1;
            if (blocks > CornerPicker.MOST_BLOCKS) {
                ChatUtil.error("A typed box holds at most " + CornerPicker.MOST_BLOCKS + " blocks.");
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 1 ? CommandManager.filter(current, takerNames()) : List.of();
    }
}
