package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KillEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.TextLines;
import net.minecraft.SharedConstants;
import net.minecraft.util.StringUtil;

// Kills come from the shared tracker. A player you hurt a moment ago who then dies is yours.
public final class KillMessage extends Module {

    private static final String PLAYER = "{player}";
    private static final String STREAK = "{streak}";
    private static final double MILLIS_PER_SECOND = 1000;

    private final TextLines lines = new TextLines(TextLines.Order.RANDOM, "Messages",
        "How many lines to pick from.",
        "What to say after a kill. {player} becomes the player you killed and {streak} your kills since you last died.",
        "GG {player}", "{player} has gone back to spawn", "{player} could not stop me. Kill streak {streak}");
    private final NumberSetting cooldown = new NumberSetting("Cooldown",
        "The shortest gap between two messages. Kills inside it pass without one.", 5, 0, 60, 0.5, "s")
        .min(0);

    private long lastSaidAt;

    public KillMessage() {
        super("KillMessage", "Says something in chat when you kill a player.", Category.COMBAT);
        addSettings(lines.settings());
        addSettings(cooldown);
        searchTags("gg", "ez", "taunt", "kill say");
    }

    @Override
    protected void onEnable() {
        lastSaidAt = 0;
        lines.restart();
    }

    @Subscribe
    private void onKill(KillEvent event) {
        long now = System.currentTimeMillis();
        if (now - lastSaidAt < cooldown.getValue() * MILLIS_PER_SECOND) {
            return;
        }
        String line = lines.pick();
        if (line == null) {
            return;
        }
        lastSaidAt = now;
        String text = line.replace(PLAYER, EntityUtil.nameOf(event.getVictim()))
            .replace(STREAK, String.valueOf(event.getStreak()));
        ChatUtil.say(StringUtil.truncateStringIfNecessary(text, SharedConstants.MAX_CHAT_LENGTH, false));
    }
}
