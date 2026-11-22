package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class EntitiesNear extends Condition {

    enum Count { TOGETHER, EACH }

    private final RegistryListSetting<EntityType<?>> kinds;
    private final EnumSetting<Count> count;
    private final NumberSetting combinedLimit;
    private final NumberSetting eachLimit;
    private final NumberSetting range;

    private final Map<EntityType<?>, Integer> tally = new HashMap<>();
    private String reason = "";

    EntitiesNear(Frame frame) {
        super(frame, "Entities near");
        kinds = add(new RegistryListSetting<>(frame.name("Entities near"),
            "Too many entities of these kinds gather close to you. Click to pick the kinds.",
            BuiltInRegistries.ENTITY_TYPE, List.of(EntityTypes.END_CRYSTAL)));
        count = add(new EnumSetting<>(frame.name("Entity counting"),
            "How the listed entities are counted.", Count.TOGETHER)
            .describe(Count.TOGETHER, "Every listed kind counts towards one total.")
            .describe(Count.EACH, "Each kind is counted on its own."));
        combinedLimit = add(new NumberSetting(frame.name("Combined limit"),
            "How many listed entities in total it takes.", 10, 1, 32, 1)
            .under(count, Count.TOGETHER));
        eachLimit = add(new NumberSetting(frame.name("Each limit"),
            "How many entities of one kind it takes.", 2, 1, 16, 1)
            .under(count, Count.EACH));
        range = add(new NumberSetting(frame.name("Entity range"),
            "How close a listed entity has to be to count.", 5, 1, 64, 1, " blocks").min(0.5));
    }

    @Override
    public boolean holds() {
        if (kinds.size() == 0) {
            return false;
        }
        tally.clear();
        int total = 0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity != mc.player && kinds.contains(entity.getType())
                && entity.distanceTo(mc.player) <= range.getValue()) {
                total++;
                tally.merge(entity.getType(), 1, Integer::sum);
            }
        }
        if (count.is(Count.TOGETHER)) {
            return met(total, combinedLimit, total + " of the listed entities");
        }
        for (Map.Entry<EntityType<?>, Integer> entry : tally.entrySet()) {
            if (met(entry.getValue(), eachLimit, Tally.counted(entry.getValue(), ChatUtil.words(entry.getKey())))) {
                return true;
            }
        }
        return false;
    }

    private boolean met(int found, NumberSetting limit, String words) {
        if (found < limit.getInt()) {
            return false;
        }
        reason = ChatUtil.capitalised(words) + (found == 1 ? " was" : " were") + " within "
            + range.getValueString() + ".";
        return true;
    }

    @Override
    public String reason() {
        return reason;
    }

    @Override
    public String problem() {
        return kinds.size() == 0 ? "Entities near has no kind of entity picked." : null;
    }
}
