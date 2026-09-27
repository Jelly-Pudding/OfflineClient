package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.JoinWatch;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.Map;

// Each rule fires once as its condition starts. Switch back returns the modules it
// changed to how they were once the condition ends. A join has no end.
public final class Triggers extends Module {

    // How many rules can be set up one by one.
    private static final int SLOTS = 8;

    public enum When {
        LOW_HEALTH,
        PLAYER_NEAR,
        DIMENSION,
        FALLING,
        JOINING
    }

    public enum Action {
        SWITCH_ON,
        SWITCH_OFF,
        TOGGLE
    }

    public enum Place {
        OVERWORLD(Level.OVERWORLD),
        NETHER(Level.NETHER),
        END(Level.END);

        final ResourceKey<Level> key;

        Place(ResourceKey<Level> key) {
            this.key = key;
        }
    }

    // The settings for one rule. The rule row folds the rest away.
    private final class Rule {

        final EnumSetting<When> when;
        final NumberSetting health;
        final NumberSetting range;
        final EnumSetting<Place> dimension;
        final NumberSetting fall;
        final ChoiceListSetting modules;
        final EnumSetting<Action> action;
        final BoolSetting switchBack;

        // Each module the rule changed and whether it was on before.
        final Map<Module, Boolean> before = new LinkedHashMap<>();
        boolean holds;

        Rule(int number) {
            int slot = number - 1;
            when = new EnumSetting<>("Rule " + number, "What sets this rule off.", When.LOW_HEALTH)
                .describe(When.LOW_HEALTH, "Your health plus absorption drops to a set number of hearts.")
                .describe(When.PLAYER_NEAR, "A player who is not your friend comes within range.")
                .describe(When.DIMENSION, "You arrive in a dimension.")
                .describe(When.FALLING, "You fall further than a set distance.")
                .describe(When.JOINING, "You join a server.")
                .visibleWhen(() -> slot < ruleCount.getInt());
            health = new NumberSetting("Health " + number,
                "Hearts at or below which the rule fires.", 5, 0.5, 18, 0.5, " hearts")
                .min(0.5)
                .under(when, () -> slot < ruleCount.getInt() && when.is(When.LOW_HEALTH));
            range = new NumberSetting("Range " + number,
                "How close the player must come.", 16, 1, 128, 1, " blocks")
                .min(0.5)
                .under(when, () -> slot < ruleCount.getInt() && when.is(When.PLAYER_NEAR));
            dimension = new EnumSetting<>("Dimension " + number, "The dimension that sets the rule off.",
                Place.NETHER)
                .under(when, () -> slot < ruleCount.getInt() && when.is(When.DIMENSION));
            fall = new NumberSetting("Fall " + number,
                "How far you must fall.", 5, 1, 50, 0.5, " blocks")
                .min(0.5)
                .under(when, () -> slot < ruleCount.getInt() && when.is(When.FALLING));
            modules = new ChoiceListSetting("Modules " + number,
                "The modules this rule switches. Click to pick them.",
                () -> OfflineClient.INSTANCE.getModuleManager().togglableNames(Triggers.this))
                .under(when, () -> slot < ruleCount.getInt());
            action = new EnumSetting<>("Action " + number, "What happens to those modules.", Action.SWITCH_ON)
                .describe(Action.SWITCH_ON, "Switches them on.")
                .describe(Action.SWITCH_OFF, "Switches them off.")
                .describe(Action.TOGGLE, "Flips each one to the other state.")
                .under(when, () -> slot < ruleCount.getInt());
            switchBack = new BoolSetting("Switch back " + number,
                "Puts the modules back how they were once the condition ends.", true)
                .under(when, () -> slot < ruleCount.getInt() && !when.is(When.JOINING));
        }

        // A join has no end to switch back at.
        boolean switchesBack() {
            return switchBack.isOn() && !when.is(When.JOINING);
        }

        boolean inUse(int slot) {
            return slot < ruleCount.getInt() && modules.size() > 0;
        }

        // Whether the condition holds this tick. A join holds for one tick only.
        boolean test(boolean joined) {
            return switch (when.getValue()) {
                case LOW_HEALTH -> EntityUtil.healthAtOrBelow(health.getValue());
                case PLAYER_NEAR -> EntityUtil.nearestEnemy(range.getValue()) != null;
                case DIMENSION -> mc.level.dimension() == dimension.getValue().key;
                case FALLING -> mc.player.fallDistance > fall.getValue();
                case JOINING -> joined;
            };
        }

        void fire() {
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
        "How many rules there are. A row opens for each.", 1, 0, SLOTS, 1).min(0).max(SLOTS);
    private final Rule[] rules = new Rule[SLOTS];
    private final JoinWatch joins = new JoinWatch();

    public Triggers() {
        super("Triggers", "Switches modules on or off by rules such as low health or a player coming near.", Category.MISC);
        addSettings(ruleCount);
        for (int i = 0; i < SLOTS; i++) {
            Rule rule = new Rule(i + 1);
            rules[i] = rule;
            addSettings(rule.when, rule.health, rule.range, rule.dimension, rule.fall, rule.modules,
                rule.action, rule.switchBack);
        }
        searchTags("condition", "auto toggle", "rules", "low health", "when");
    }

    // A condition already true as the module comes on does not fire.
    @Override
    protected void onEnable() {
        joins.accept();
        for (int i = 0; i < SLOTS; i++) {
            Rule rule = rules[i];
            rule.before.clear();
            rule.holds = inGame() && rule.inUse(i) && rule.test(false);
        }
    }

    @Override
    protected void onDisable() {
        for (Rule rule : rules) {
            rule.before.clear();
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        boolean joined = joins.joined();
        for (int i = 0; i < SLOTS; i++) {
            Rule rule = rules[i];
            boolean holds = rule.inUse(i) && rule.test(joined);
            if (holds && !rule.holds) {
                rule.fire();
            } else if (!holds && rule.holds && rule.switchesBack()) {
                rule.undo();
            }
            rule.holds = holds;
        }
    }
}
