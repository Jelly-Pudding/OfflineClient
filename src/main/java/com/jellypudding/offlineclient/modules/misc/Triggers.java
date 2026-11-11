package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.condition.Conditions;
import com.jellypudding.offlineclient.config.MacroStore;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.event.events.TotemPopEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.Bind;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatPace;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.LiveText;
import com.jellypudding.offlineclient.util.TextMarkup;
import net.minecraft.SharedConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Each rule acts once as its conditions start to hold. Switch back returns the modules it
// changed to how they were once the conditions end. A rule that takes in something over in a
// tick such as a death has no end to wait for and Lasts for times it instead. What held as you
// left a server still holds when you are back and sets nothing off a second time.
public final class Triggers extends Module {

    // How many rules can be set up one by one.
    private static final int SLOTS = 8;

    // A line the server would have counted as spam is tried again this much later.
    private static final int RETRY_TICKS = SharedConstants.TICKS_PER_SECOND;

    public enum Match { ALL, ANY }

    public enum Deed { SWITCH_MODULES, SEND_A_LINE, RUN_MACROS }

    public enum Action { SWITCH_ON, SWITCH_OFF, TOGGLE }

    // The settings for one rule. The rule row folds the rest away.
    private final class Rule {

        final int slot;
        final Conditions when;
        final EnumSetting<Match> match;
        final EnumSetting<Deed> deed;
        final ChoiceListSetting modules;
        final EnumSetting<Action> action;
        final BoolSetting switchBack;
        final NumberSetting lastsFor;
        final TextSetting line;
        final ChoiceListSetting macros;
        final NumberSetting againAfter;

        // Each module the rule changed and whether it was on before.
        final Map<Module, Boolean> before = new LinkedHashMap<>();
        // True once the rule has something to watch and something to do and has been armed.
        boolean live;
        boolean holds;
        // Ticks until a rule with nothing to wait for puts its modules back. Nought whilst none run.
        int timeLeft;
        // Ticks until a rule that still holds sends its line or runs its macros again.
        int againIn;

        Rule(int number) {
            slot = number - 1;
            String tail = " " + number;
            when = Conditions.forRule("Rule" + tail,
                "What sets this rule off. Click to pick the conditions. Each one you pick lists its own rows"
                    + " below.", tail);
            when.list().visibleWhen(() -> slot < ruleCount.getInt());
            match = new EnumSetting<>("Match" + tail, "How many of the picked conditions it takes.", Match.ALL)
                .describe(Match.ALL, "Every picked condition has to hold at the same time.")
                .describe(Match.ANY, "One picked condition is enough.")
                .under(when.list(), () -> when.size() > 1);
            deed = new EnumSetting<>("Do" + tail, "What the rule does.", Deed.SWITCH_MODULES)
                .describe(Deed.SWITCH_MODULES, "Switches the modules you pick.")
                .describe(Deed.SEND_A_LINE, "Sends a chat line or runs a command.")
                .describe(Deed.RUN_MACROS, "Runs the macros you pick.")
                .under(when.list(), () -> true);
            modules = new ChoiceListSetting("Modules" + tail,
                "The modules this rule switches. Click to pick them.",
                () -> OfflineClient.INSTANCE.getModuleManager().togglableNames(Triggers.this))
                .under(deed, Deed.SWITCH_MODULES);
            action = new EnumSetting<>("Action" + tail, "What happens to those modules.", Action.SWITCH_ON)
                .describe(Action.SWITCH_ON, "Switches them on.")
                .describe(Action.SWITCH_OFF, "Switches them off.")
                .describe(Action.TOGGLE, "Flips each one to the other state.")
                .under(deed, Deed.SWITCH_MODULES);
            switchBack = new BoolSetting("Switch back" + tail,
                "Puts the modules back how they were once the conditions end.", true)
                .under(deed, () -> deed.is(Deed.SWITCH_MODULES) && !when.hasMoment());
            lastsFor = new NumberSetting("Lasts for" + tail,
                "Seconds before the rule puts its modules back. Nought leaves them switched.",
                0, 0, 60, 1, " seconds")
                .under(deed, () -> deed.is(Deed.SWITCH_MODULES) && when.hasMoment());
            line = new TextSetting("Line" + tail,
                "The chat line the rule sends. Click to type it. A line that starts with a slash runs as a"
                    + " server command and one that starts with the client prefix as a client command. Type \\n"
                    + " between two lines to send both. You can use " + LiveText.WORDS + ".", "")
                .under(deed, Deed.SEND_A_LINE);
            macros = new ChoiceListSetting("Macros" + tail,
                "The macros this rule runs. Click to pick them. The macro command saves a macro.",
                () -> MacroStore.get().all().stream().map(MacroStore.Macro::name).toList())
                .under(deed, Deed.RUN_MACROS);
            againAfter = new NumberSetting("Again after" + tail,
                "Whilst the conditions go on holding the rule acts again every this many seconds. Nought acts"
                    + " once each time they start to hold.", 0, 0, 300, 1, " seconds")
                .under(deed, () -> !deed.is(Deed.SWITCH_MODULES) && !when.hasMoment());
        }

        void register() {
            addSettings(when.settings());
            addSettings(match, deed, modules, action, switchBack, lastsFor, line, macros, againAfter);
        }

        // False for a rule with nothing to watch or nothing to do.
        boolean inUse() {
            if (slot >= ruleCount.getInt() || when.size() == 0) {
                return false;
            }
            return switch (deed.getValue()) {
                case SWITCH_MODULES -> modules.size() > 0;
                case SEND_A_LINE -> !line.isBlank();
                case RUN_MACROS -> macros.size() > 0;
            };
        }

        // As the module goes on. A rule that can never act as it is set up says why.
        void start() {
            before.clear();
            timeLeft = 0;
            againIn = 0;
            holds = false;
            live = inUse();
            if (live) {
                arm();
            }
            if (slot < ruleCount.getInt()) {
                tellProblems();
            }
        }

        // The conditions forget what happened before and what is already met sets nothing off.
        // A rule that repeats and is met already acts for the first time after its gap.
        private void arm() {
            when.start();
            holds = false;
            if (inGame()) {
                when.tick();
                holds = met();
            }
            againIn = holds ? repeatTicks() : 0;
        }

        private void tellProblems() {
            String rule = "Rule " + (slot + 1);
            if (when.size() == 0) {
                ChatUtil.error(rule + " has no condition picked.");
            } else if (!inUse()) {
                ChatUtil.error(rule + " has nothing to do yet. " + switch (deed.getValue()) {
                    case SWITCH_MODULES -> "Pick the modules it switches.";
                    case SEND_A_LINE -> "Type the line it sends.";
                    case RUN_MACROS -> "Pick the macros it runs.";
                });
            }
            when.tellProblems();
            if (!deed.is(Deed.SWITCH_MODULES) && !ChatPace.RULE.everAffords(linesOf(toRun()))) {
                ChatUtil.error(rule + " sends more lines in one go than the server takes without a kick for spam."
                    + " Six chat lines and six commands are the most.");
            }
            if (!when.watchesOutside()) {
                return;
            }
            if (match.is(Match.ALL) && when.size() > 1) {
                ChatUtil.error(rule + " can never act. Leaving is never met together with anything else. Set"
                    + " its Match to Any or give Leaving a rule of its own.");
            } else if (!deed.is(Deed.SWITCH_MODULES)) {
                ChatUtil.error(rule + " can do nothing on Leaving. Nothing can be sent once you have left and"
                    + " only a switch of modules can follow it.");
            }
        }

        // Every line the macros would send between them.
        private static List<String> linesOf(List<MacroStore.Macro> macros) {
            List<String> lines = new ArrayList<>();
            macros.forEach(macro -> lines.addAll(macro.lines()));
            return lines;
        }

        boolean met() {
            return match.is(Match.ALL) ? when.allHold() : when.anyHolds();
        }

        // Ticks between two lines of a rule that goes on holding. Nought for one that acts once.
        private int repeatTicks() {
            return deed.is(Deed.SWITCH_MODULES) || when.hasMoment()
                ? 0 : againAfter.getInt() * SharedConstants.TICKS_PER_SECOND;
        }

        // Once a tick. A rule that comes into use whilst the module is on is armed first like
        // any rule is as the module goes on. One that goes out of use counts as ended.
        void tick(boolean inWorld) {
            boolean use = inUse();
            if (use && !live) {
                live = true;
                arm();
                return;
            }
            live = use;
            if (use && inWorld) {
                when.tick();
            } else if (use) {
                when.tickOutside();
            }
            boolean now = use && met();
            if (!now) {
                againIn = 0;
            }
            if (now && !holds) {
                begin();
            } else if (now && againIn > 0 && --againIn == 0) {
                act();
            } else if (!now && holds && switchBack.isOn() && !when.hasMoment()) {
                undo();
            }
            holds = now;
            if (timeLeft > 0 && --timeLeft == 0) {
                undo();
            }
        }

        // A fresh start whilst the time of the last one still runs only starts the time again.
        private void begin() {
            if (!deed.is(Deed.SWITCH_MODULES)) {
                act();
                return;
            }
            if (timeLeft == 0) {
                switchModules();
            }
            timeLeft = when.hasMoment() ? lastsFor.getInt() * SharedConstants.TICKS_PER_SECOND : 0;
        }

        // Sends the line or runs the macros and sets the wait for the next time. Lines the
        // server would count as spam are held back. A rule that still holds tries again in a
        // second and a moment that has passed loses its line.
        private void act() {
            List<MacroStore.Macro> toRun = toRun();
            if (!ChatPace.RULE.affords(linesOf(toRun))) {
                ChatPace.tellHeld(getName());
                againIn = when.hasMoment() ? 0 : RETRY_TICKS;
                return;
            }
            toRun.forEach(MacroStore.get()::run);
            againIn = repeatTicks();
        }

        // The typed line as a macro of its own or the picked macros that still exist. The lines go
        // as typed. A macro fills in its live words once as each line is sent.
        private List<MacroStore.Macro> toRun() {
            if (deed.is(Deed.SEND_A_LINE)) {
                List<String> lines = Arrays.stream(TextMarkup.LINE_BREAK.split(line.getValue()))
                    .map(String::trim).filter(typed -> !typed.isEmpty()).toList();
                return List.of(new MacroStore.Macro(getName() + " rule " + (slot + 1), Bind.NONE,
                    new MacroStore.Typed(lines, false, null)));
            }
            List<MacroStore.Macro> found = new ArrayList<>();
            for (String name : macros.getValue()) {
                MacroStore.Macro macro = MacroStore.get().find(name);
                if (macro != null) {
                    found.add(macro);
                }
            }
            return found;
        }

        private void switchModules() {
            before.clear();
            for (String name : modules.getValue()) {
                Module module = OfflineClient.INSTANCE.getModuleManager().get(name);
                if (module == null || module == Triggers.this || !module.isTogglable()) {
                    continue;
                }
                boolean was = module.isEnabled();
                switch (action.getValue()) {
                    case SWITCH_ON -> module.setEnabled(true);
                    case SWITCH_OFF -> module.setEnabled(false);
                    case TOGGLE -> module.toggle();
                }
                if (module.isEnabled() != was) {
                    before.put(module, was);
                    ChatUtil.toggled(module);
                }
            }
        }

        void undo() {
            before.forEach((module, was) -> {
                if (module.isEnabled() != was) {
                    module.setEnabled(was);
                    ChatUtil.toggled(module);
                }
            });
            before.clear();
        }
    }

    private final NumberSetting ruleCount = new NumberSetting("Rules",
        "How many rules there are. A row opens for each.", 1, 0, SLOTS, 1).max(SLOTS);
    private final Rule[] rules = new Rule[SLOTS];

    public Triggers() {
        super("Triggers", "Switches modules or sends a line or runs a macro when the conditions you pick are met.",
            Category.MISC);
        addSettings(ruleCount);
        for (int i = 0; i < SLOTS; i++) {
            rules[i] = new Rule(i + 1);
            rules[i].register();
        }
        searchTags("condition", "auto toggle", "rules", "low health", "when", "on kill", "join drop",
            "send command", "on death", "respawn", "kit", "feed", "heal", "repair", "back", "macro");
    }

    @Override
    protected void onEnable() {
        for (Rule rule : rules) {
            rule.start();
        }
    }

    @Override
    protected void onDisable() {
        for (Rule rule : rules) {
            rule.before.clear();
            rule.timeLeft = 0;
        }
    }

    // A pop is passed on to every rule and met on its next tick.
    @Subscribe
    private void onTotemPop(TotemPopEvent event) {
        for (Rule rule : rules) {
            rule.when.heard(event);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        for (Rule rule : rules) {
            rule.tick(true);
        }
    }

    // The world tick stops with the world. This one carries on for the rules about leaving.
    // Every other rule stands as it stood until you are back in a world.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (inGame()) {
            return;
        }
        for (Rule rule : rules) {
            if (rule.when.watchesOutside()) {
                rule.tick(false);
            }
        }
    }
}
