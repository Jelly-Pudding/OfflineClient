package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.config.SpongeSchematic;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.OptionalInt;

// The blocks are read a slice each tick on the game thread and the game stays smooth
// through a big export. The file is written on the io pool once the last block is in.
public final class ExportCommand extends Command {

    // The whole export sits in memory twice whilst the file is built.
    private static final long MAX_BLOCKS = 1L << 26;
    // The format keeps each side in an unsigned short.
    private static final int MAX_SIDE = 0xFFFF;
    private static final String TOO_WIDE = "A schematic can be at most " + MAX_SIDE + " blocks along each side.";
    // Time the scan may take out of each tick.
    private static final long SLICE_NANOS = 8_000_000L;
    // Blocks read between looks at the clock.
    private static final int BATCH = 4096;

    private static Export running;

    public ExportCommand() {
        super("export", "Saves the loaded blocks around you to a schematic for WorldEdit and Litematica.",
            "export <name> <radius> or export <name> <x y z> <x y z>", "schem");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        ClientLevel level = OfflineClient.MC.level;
        if (player == null || level == null) {
            return;
        }
        if (args.length != 2 && args.length != 7) {
            usage();
            return;
        }
        if (running != null) {
            ChatUtil.error("The export of " + running.name + " is still going.");
            return;
        }
        BlockPos standing = player.blockPosition();
        BlockPos from;
        BlockPos to;
        if (args.length == 2) {
            OptionalInt radius = wholeNumber(args[1]);
            if (radius.isEmpty()) {
                return;
            }
            if (radius.getAsInt() < 0) {
                ChatUtil.error("The radius cannot be negative.");
                return;
            }
            if (radius.getAsInt() > MAX_SIDE / 2) {
                ChatUtil.error(TOO_WIDE);
                return;
            }
            from = standing.offset(-radius.getAsInt(), -radius.getAsInt(), -radius.getAsInt());
            to = standing.offset(radius.getAsInt(), radius.getAsInt(), radius.getAsInt());
        } else {
            Vec3 first = coordinates(args, 1, player.position());
            Vec3 second = first == null ? null : coordinates(args, 4, player.position());
            if (second == null) {
                return;
            }
            from = BlockPos.containing(first);
            to = BlockPos.containing(second);
        }
        BlockPos min = new BlockPos(Math.min(from.getX(), to.getX()),
            Math.max(Math.min(from.getY(), to.getY()), level.getMinY()), Math.min(from.getZ(), to.getZ()));
        BlockPos max = new BlockPos(Math.max(from.getX(), to.getX()),
            Math.min(Math.max(from.getY(), to.getY()), level.getMaxY()), Math.max(from.getZ(), to.getZ()));
        if (min.getY() > max.getY()) {
            ChatUtil.error("That area lies outside the height of the world.");
            return;
        }
        start(args[0], level, player, min, max);
    }

    private static void start(String name, ClientLevel level, LocalPlayer player, BlockPos min, BlockPos max) {
        // Corners typed far outside the world are wider than an int can count.
        long width = (long) max.getX() - min.getX() + 1;
        long height = (long) max.getY() - min.getY() + 1;
        long length = (long) max.getZ() - min.getZ() + 1;
        if (width > MAX_SIDE || length > MAX_SIDE) {
            ChatUtil.error(TOO_WIDE);
            return;
        }
        long total = width * height * length;
        if (total > MAX_BLOCKS) {
            ChatUtil.error("That is " + total + " blocks. One export holds at most " + MAX_BLOCKS + ".");
            return;
        }
        ChatUtil.component(Component.literal("§7Reading §f" + width + " §7by §f" + height + " §7by §f" + length
            + " §7blocks into ").append(ChatUtil.fileLink(SpongeSchematic.file(name))).append("§7."));
        int missing = unloadedChunks(level, min, max);
        if (missing == 1) {
            ChatUtil.message("§7One chunk of that area is not loaded. It goes in as air.");
        } else if (missing > 1) {
            ChatUtil.message("§f" + missing + " §7chunks of that area are not loaded. They go in as air.");
        }
        BlockPos offset = min.subtract(player.blockPosition());
        running = new Export(name, level, player.getGameProfile().name(), min, offset,
            (int) width, (int) height, (int) length);
        OfflineClient.INSTANCE.getEventBus().register(running);
    }

    private static int unloadedChunks(ClientLevel level, BlockPos min, BlockPos max) {
        int missing = 0;
        int lastX = SectionPos.blockToSectionCoord(max.getX());
        int lastZ = SectionPos.blockToSectionCoord(max.getZ());
        for (int x = SectionPos.blockToSectionCoord(min.getX()); x <= lastX; x++) {
            for (int z = SectionPos.blockToSectionCoord(min.getZ()); z <= lastZ; z++) {
                if (!level.hasChunk(x, z)) {
                    missing++;
                }
            }
        }
        return missing;
    }

    private static final class Export {

        private final String name;
        private final ClientLevel level;
        private final String author;
        private final BlockPos min;
        private final BlockPos offset;
        private final int width;
        private final int length;
        private final long total;
        private final SpongeSchematic schematic;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private long next;

        private Export(String name, ClientLevel level, String author, BlockPos min, BlockPos offset,
                       int width, int height, int length) {
            this.name = name;
            this.level = level;
            this.author = author;
            this.min = min;
            this.offset = offset;
            this.width = width;
            this.length = length;
            this.total = (long) width * height * length;
            this.schematic = new SpongeSchematic(width, height, length);
        }

        @Subscribe
        private void onTick(TickEvent event) {
            if (OfflineClient.MC.level != level) {
                stop();
                running = null;
                ChatUtil.error("The export of " + name + " stopped when you left that world.");
                return;
            }
            long deadline = System.nanoTime() + SLICE_NANOS;
            while (next < total && System.nanoTime() < deadline) {
                long end = Math.min(total, next + BATCH);
                for (; next < end; next++) {
                    read(next);
                }
            }
            if (next == total) {
                stop();
                save();
            }
        }

        private void read(long index) {
            int x = (int) (index % width);
            int z = (int) (index / width % length);
            int y = (int) (index / ((long) width * length));
            pos.set(min.getX() + x, min.getY() + y, min.getZ() + z);
            BlockState state = level.getBlockState(pos);
            // A chunk that is not loaded reads as void air.
            if (state.is(Blocks.VOID_AIR)) {
                schematic.addBlock(Blocks.AIR.defaultBlockState());
                return;
            }
            schematic.addBlock(state);
            if (state.hasBlockEntity()) {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity != null) {
                    schematic.addBlockEntity(x, y, z, blockEntity, level.registryAccess());
                }
            }
        }

        private void stop() {
            OfflineClient.INSTANCE.getEventBus().unregister(this);
        }

        private void save() {
            CompoundTag root = schematic.toTag(name, author, offset);
            Path file = SpongeSchematic.file(name);
            Util.ioPool().execute(() -> {
                boolean saved = SpongeSchematic.write(root, file);
                OfflineClient.MC.execute(() -> finished(file, saved));
            });
        }

        private void finished(Path file, boolean saved) {
            running = null;
            if (!saved) {
                ChatUtil.error("The export of " + name + " could not be written. The log says why.");
                return;
            }
            ChatUtil.component(Component.literal("§7Saved ").append(ChatUtil.fileLink(file))
                .append("§7 with §f" + schematic.kinds() + " §7kinds of block and §f"
                    + schematic.blockEntityCount() + " §7block entities."));
        }
    }
}
