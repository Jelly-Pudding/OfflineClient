package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Strangers;
import net.minecraft.world.entity.player.Player;

import java.util.function.Predicate;

final class PlayerNear extends Condition {

    private static final double IN_VIEW = Double.MAX_VALUE;

    // Null for the kind that counts anyone in view.
    private final NumberSetting range;
    private final BoolSetting enemiesOnly;
    private final Predicate<Player> doing;
    private final String did;
    private String reason = "";

    private PlayerNear(Frame frame, String name, NumberSetting range, String tickedDescription,
                       Predicate<Player> doing, String did) {
        super(frame, name);
        this.range = range;
        this.doing = doing;
        this.did = did;
        if (range == null) {
            ticked(tickedDescription);
        } else {
            add(range);
        }
        enemiesOnly = add(new BoolSetting(frame.name(name + " enemies only"),
            "Only players on your enemy list count.", false));
    }

    static PlayerNear inView(Frame frame) {
        return new PlayerNear(frame, "Player in view", null,
            "A player who is not a friend comes into view. Fake players that AntiBot spots never count.",
            player -> true, "came into view");
    }

    static PlayerNear inRange(Frame frame) {
        return new PlayerNear(frame, "Player near", new NumberSetting(frame.name("Player near"),
            "A player who is not a friend is within this many blocks.", 16, 1, 128, 1, " blocks").min(0.5),
            null, player -> true, "came within");
    }

    static PlayerNear withBed(Frame frame) {
        return new PlayerNear(frame, "Bed carrier", new NumberSetting(frame.name("Bed carrier"),
            "A player who is not a friend is within this many blocks with a bed in hand.",
            8, 1, 32, 1, " blocks").min(0.5), null, Strangers::holdsBed, "held a bed within");
    }

    @Override
    public boolean holds() {
        Player found = Strangers.nearest(range == null ? IN_VIEW : range.getValue(), enemiesOnly.isOn(), doing);
        if (found == null) {
            return false;
        }
        reason = EntityUtil.nameOf(found) + " " + did + (range == null ? "." : " " + range.getValueString() + ".");
        return true;
    }

    @Override
    public String reason() {
        return reason;
    }
}
