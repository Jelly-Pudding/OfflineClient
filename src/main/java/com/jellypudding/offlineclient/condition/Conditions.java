package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.event.events.TotemPopEvent;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

// The conditions one module or one rule watches. A pick list holds the ones in use and each
// picked one lists its rows beneath it. Nothing of a condition that is not picked shows.
public final class Conditions {

    private final ChoiceListSetting list;
    private final List<String> names = new ArrayList<>();
    private final List<Condition> offered = new ArrayList<>();
    private final List<Setting<?>> settings = new ArrayList<>();
    // The one condition that is told of a pop as it happens. Every list offers it.
    private TotemPops pops;

    // The picked ones in the order they are offered.
    private List<Condition> picked = List.of();
    private final List<Condition> holding = new ArrayList<>();

    private Conditions(String listName, String description, String tail,
                       List<Function<Frame, Condition>> offer, String[] startPicked) {
        list = new ChoiceListSetting(listName, description, () -> names, List.of(startPicked));
        settings.add(list);
        Frame frame = new Frame(list, tail);
        for (Function<Frame, Condition> maker : offer) {
            Condition condition = maker.apply(frame);
            offered.add(condition);
            names.add(condition.name());
            if (condition instanceof TotemPops totemPops) {
                pops = totemPops;
            }
            settings.addAll(condition.rows());
        }
        list.onChange(this::repick);
        repick();
    }

    // Everything there is for a rule that may be set off by anything. The tail ends every row
    // name and keeps the rows of one rule apart from the next.
    public static Conditions forRule(String listName, String description, String tail) {
        return new Conditions(listName, description, tail, Catalogue.all(), new String[0]);
    }

    // The conditions that are a reason to get away. The names given start picked.
    public static Conditions forEscape(String listName, String description, String... startPicked) {
        return new Conditions(listName, description, "", Catalogue.dangers(), startPicked);
    }

    // The pick list and then the rows of every condition.
    public Setting<?>[] settings() {
        return settings.toArray(new Setting<?>[0]);
    }

    // The pick list. Whatever shows with the rule hangs under it.
    public ChoiceListSetting list() {
        return list;
    }

    // A condition that has just been picked starts afresh. What happened whilst nobody watched
    // it is no news.
    private void repick() {
        List<Condition> now = new ArrayList<>();
        for (Condition condition : offered) {
            if (!list.contains(condition.name())) {
                continue;
            }
            if (!picked.contains(condition)) {
                condition.start();
            }
            now.add(condition);
        }
        picked = now;
        holding.retainAll(now);
    }

    // Call as the owner goes on or begins to watch again. Every condition forgets what happened
    // before. Clocks start again and a hit or a kill from earlier is no news.
    public void start() {
        holding.clear();
        for (Condition condition : picked) {
            condition.start();
        }
    }

    // Says in chat why a picked condition can never hold as it is set up.
    public void tellProblems() {
        for (Condition condition : picked) {
            String problem = condition.problem();
            if (problem != null) {
                ChatUtil.error(problem);
            }
        }
    }

    // Passes on a totem pop the owner heard of. A pop of yours that is not your death is met
    // by Totem pops on the next tick.
    public void heard(TotemPopEvent event) {
        if (!event.isDeath() && event.getPlayer() == Condition.mc.player && picked.contains(pops)) {
            pops.pop(event.getCount());
        }
    }

    // The same for an owner that acts on the pop itself. Hands back Totem pops when the pop
    // met it and null otherwise. The next tick does not see that pop again.
    public Condition popped(TotemPopEvent event) {
        heard(event);
        return picked.contains(pops) && pops.holds() ? pops : null;
    }

    // True when one of the picked can be met with no world. Only leaving the server can.
    public boolean watchesOutside() {
        for (Condition condition : picked) {
            if (condition.watchesOutside()) {
                return true;
            }
        }
        return false;
    }

    // Asks every picked condition once. Call it once a tick whilst in a world.
    public void tick() {
        holding.clear();
        for (Condition condition : picked) {
            if (condition.holds()) {
                holding.add(condition);
            }
        }
    }

    // The same for a tick with no world. Only leaving the server can hold there.
    public void tickOutside() {
        holding.clear();
        for (Condition condition : picked) {
            if (condition.holdsOutside()) {
                holding.add(condition);
            }
        }
    }

    // How many are picked.
    public int size() {
        return picked.size();
    }

    // True when one of the picked held on the last tick.
    public boolean anyHolds() {
        return !holding.isEmpty();
    }

    // True when every one of the picked held on the last tick. Never with nothing picked.
    public boolean allHold() {
        return !picked.isEmpty() && holding.size() == picked.size();
    }

    // The first of the picked that held on the last tick. Null when none did.
    public Condition firstHolding() {
        return holding.isEmpty() ? null : holding.getFirst();
    }

    // True when one of the picked is over in a tick and has no end to wait for.
    public boolean hasMoment() {
        for (Condition condition : picked) {
            if (condition.isMoment()) {
                return true;
            }
        }
        return false;
    }

    // A few words for a module suffix. What the one picked condition is set to or how many
    // are picked. Null with none.
    public String summary() {
        if (picked.isEmpty()) {
            return null;
        }
        String shown = picked.size() == 1 ? picked.getFirst().shown() : "";
        return shown.isEmpty() ? String.valueOf(picked.size()) : shown;
    }
}
