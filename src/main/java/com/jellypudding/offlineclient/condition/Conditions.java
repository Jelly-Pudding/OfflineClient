package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.event.events.TotemPopEvent;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class Conditions {

    private final ChoiceListSetting list;
    private final List<String> names = new ArrayList<>();
    private final List<Condition> offered = new ArrayList<>();
    private final List<Setting<?>> settings = new ArrayList<>();
    // The one condition that is told of a pop as it happens. Every list offers it.
    private TotemPops pops;

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

    // The tail ends every row name and keeps the rows of one rule apart from the next.
    public static Conditions forRule(String listName, String description, String tail) {
        return new Conditions(listName, description, tail, Catalogue.all(), new String[0]);
    }

    public static Conditions forEscape(String listName, String description, String... startPicked) {
        return new Conditions(listName, description, "", Catalogue.dangers(), startPicked);
    }

    public Setting<?>[] settings() {
        return settings.toArray(new Setting<?>[0]);
    }

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

    // Call as the owner goes on or begins to watch again.
    public void start() {
        holding.clear();
        for (Condition condition : picked) {
            condition.start();
        }
    }

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

    // For an owner that acts on the pop itself. The next tick does not see that pop again.
    public Condition popped(TotemPopEvent event) {
        heard(event);
        return picked.contains(pops) && pops.holds() ? pops : null;
    }

    public boolean watchesOutside() {
        for (Condition condition : picked) {
            if (condition.watchesOutside()) {
                return true;
            }
        }
        return false;
    }

    // Call it once a tick whilst in a world.
    public void tick() {
        holding.clear();
        for (Condition condition : picked) {
            if (condition.holds()) {
                holding.add(condition);
            }
        }
    }

    public void tickOutside() {
        holding.clear();
        for (Condition condition : picked) {
            if (condition.holdsOutside()) {
                holding.add(condition);
            }
        }
    }

    public int size() {
        return picked.size();
    }

    public boolean anyHolds() {
        return !holding.isEmpty();
    }

    // Never with nothing picked.
    public boolean allHold() {
        return !picked.isEmpty() && holding.size() == picked.size();
    }

    public Condition firstHolding() {
        return holding.isEmpty() ? null : holding.getFirst();
    }

    public boolean hasMoment() {
        for (Condition condition : picked) {
            if (condition.isMoment()) {
                return true;
            }
        }
        return false;
    }

    public String summary() {
        if (picked.isEmpty()) {
            return null;
        }
        String shown = picked.size() == 1 ? picked.getFirst().shown() : "";
        return shown.isEmpty() ? String.valueOf(picked.size()) : shown;
    }
}
