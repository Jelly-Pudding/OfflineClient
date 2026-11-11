package com.jellypudding.offlineclient.config;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.VarInt;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

// A schematic in the Sponge format. It is gzipped NBT. Version three keeps a Schematic compound
// inside a root with no name and that is the version written here. Versions one and two keep
// everything in the root. Blocks go in the order the format keeps them. X runs fastest then Z
// then Y.
public final class SpongeSchematic {

    public static final String EXTENSION = ".schem";

    private static final int FORMAT_VERSION = 3;

    private static final String SCHEMATIC_KEY = "Schematic";
    private static final String VERSION_KEY = "Version";
    private static final String DATA_VERSION_KEY = "DataVersion";
    private static final String METADATA_KEY = "Metadata";
    private static final String WIDTH_KEY = "Width";
    private static final String HEIGHT_KEY = "Height";
    private static final String LENGTH_KEY = "Length";
    private static final String OFFSET_KEY = "Offset";
    private static final String BLOCKS_KEY = "Blocks";
    private static final String PALETTE_KEY = "Palette";
    private static final String DATA_KEY = "Data";
    // Versions one and two name the block data this way.
    private static final String ROOT_DATA_KEY = "BlockData";
    // Some editors of versions one and two keep the world position in Offset and the offset
    // from the spot the build was copied at under these three keys of the metadata.
    private static final String[] COPY_OFFSET_KEYS = {"WEOffsetX", "WEOffsetY", "WEOffsetZ"};
    // The highest number a palette may give a block. The game has far fewer states than this
    // and a table as long as the highest number is made to look them up.
    private static final int HIGHEST_PALETTE_ID = (1 << 20) - 1;

    private final int width;
    private final int height;
    private final int length;
    private final Map<BlockState, Integer> palette = new LinkedHashMap<>();
    // Each palette index is a varint the way the game writes them in packets.
    private final ByteBuf data = Unpooled.buffer();
    private final ListTag blockEntities = new ListTag();

    public SpongeSchematic(int width, int height, int length) {
        this.width = width;
        this.height = height;
        this.length = length;
    }

    public static Path file(String name) {
        return BuildFiles.folder().resolve(DataFiles.safeName(name) + EXTENSION);
    }

    public void addBlock(BlockState state) {
        VarInt.write(data, palette.computeIfAbsent(state, added -> palette.size()));
    }

    // The position counts from the lowest corner of the schematic.
    public void addBlockEntity(int x, int y, int z, BlockEntity blockEntity, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("Pos", new int[] {x, y, z});
        tag.putString("Id", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString());
        CompoundTag contents = blockEntity.saveWithoutMetadata(registries);
        if (!contents.isEmpty()) {
            tag.put("Data", contents);
        }
        blockEntities.add(tag);
    }

    public int kinds() {
        return palette.size();
    }

    public int blockEntityCount() {
        return blockEntities.size();
    }

    // The offset runs from the spot it was saved at to the lowest corner. A paste puts the
    // blocks back the same way round from wherever you stand.
    public CompoundTag toTag(String name, String author, Vec3i offset) {
        CompoundTag paletteTag = new CompoundTag();
        palette.forEach((state, index) -> paletteTag.putInt(BlockStateParser.serialize(state), index));
        CompoundTag blocks = new CompoundTag();
        blocks.put(PALETTE_KEY, paletteTag);
        blocks.putByteArray(DATA_KEY, ByteBufUtil.getBytes(data));
        blocks.put("BlockEntities", blockEntities);

        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", name);
        metadata.putString("Author", author);
        metadata.putLong("Date", System.currentTimeMillis());

        // The sides are unsigned shorts. A cast keeps the bits of anything up to 65535.
        CompoundTag schematic = new CompoundTag();
        schematic.putInt(VERSION_KEY, FORMAT_VERSION);
        schematic.putInt(DATA_VERSION_KEY, SharedConstants.getCurrentVersion().dataVersion().version());
        schematic.put(METADATA_KEY, metadata);
        schematic.putShort(WIDTH_KEY, (short) width);
        schematic.putShort(HEIGHT_KEY, (short) height);
        schematic.putShort(LENGTH_KEY, (short) length);
        schematic.putIntArray(OFFSET_KEY, new int[] {offset.getX(), offset.getY(), offset.getZ()});
        schematic.put(BLOCKS_KEY, blocks);

        CompoundTag root = new CompoundTag();
        root.put(SCHEMATIC_KEY, schematic);
        return root;
    }

    // False after logging why the file could not be written.
    public static boolean write(CompoundTag root, Path path) {
        return DataFiles.writeSafely(path, temp -> NbtIo.writeCompressed(root, temp));
    }

    // True for a root that holds a schematic of any version of the format.
    static boolean holds(CompoundTag root) {
        return root.contains(SCHEMATIC_KEY) || root.contains(PALETTE_KEY);
    }

    static int dataVersion(CompoundTag root) {
        return root.getCompound(SCHEMATIC_KEY).orElse(root).getIntOr(DATA_VERSION_KEY, 0);
    }

    // The build a schematic of version one two or three holds. The offset of the file becomes
    // the lowest corner. Put down where it was copied the build lands on the blocks it came from.
    static Blueprint read(String name, CompoundTag root, SavedStates states) throws BuildFiles.Unreadable {
        CompoundTag schematic = root.getCompound(SCHEMATIC_KEY).orElse(root);
        int version = schematic.getIntOr(VERSION_KEY, 0);
        if (version < 1) {
            throw new BuildFiles.Unreadable("does not say which version of its format it is");
        }
        if (version > FORMAT_VERSION) {
            throw new BuildFiles.Unreadable("is version " + version + " of its format and only versions 1 to "
                + FORMAT_VERSION + " are read");
        }
        boolean nested = version == FORMAT_VERSION;
        CompoundTag blocks = nested ? schematic.getCompoundOrEmpty(BLOCKS_KEY) : schematic;
        CompoundTag paletteTag = blocks.getCompoundOrEmpty(PALETTE_KEY);
        byte[] packed = blocks.getByteArray(nested ? DATA_KEY : ROOT_DATA_KEY).orElse(null);
        if (paletteTag.isEmpty() || packed == null) {
            throw new BuildFiles.Unreadable("holds no blocks");
        }
        int sizeX = Short.toUnsignedInt(schematic.getShortOr(WIDTH_KEY, (short) 0));
        int sizeY = Short.toUnsignedInt(schematic.getShortOr(HEIGHT_KEY, (short) 0));
        int sizeZ = Short.toUnsignedInt(schematic.getShortOr(LENGTH_KEY, (short) 0));
        BuildFiles.checkSize(sizeX, sizeY, sizeZ);

        Blueprint.Builder builder = new Blueprint.Builder(name, offset(schematic, nested),
            new Vec3i(sizeX, sizeY, sizeZ), paletteTag.size());
        int[] kinds = kinds(paletteTag, builder, states);
        ByteBuf buffer = Unpooled.wrappedBuffer(packed);
        int total = sizeX * sizeY * sizeZ;
        for (int cell = 0; cell < total; cell++) {
            if (!buffer.isReadable()) {
                throw new BuildFiles.Unreadable("stops before the last of its blocks");
            }
            int id;
            try {
                id = VarInt.read(buffer);
            } catch (RuntimeException e) {
                // A number that never ends or stops half way through.
                throw new BuildFiles.Unreadable("holds block data that cannot be read");
            }
            if (id < 0 || id >= kinds.length) {
                throw new BuildFiles.Unreadable("names a block its palette does not hold");
            }
            builder.setAt(cell, kinds[id]);
        }
        return builder.build();
    }

    // The palette index of the build for every id the file uses. An id the file never names
    // leaves its cells alone.
    private static int[] kinds(CompoundTag paletteTag, Blueprint.Builder builder, SavedStates states)
        throws BuildFiles.Unreadable {
        int highest = -1;
        for (String state : paletteTag.keySet()) {
            highest = Math.max(highest, paletteTag.getIntOr(state, -1));
        }
        if (highest > HIGHEST_PALETTE_ID) {
            throw new BuildFiles.Unreadable("gives a block the number " + highest + " in its palette and the highest"
                + " it may use is " + HIGHEST_PALETTE_ID);
        }
        int[] kinds = new int[highest + 1];
        Arrays.fill(kinds, Blueprint.NO_SAY);
        for (String state : paletteTag.keySet()) {
            int id = paletteTag.getIntOr(state, -1);
            if (id >= 0) {
                kinds[id] = SavedStates.kindIn(builder, states.read(state));
            }
        }
        return kinds;
    }

    private static Vec3i offset(CompoundTag schematic, boolean nested) {
        CompoundTag metadata = schematic.getCompoundOrEmpty(METADATA_KEY);
        if (!nested && metadata.contains(COPY_OFFSET_KEYS[0])) {
            return new Vec3i(metadata.getIntOr(COPY_OFFSET_KEYS[0], 0), metadata.getIntOr(COPY_OFFSET_KEYS[1], 0),
                metadata.getIntOr(COPY_OFFSET_KEYS[2], 0));
        }
        int[] offset = schematic.getIntArray(OFFSET_KEY).orElse(null);
        return offset == null || offset.length != 3 ? Vec3i.ZERO : new Vec3i(offset[0], offset[1], offset[2]);
    }
}
