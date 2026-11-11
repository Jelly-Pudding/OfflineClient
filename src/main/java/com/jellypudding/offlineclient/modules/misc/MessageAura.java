package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.EntityAddedEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatPace;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.JoinWatch;
import com.jellypudding.offlineclient.util.SentLine;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.TextLines;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

// Whispers one line to other players. A whisper the server would count as spam waits for
// the next tick. A player who leaves before their turn is passed over.
public final class MessageAura extends Module {

    public enum Reach { IN_VIEW, EVERYONE, RANDOM }

    // Names remembered. One player is only messaged once a session. As many players at most
    // wait their turn.
    private static final int MAX_TRACKED = 512;

    private final EnumSetting<Reach> reach = new EnumSetting<>("Reach",
        "Which players are told.", Reach.IN_VIEW)
        .describe(Reach.IN_VIEW, "Each player on the tab list the moment they come into view.")
        .describe(Reach.EVERYONE, "Each player on the tab list in turn. It switches off after the last one.")
        .describe(Reach.RANDOM, "A random player on the tab list each time the delay runs out.");
    private final TextSetting message = new TextSetting("Message",
        "What every player is told with {player} for their name. Click to type it." + TextLines.LIVE_WORDS,
        "Offline on top");
    private final TextSetting whisperWith = SentLine.whisperWith();
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Leaves people on your friend list alone.", false);
    private final BoolSetting once = new BoolSetting("Once each",
        "Messages a player only the first time you see them.", true)
        .under(reach, Reach.IN_VIEW);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between one message and the next.", 20, 0, 200, 1, " ticks");

    private final Deque<String> queue = new ArrayDeque<>();
    private final LinkedHashSet<String> told = new LinkedHashSet<>();
    private final JoinWatch joins = new JoinWatch();
    // The reach the queue was last filled for. Null until the first tick in a world.
    private Reach filledFor;
    private int timer;
    // Players whispered to since the queue was filled.
    private int whispered;

    public MessageAura() {
        super("MessageAura", "Whispers a line to players who come into view or to everyone on the tab list.",
            Category.MISC);
        addSettings(reach, message, whisperWith, ignoreFriends, once, delay);
        searchTags("msg", "whisper", "advert", "dm everyone", "tab list");
    }

    @Override
    public String getSuffix() {
        return count(queue.size());
    }

    @Override
    protected void onEnable() {
        queue.clear();
        told.clear();
        timer = 0;
        filledFor = null;
        joins.accept();
    }

    @Override
    protected void onDisable() {
        queue.clear();
        told.clear();
    }

    @Subscribe
    private void onEntityAdded(EntityAddedEvent event) {
        if (!reach.is(Reach.IN_VIEW) || !(event.getEntity() instanceof Player player) || player == mc.player) {
            return;
        }
        String name = player.getGameProfile().name();
        if (name.isBlank() || (ignoreFriends.isOn() && EntityUtil.isFriend(player))) {
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (once.isOn() && !told.add(key)) {
            return;
        }
        if (told.size() > MAX_TRACKED) {
            told.removeFirst();
        }
        if (queue.size() < MAX_TRACKED) {
            queue.addLast(name);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.getConnection() == null || message.isBlank()) {
            return;
        }
        // A name waiting from the last server or the last reach means nothing here.
        if (joins.joined() || reach.getValue() != filledFor) {
            filledFor = reach.getValue();
            queue.clear();
            whispered = 0;
            if (reach.is(Reach.EVERYONE) && !queueEveryone()) {
                return;
            }
        }
        if (--timer > 0) {
            return;
        }
        String name = reach.is(Reach.RANDOM) ? randomName() : nextOnline();
        if (name == null) {
            if (reach.is(Reach.EVERYONE)) {
                tell("has told §f" + Tally.counted(whispered, "player")
                    + "§7 on the tab list.");
                setEnabled(false);
            }
            return;
        }
        // A name that cannot stand in a command gets nothing and the next one is tried.
        String line = SentLine.whisper(whisperWith.getValue(), name, SentLine.of(message.getValue(), "player", name));
        if (!line.isEmpty() && !ChatPace.RULE.say(line)) {
            ChatPace.tellHeld(getName());
            return;
        }
        if (!line.isEmpty()) {
            timer = delay.getInt();
            whispered++;
        }
        queue.pollFirst();
    }

    // False after switching off because there is nobody to tell.
    private boolean queueEveryone() {
        List<String> others = others();
        queue.addAll(others.subList(0, Math.min(others.size(), MAX_TRACKED)));
        if (queue.isEmpty()) {
            disable("MessageAura found nobody else on the tab list to tell.");
            return false;
        }
        return true;
    }

    // The first waiting player who is still on the tab list. Those who left are passed over.
    private String nextOnline() {
        while (!queue.isEmpty() && ChatSender.listedName(queue.peekFirst()) == null) {
            queue.pollFirst();
        }
        return queue.peekFirst();
    }

    private String randomName() {
        List<String> others = others();
        return others.isEmpty() ? null : others.get(ThreadLocalRandom.current().nextInt(others.size()));
    }

    // The other players on the tab list without the friends left alone.
    private List<String> others() {
        List<String> names = ServerInfo.othersOnline();
        if (ignoreFriends.isOn()) {
            names.removeIf(OfflineClient.INSTANCE.getFriendManager()::isFriend);
        }
        return names;
    }
}
