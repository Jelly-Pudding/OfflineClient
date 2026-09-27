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
import java.util.LinkedHashMap;
import java.util.Map;

// A schematic in version three of the Sponge format that WorldEdit and Litematica read. It is
// gzipped NBT with a Schematic compound inside a root with no name. Blocks go in the order the
// format keeps them. X runs fastest then Z then Y.
public final class SpongeSchematic {

    private static final int FORMAT_VERSION = 3;
    private static final String EXTENSION = ".schem";

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
        return DataFiles.path("schematics", DataFiles.safeName(name) + EXTENSION);
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

    // The offset runs from the spot it was saved at to the lowest corner. WorldEdit pastes
    // it the same way round from wherever you stand.
    public CompoundTag toTag(String name, String author, Vec3i offset) {
        CompoundTag paletteTag = new CompoundTag();
        palette.forEach((state, index) -> paletteTag.putInt(BlockStateParser.serialize(state), index));
        CompoundTag blocks = new CompoundTag();
        blocks.put("Palette", paletteTag);
        blocks.putByteArray("Data", ByteBufUtil.getBytes(data));
        blocks.put("BlockEntities", blockEntities);

        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", name);
        metadata.putString("Author", author);
        metadata.putLong("Date", System.currentTimeMillis());

        // The sides are unsigned shorts. A cast keeps the bits of anything up to 65535.
        CompoundTag schematic = new CompoundTag();
        schematic.putInt("Version", FORMAT_VERSION);
        schematic.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        schematic.put("Metadata", metadata);
        schematic.putShort("Width", (short) width);
        schematic.putShort("Height", (short) height);
        schematic.putShort("Length", (short) length);
        schematic.putIntArray("Offset", new int[] {offset.getX(), offset.getY(), offset.getZ()});
        schematic.put("Blocks", blocks);

        CompoundTag root = new CompoundTag();
        root.put("Schematic", schematic);
        return root;
    }

    // False after logging why the file could not be written.
    public static boolean write(CompoundTag root, Path path) {
        return DataFiles.writeSafely(path, temp -> NbtIo.writeCompressed(root, temp));
    }
}
