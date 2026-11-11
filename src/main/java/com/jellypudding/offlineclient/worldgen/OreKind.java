package com.jellypudding.offlineclient.worldgen;

// The ores a player can ask to see. Stone and deepslate forms of one ore share a kind.
// The colour is the one every ore finder draws the kind in until the player picks another.
// A large vein is made of the blocks of a plain ore and is still a pick of its own.
public enum OreKind {
    COAL("Coal", 0xFF909090),
    IRON("Iron", 0xFFD8C0A8),
    COPPER("Copper", 0xFFFF8050),
    GOLD("Gold", 0xFFFFD040),
    REDSTONE("Redstone", 0xFFFF4040),
    LAPIS("Lapis", 0xFF4060FF),
    DIAMOND("Diamond", 0xFF40E0FF),
    EMERALD("Emerald", 0xFF40FF80),
    NETHER_GOLD("Nether gold", 0xFFFFD040),
    QUARTZ("Quartz", 0xFFF0F0E0),
    ANCIENT_DEBRIS("Ancient debris", 0xFFB08060),
    IRON_VEIN("Iron vein", 0xFF90A8C8, IRON),
    COPPER_VEIN("Copper vein", 0xFF50C8A8, COPPER);

    private final String label;
    private final int colour;
    private final OreKind ore;

    OreKind(String label, int colour) {
        this(label, colour, null);
    }

    OreKind(String label, int colour, OreKind ore) {
        this.label = label;
        this.colour = colour;
        this.ore = ore == null ? this : ore;
    }

    public String label() {
        return label;
    }

    public int colour() {
        return colour;
    }

    // The ore whose blocks this kind is found as. A plain ore answers itself.
    public OreKind ore() {
        return ore;
    }
}
