package com.jellypudding.offlineclient.util;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

// Every kind of clue BaseFinder marks a chunk for. The noun names the finds and their waypoints
// and keeps the quiet time of each kind apart. Where the clue came from picks its colour.
public enum BaseClue {

    SIGN("sign", Source.BLOCKS),
    PORTAL("portal", Source.BLOCKS),
    BUBBLES("bubbles", Source.BLOCKS),
    SKY("sky build", Source.BLOCKS),
    BEDROCK("bedrock", Source.BLOCKS),
    ROOF("roof build", Source.BLOCKS),
    SPAWNER("spawner", Source.BLOCKS),
    RARE("rare blocks", Source.BLOCKS),
    BUILDING("building blocks", Source.BLOCKS),
    WORKSTATIONS("workstations", Source.BLOCKS),
    STORAGE("storage blocks", Source.BLOCKS),
    FURNISHING("furnishings", Source.BLOCKS),
    REDSTONE("redstone parts", Source.BLOCKS),
    CUSTOM("custom blocks", Source.BLOCKS),
    DECORATION("decoration", Source.ENTITIES),
    PEARL("pearl", Source.ENTITIES),
    NAMED("named mob", Source.ENTITIES),
    VILLAGER("villager", Source.ENTITIES),
    BOAT("boat", Source.ENTITIES),
    PET("pet", Source.ENTITIES),
    CROWD("crowd", Source.ENTITIES),
    MARK("mark", Source.HAND);

    public enum Source { BLOCKS, ENTITIES, HAND }

    private static final Map<String, BaseClue> BY_NOUN = Arrays.stream(values())
        .collect(Collectors.toUnmodifiableMap(BaseClue::noun, clue -> clue));

    private final String noun;
    private final Source source;

    BaseClue(String noun, Source source) {
        this.noun = noun;
        this.source = source;
    }

    public String noun() {
        return noun;
    }

    public Source source() {
        return source;
    }

    // The clue a saved find was made for. Null for a noun no clue carries.
    public static BaseClue byNoun(String noun) {
        return BY_NOUN.get(noun);
    }
}
