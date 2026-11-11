package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// One thing that can be true of you or the world such as low health or a player close by. It
// owns its rows and its words. Every module that acts on conditions holds its own copy of each
// one through a Conditions list and a row only shows whilst its condition is picked.
public abstract class Condition {

    protected static final Minecraft mc = OfflineClient.MC;

    private final Frame frame;
    private final String name;
    private final List<Setting<?>> rows = new ArrayList<>();

    protected Condition(Frame frame, String name) {
        this.frame = frame;
        this.name = name;
    }

    // What the pick list calls it.
    public final String name() {
        return name;
    }

    // The rows in the order they are listed. The first carries the name of the condition.
    final List<Setting<?>> rows() {
        return rows;
    }

    // Adds a row. The first one hangs under the pick list and shows whilst the condition is
    // picked. A later one hangs under the first unless it came with a parent of its own.
    protected final <S extends Setting<?>> S add(S setting) {
        if (rows.isEmpty()) {
            setting.under(frame.list(), () -> frame.list().contains(name));
        } else if (setting.getParent() == null) {
            setting.under(rows.getFirst(), () -> true);
        }
        rows.add(setting);
        return setting;
    }

    // The first row of a condition with nothing to set. It stands for the pick itself and
    // unticking it takes the condition off the list.
    protected final BoolSetting ticked(String description) {
        return add(new Picked(frame.name(name), description + " Untick it to take it off the list.",
            frame.list(), name));
    }

    // A row that fills something in from where you stand such as a corner of a box. The words
    // it hands back show on the row.
    protected static ActionSetting fromHere(String name, String description, Supplier<String> fill) {
        return new ActionSetting(name, description, () -> {
            if (mc.player == null) {
                return "Join a world first";
            }
            String done = fill.get();
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
            return done;
        });
    }

    // Fills a place row with the block you stand in and its dimension row with the one you are in.
    // The words it hands back are for a row made by fromHere.
    protected static String writeHere(TextSetting place, EnumSetting<Within> within) {
        place.setValue(BlockUtil.text(mc.player.blockPosition()));
        within.setValue(Within.here());
        return "Set to " + place.getValue() + " " + within.getValue().words();
    }

    // True on a tick the condition is met. Asked once a tick whilst it is picked and you are
    // in a world.
    public abstract boolean holds();

    // What was met on the tick it last held as a sentence such as Health was at or below 3 hearts.
    public abstract String reason();

    // True for the one thing that can happen with no world.
    public boolean watchesOutside() {
        return false;
    }

    // True on a tick with no world on which that thing happened.
    public boolean holdsOutside() {
        return false;
    }

    // True for something over in a tick such as a death. It has no end to wait for.
    public boolean isMoment() {
        return false;
    }

    // Forgets the past. Called as the owner goes on and as the condition is picked. There may
    // be no world yet.
    public void start() {
    }

    // Why the condition can never hold as it is set up. Null whilst nothing is wrong.
    public String problem() {
        return null;
    }

    // What the first row reads such as 3 hearts. Empty for a condition with nothing to set.
    final String shown() {
        Setting<?> first = rows.getFirst();
        return first instanceof Picked ? "" : first.getValueString();
    }
}
