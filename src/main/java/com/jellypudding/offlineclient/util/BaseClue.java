package com.jellypudding.offlineclient.util;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

// The noun names the finds and their waypoints and keeps the quiet time of each kind apart. Where
// the clue came from picks its colour.
public enum BaseClue {

    SIGN("sign", Source.BLOCKS),
    PORTAL("portal", Source.BLOCKS),
    BUBBLES("bubbles", Source.BLOCKS),
    SKY("sky build", Source.BLOCKS),
    BEDROCK("bedrock", Source.BLOCKS),
    ROOF("roof build", Source.BLOCKS),
    SPAWNER("spawner", Source.BLOCKS),
    BED("bed", Source.BLOCKS),
    ROOM("bedrock room", Source.BLOCKS),
    PLAYER_BLOCKS("player blocks", Source.BLOCKS),
    SIDEWAYS("sideways deepslate", Source.BLOCKS),
    GROWTH("grown plants", Source.BLOCKS),
    DECORATION("decoration", Source.ENTITIES),
    PEARL("pearl", Source.ENTITIES),
    NAMED("named mob", Source.ENTITIES),
    VILLAGER("villager", Source.ENTITIES),
    BOAT("boat", Source.ENTITIES),
    PET("pet", Source.ENTITIES),
    CROWD("crowd", Source.ENTITIES),
    DROPS("dropped blocks", Source.ENTITIES),
    LOST_MOB("lost mob", Source.ENTITIES),
    ENDERMITE("endermite", Source.ENTITIES),
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

    public static BaseClue byNoun(String noun) {
        return BY_NOUN.get(noun);
    }
}
