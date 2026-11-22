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

// Every module that acts on conditions holds its own copy of each one. A row only shows whilst its
// condition is picked.
public abstract class Condition {

    protected static final Minecraft mc = OfflineClient.MC;

    private final Frame frame;
    private final String name;
    private final List<Setting<?>> rows = new ArrayList<>();

    protected Condition(Frame frame, String name) {
        this.frame = frame;
        this.name = name;
    }

    public final String name() {
        return name;
    }

    // The first carries the name of the condition.
    final List<Setting<?>> rows() {
        return rows;
    }

    // The first row hangs under the pick list. A later one hangs under the first unless it came
    // with a parent of its own.
    protected final <S extends Setting<?>> S add(S setting) {
        if (rows.isEmpty()) {
            setting.under(frame.list(), () -> frame.list().contains(name));
        } else if (setting.getParent() == null) {
            setting.under(rows.getFirst(), () -> true);
        }
        rows.add(setting);
        return setting;
    }

    // Unticking it takes the condition off the list.
    protected final BoolSetting ticked(String description) {
        return add(new Picked(frame.name(name), description + " Untick it to take it off the list.",
            frame.list(), name));
    }

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

    protected static String writeHere(TextSetting place, EnumSetting<Within> within) {
        place.setValue(BlockUtil.text(mc.player.blockPosition()));
        within.setValue(Within.here());
        return "Set to " + place.getValue() + " " + within.getValue().words();
    }

    // Asked once a tick whilst it is picked and you are in a world.
    public abstract boolean holds();

    public abstract String reason();

    public boolean watchesOutside() {
        return false;
    }

    public boolean holdsOutside() {
        return false;
    }

    // True for something over in a tick such as a death.
    public boolean isMoment() {
        return false;
    }

    // Called as the owner goes on and as the condition is picked. There may be no world yet.
    public void start() {
    }

    public String problem() {
        return null;
    }

    final String shown() {
        Setting<?> first = rows.getFirst();
        return first instanceof Picked ? "" : first.getValueString();
    }
}
