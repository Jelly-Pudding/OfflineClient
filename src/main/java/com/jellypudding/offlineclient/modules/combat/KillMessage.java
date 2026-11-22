package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KillEvent;
import com.jellypudding.offlineclient.event.events.TotemPopEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatPace;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.KillTracker;
import com.jellypudding.offlineclient.util.SentLine;
import com.jellypudding.offlineclient.util.Strangers;
import com.jellypudding.offlineclient.util.TextLines;
import net.minecraft.world.entity.player.Player;

public final class KillMessage extends Module {

    public enum PopLines { CHAT, WHISPER }

    private static final double MILLIS_PER_SECOND = 1000;

    private final TextLines lines = new TextLines(TextLines.Order.RANDOM, "Messages",
        "How many lines to pick from.",
        "What to say after a kill. {player} becomes the player you killed and {streak} your kills since you last"
            + " died." + TextLines.LIVE_WORDS,
        "GG {player}", "{player} has gone back to spawn", "{player} could not stop me. Kill streak {streak}");
    private final BoolSetting pops = new BoolSetting("Totem pops",
        "Also says a line when a player you hurt a moment ago pops a totem.", false);
    private final TextLines popLines = TextLines.prefixed("Pop", TextLines.Order.RANDOM, "Pop messages",
        "How many lines to pick from for a pop.",
        "What to say after a pop. {player} becomes the player who popped and {pops} the totems they have"
            + " popped since they last died." + TextLines.LIVE_WORDS,
        "That was totem {pops} {player}", "{player} is running out of totems")
        .under(pops);
    private final EnumSetting<PopLines> popsGo = new EnumSetting<>("Pop lines go",
        "Where a line about a pop is said.", PopLines.CHAT)
        .describe(PopLines.CHAT, "Into chat for everyone to read.")
        .describe(PopLines.WHISPER, "In a whisper to the player who popped.")
        .under(pops);
    private final TextSetting whisperWith = SentLine.whisperWith()
        .under(popsGo, PopLines.WHISPER);
    private final NumberSetting cooldown = new NumberSetting("Cooldown",
        "The shortest gap between two lines about kills and between two about pops. A kill or a pop inside it"
            + " passes without a line.", 5, 0, 60, 0.5, "s");

    private long killSaidAt;
    private long popSaidAt;

    public KillMessage() {
        super("KillMessage", "Says something in chat when you kill a player.", Category.COMBAT);
        addSettings(lines.settings());
        addSettings(pops);
        addSettings(popLines.settings());
        addSettings(popsGo, whisperWith, cooldown);
        searchTags("gg", "ez", "taunt", "kill say", "pop message", "totem pop");
    }

    @Override
    protected void onEnable() {
        killSaidAt = 0;
        popSaidAt = 0;
        lines.restart();
        popLines.restart();
    }

    @Subscribe
    private void onKill(KillEvent event) {
        long now = System.currentTimeMillis();
        if (!cooledSince(killSaidAt, now)) {
            return;
        }
        String line = lines.pick("player", EntityUtil.nameOf(event.getVictim()),
            "streak", String.valueOf(event.getStreak()));
        if (line != null) {
            killSaidAt = now;
            say(line);
        }
    }

    // The pop that comes with a death is the kill and gets the kill line.
    @Subscribe
    private void onTotemPop(TotemPopEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        if (!pops.isOn() || event.isDeath() || !cooledSince(popSaidAt, now) || !Strangers.counts(player, false)
            || !KillTracker.INSTANCE.creditsYou(player)) {
            return;
        }
        String name = EntityUtil.nameOf(player);
        String line = popLines.pick("player", name, "pops", String.valueOf(event.getCount()));
        if (line != null) {
            popSaidAt = now;
            say(popsGo.is(PopLines.WHISPER) ? SentLine.whisper(whisperWith.getValue(), name, line) : line);
        }
    }

    private boolean cooledSince(long saidAt, long now) {
        return now - saidAt >= cooldown.getValue() * MILLIS_PER_SECOND;
    }

    // A taunt that had to wait would come too late to mean anything. One the server would
    // count as spam is dropped.
    private void say(String line) {
        if (!ChatPace.RULE.say(line)) {
            ChatPace.tellHeld(getName());
        }
    }
}
