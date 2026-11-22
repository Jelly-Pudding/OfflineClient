package com.jellypudding.offlineclient.config;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.TreeSet;

// A file names the game version that wrote it and the game's own data fixer brings each state up to
// date.
public final class SavedStates {

    // The game writes a state under these two keys. Before this data version it wrote the
    // capitalised pair and the fixer only knows a state by the keys of its version.
    private static final int LOWER_CASE_KEYS_VERSION = 5006;
    private static final String ID_KEY = "id";
    private static final String PROPERTIES_KEY = "properties";

    private static final int CURRENT_VERSION = SharedConstants.getCurrentVersion().dataVersion().version();

    private final int fileVersion;
    private final Set<String> unknown = new TreeSet<>();

    // A file that names no version counts as written by this game.
    public SavedStates(int dataVersion) {
        this.fileVersion = dataVersion > 0 ? dataVersion : CURRENT_VERSION;
    }

    public BlockState read(CompoundTag saved) {
        String name = saved.getString(ID_KEY).or(() -> saved.getString(NbtUtils.LEGACY_BLOCK_STATE_ID_TAG))
            .orElse("");
        CompoundTag properties = saved.getCompound(PROPERTIES_KEY)
            .or(() -> saved.getCompound(NbtUtils.LEGACY_BLOCKSTATE_PROPERTY_TAG)).orElse(null);
        return resolve(name, properties);
    }

    public BlockState read(String text) {
        int open = text.indexOf('[');
        if (open == -1 || !text.endsWith("]")) {
            return resolve(text, null);
        }
        CompoundTag properties = new CompoundTag();
        for (String pair : text.substring(open + 1, text.length() - 1).split(",")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                properties.putString(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
            }
        }
        return resolve(text.substring(0, open), properties);
    }

    public Set<String> unknown() {
        return unknown;
    }

    // A block this game lacks and the structure void leave the cell alone. Every kind of air is the
    // same wish for an empty cell.
    public static int kindIn(Blueprint.Builder builder, BlockState state) {
        if (state == null || state.is(Blocks.STRUCTURE_VOID)) {
            return Blueprint.NO_SAY;
        }
        return builder.kind(state.isAir() ? Blocks.AIR.defaultBlockState() : state);
    }

    private BlockState resolve(String name, CompoundTag properties) {
        boolean lowerCase = fileVersion >= LOWER_CASE_KEYS_VERSION;
        CompoundTag state = new CompoundTag();
        state.putString(lowerCase ? ID_KEY : NbtUtils.LEGACY_BLOCK_STATE_ID_TAG, name.trim());
        if (properties != null) {
            state.put(lowerCase ? PROPERTIES_KEY : NbtUtils.LEGACY_BLOCKSTATE_PROPERTY_TAG, properties);
        }
        if (fileVersion < CURRENT_VERSION) {
            state = (CompoundTag) DataFixers.getDataFixer().update(References.BLOCK_STATE,
                new Dynamic<>(NbtOps.INSTANCE, state), fileVersion, CURRENT_VERSION).getValue();
        }
        String id = state.getStringOr(ID_KEY, "");
        Identifier key = Identifier.tryParse(id);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            if (!id.isEmpty()) {
                unknown.add(id);
            }
            return null;
        }
        return NbtUtils.readBlockState(BuiltInRegistries.BLOCK, state);
    }
}
