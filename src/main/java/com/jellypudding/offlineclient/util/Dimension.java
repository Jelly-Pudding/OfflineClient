package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

// The three dimensions of the game by the names players know. A dimension a server added has no
// entry here.
public enum Dimension {
    OVERWORLD(Level.OVERWORLD),
    NETHER(Level.NETHER),
    END(Level.END);

    // Eight overworld blocks to one nether block.
    private static final int NETHER_SCALE = 8;

    private final ResourceKey<Level> key;
    private final String id;

    Dimension(ResourceKey<Level> key) {
        this.key = key;
        this.id = key.identifier().toString();
    }

    // The id the game knows it by. Saved data files a place under it.
    public String id() {
        return id;
    }

    // The word a command takes for it such as nether.
    public String commandWord() {
        return name().toLowerCase(Locale.ROOT);
    }

    // As a sentence says it such as the Nether.
    public String words() {
        return "the " + ServerInfo.dimensionName(id());
    }

    // True whilst you are in it.
    public boolean isHere() {
        ClientLevel level = OfflineClient.MC.level;
        return level != null && level.dimension() == key;
    }

    // The one you are in. Null outside a world and in a dimension a server added.
    public static Dimension here() {
        ClientLevel level = OfflineClient.MC.level;
        return level == null ? null : of(level.dimension());
    }

    // Null for the key of a dimension a server added.
    public static Dimension of(ResourceKey<Level> key) {
        for (Dimension dimension : values()) {
            if (dimension.key == key) {
                return dimension;
            }
        }
        return null;
    }

    // Null for the id of a dimension a server added.
    public static Dimension of(String id) {
        for (Dimension dimension : values()) {
            if (dimension.id().equals(id)) {
                return dimension;
            }
        }
        return null;
    }

    // Null for a word that names none of them.
    public static Dimension named(String word) {
        for (Dimension dimension : values()) {
            if (dimension.commandWord().equalsIgnoreCase(word)) {
                return dimension;
            }
        }
        return null;
    }

    // The block a coordinate lands in on the other side of a nether portal. It shrinks going in
    // and grows coming out. The game scales the exact position and only then takes the block.
    public static int acrossPortal(double coordinate, boolean intoNether) {
        return Mth.floor(intoNether ? coordinate / NETHER_SCALE : coordinate * NETHER_SCALE);
    }

    // The dimension a nether portal leads to from this one. Null in the End and anywhere else
    // a nether portal does not work.
    public static String otherSide(String dimension) {
        return dimension.equals(OVERWORLD.id) ? NETHER.id : dimension.equals(NETHER.id) ? OVERWORLD.id : null;
    }

    // The block in the other dimension that matches this exact spot at the same height. Null
    // where there is no other side.
    public static BlockPos acrossPortal(Vec3 position, String dimension) {
        String other = otherSide(dimension);
        if (other == null) {
            return null;
        }
        boolean intoNether = other.equals(NETHER.id);
        return new BlockPos(acrossPortal(position.x(), intoNether), Mth.floor(position.y()),
            acrossPortal(position.z(), intoNether));
    }
}
