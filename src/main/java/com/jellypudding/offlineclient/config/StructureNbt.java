package com.jellypudding.offlineclient.config;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

// Gzipped NBT with a palette of block states and a list of blocks. A cell the list leaves out is
// left alone. A file with several palettes is read with its first.
final class StructureNbt {

    static final String EXTENSION = ".nbt";

    private StructureNbt() {
    }

    static boolean holds(CompoundTag root) {
        return root.contains(StructureTemplate.BLOCKS_TAG)
            && (root.contains(StructureTemplate.PALETTE_TAG) || root.contains(StructureTemplate.PALETTE_LIST_TAG));
    }

    static int dataVersion(CompoundTag root) {
        return root.getIntOr(SharedConstants.DATA_VERSION_TAG, 0);
    }

    static Blueprint read(String name, CompoundTag root, SavedStates states) throws BuildFiles.Unreadable {
        ListTag blocks = root.getListOrEmpty(StructureTemplate.BLOCKS_TAG);
        ListTag palette = root.getList(StructureTemplate.PALETTE_LIST_TAG).map(all -> all.getListOrEmpty(0))
            .orElseGet(() -> root.getListOrEmpty(StructureTemplate.PALETTE_TAG));
        if (blocks.isEmpty() || palette.isEmpty()) {
            throw new BuildFiles.Unreadable("holds no blocks");
        }
        Blueprint.Box box = new Blueprint.Box();
        for (int i = 0; i < blocks.size(); i++) {
            ListTag at = blocks.getCompoundOrEmpty(i).getListOrEmpty(StructureTemplate.BLOCK_TAG_POS);
            box.add(at.getIntOr(0, 0), at.getIntOr(1, 0), at.getIntOr(2, 0));
        }
        BuildFiles.checkSize(box.sizeX(), box.sizeY(), box.sizeZ());
        Blueprint.Builder builder = new Blueprint.Builder(name, box.min(), box.size(), palette.size());
        int[] kinds = new int[palette.size()];
        for (int id = 0; id < kinds.length; id++) {
            kinds[id] = SavedStates.kindIn(builder, states.read(palette.getCompoundOrEmpty(id)));
        }
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompoundOrEmpty(i);
            ListTag at = block.getListOrEmpty(StructureTemplate.BLOCK_TAG_POS);
            int id = block.getIntOr(StructureTemplate.BLOCK_TAG_STATE, 0);
            if (id < 0 || id >= kinds.length) {
                throw new BuildFiles.Unreadable("names a block its palette does not hold");
            }
            builder.set(at.getIntOr(0, 0), at.getIntOr(1, 0), at.getIntOr(2, 0), kinds[id]);
        }
        return builder.build();
    }
}
