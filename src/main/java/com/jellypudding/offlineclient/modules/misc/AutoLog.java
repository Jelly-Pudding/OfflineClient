package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.condition.Condition;
import com.jellypudding.offlineclient.condition.Conditions;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.event.events.TotemPopEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatPace;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Leave;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.SentLine;
import net.minecraft.client.Screenshot;
import net.minecraft.util.Util;

import java.util.concurrent.TimeUnit;

public final class AutoLog extends Module {

    // A command that did not get you out of danger is run again after this long at the soonest.
    // The rearm waits as long with no reason holding and one escape takes one screenshot in it.
    private static final long GAP_MILLIS = TimeUnit.SECONDS.toMillis(5);

    // The way each mode leaves. A command keeps you on the server and a blank one cannot. It falls
    // back to a plain disconnect.
    public enum Mode {
        QUIT(Leave.QUIT), CHARS(Leave.CHAT), SELF_HURT(Leave.HURT), COMMAND(Leave.QUIT);

        private final Leave way;

        Mode(Leave way) {
            this.way = way;
        }
    }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How you get away from the danger.", Mode.QUIT)
        .describe(Mode.QUIT, "A plain disconnect.")
        .describe(Mode.CHARS, "Sends a chat line the server refuses and gets you kicked. Slips past combat log plugins.")
        .describe(Mode.SELF_HURT, "Makes you hit yourself which the server kicks you for. Anti cheats let it through.")
        .describe(Mode.COMMAND, "Runs the command below instead of leaving the server.");
    private final TextSetting command = new TextSetting("Command",
        "The command it runs such as /home. Click to type it.", "/home")
        .under(mode, Mode.COMMAND);
    private final BoolSetting pauseInCreative = new BoolSetting("Pause in creative",
        "Does nothing whilst you are in creative mode.", true);
    private final Conditions reasons = Conditions.forEscape("Reasons",
        "What makes it act. Click to pick the reasons. Each one you pick lists its own rows below and any"
            + " one of them is enough.", "Health");
    private final BoolSetting screenshot = new BoolSetting("Screenshot first",
        "Saves a screenshot of what you saw to your screenshots folder as it acts.", false);
    private final BoolSetting toggleOff = new BoolSetting("Toggle off",
        "Turns the module off after it acts.", true);
    private final BoolSetting rearm = new BoolSetting("Rearm when safe",
        "After it acts it turns back on once no reason has held for five seconds in a world. Time on counts"
            + " again from the moment it acted.", false)
        .under(toggleOff);
    private final BoolSetting stopReconnect = new BoolSetting("Stop AutoReconnect",
        "Turns AutoReconnect off as you leave to stop you rejoining the danger.", true)
        .visibleWhen(() -> !staying());

    // When the command may run again and when the last screenshot was taken on the game clock.
    private long commandAt;
    private long pictureAt;

    // Listens whilst the module is off to come back once no reason has held for a while. It
    // gives up when the module is switched on by hand or the rearm is switched off. A dormant
    // client stays as it is.
    private final Object safeWatcher = new Object() {

        private long quietSince;

        @Subscribe
        private void onTick(TickEvent event) {
            if (isEnabled() || !rearm.isOn()) {
                unwatch(this);
                return;
            }
            if (mc.player.isDeadOrDying() || Modules.dormant()) {
                quietSince = 0;
                return;
            }
            reasons.tick();
            long now = Util.getMillis();
            if (reasons.anyHolds() || quietSince == 0) {
                quietSince = now;
                return;
            }
            if (now - quietSince < GAP_MILLIS) {
                return;
            }
            quietSince = 0;
            unwatch(this);
            setEnabled(true);
            if (isEnabled()) {
                ChatUtil.message("AutoLog is back on. None of its reasons has held for five seconds.");
            }
        }
    };

    public AutoLog() {
        super("AutoLog", "Logs you out on low health or for any other reason you pick.", Category.MISC);
        addSettings(mode, command, pauseInCreative);
        addSettings(reasons.settings());
        addSettings(screenshot, toggleOff, rearm, stopReconnect);
        searchTags("auto disconnect", "log out", "antisocial", "staff", "afk", "travel", "arrival", "highway",
            "stranger", "screenshot", "at a place");
    }

    @Override
    protected void onEnable() {
        reasons.start();
        reasons.tellProblems();
        if (reasons.size() == 0) {
            ChatUtil.error("AutoLog has no reason to act. Pick at least one under Reasons.");
        }
    }

    @Override
    public String getSuffix() {
        return reasons.summary();
    }

    // A command keeps you on the server. A blank one cannot and the mode falls back to a
    // plain disconnect.
    private boolean staying() {
        return mode.is(Mode.COMMAND) && !command.isBlank();
    }

    private boolean paused() {
        return !inGame() || mc.player.isDeadOrDying() || pauseInCreative.isOn() && mc.player.isCreative();
    }

    // A pop is acted on as it is heard of. That is the same tick the totem saved you on.
    @Subscribe
    private void onTotemPop(TotemPopEvent event) {
        Condition pop = reasons.popped(event);
        if (pop != null && !paused()) {
            act(pop.reason());
        }
    }

    // The reasons are read on every tick. Something that happened whilst it sat a tick out is
    // not kept for later.
    @Subscribe
    private void onTick(TickEvent event) {
        reasons.tick();
        Condition reason = reasons.firstHolding();
        if (reason != null && !paused()) {
            act(reason.reason());
        }
    }

    // Turning off first stops an instant logout after reconnecting. It can leave a watcher
    // behind that turns the module back on. A command keeps you on the server and AutoReconnect
    // has nothing to do.
    private void act(String reason) {
        boolean staying = staying();
        String line = SentLine.asCommand(command.getValue());
        long now = Util.getMillis();
        if (staying && (now < commandAt || !sendCommand(line))) {
            return;
        }
        takeScreenshot(now);
        String note = "";
        if (!staying && stopReconnect.isOn() && Modules.stopReconnecting()) {
            note = "\n§7AutoReconnect was turned off.";
        }
        if (toggleOff.isOn()) {
            setEnabled(false);
            if (rearm.isOn()) {
                reasons.start();
                watch(safeWatcher);
            }
        }
        if (staying) {
            commandAt = now + GAP_MILLIS;
            ChatUtil.message("§7AutoLog ran §f" + line + "§7. " + reason);
            return;
        }
        mode.getValue().way.go("AutoLog saved you.\n§7" + reason + note);
    }

    // The command gets you out of danger and may go where a rule of Triggers may not. A server
    // close to kicking for spam still does not get it.
    private boolean sendCommand(String line) {
        if (ChatPace.ESCAPE.say(line)) {
            return true;
        }
        ChatPace.tellHeld(getName());
        return false;
    }

    // The copy of the last drawn frame is ordered at once and the file is written afterwards.
    // The picture is of the game as it stood even when the disconnect follows straight away.
    // One escape takes one picture however many ticks the kick takes to land and a picture
    // that fails must never stand in the way of the escape.
    private void takeScreenshot(long now) {
        if (!screenshot.isOn() || now < pictureAt) {
            return;
        }
        pictureAt = now + GAP_MILLIS;
        try {
            Screenshot.grab(mc.gameDirectory, mc.gameRenderer.mainRenderTarget(), saved -> mc.execute(() -> {
                if (mc.player != null) {
                    mc.gui.hud.getChat().addClientSystemMessage(saved);
                }
            }));
        } catch (RuntimeException e) {
            OfflineClient.LOG.error("AutoLog could not take its screenshot", e);
        }
    }
}
