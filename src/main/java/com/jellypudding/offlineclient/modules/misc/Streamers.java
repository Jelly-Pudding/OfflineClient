package com.jellypudding.offlineclient.modules.misc;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.JoinWatch;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.Web;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

// The checks run one name at a time on a worker thread and read the public page of each site with
// no key. On a server that does not check accounts anyone can take a streamer's name.
public final class Streamers extends Module {

    private enum Site {
        TWITCH("Twitch", "https://www.twitch.tv/%s", "https://www.twitch.tv/%s",
            body -> body.contains("\"isLiveBroadcast\":true")),
        YOUTUBE("YouTube", "https://www.youtube.com/@%s/live", "https://www.youtube.com/@%s/live",
            body -> body.contains("\"isLiveNow\":true")),
        KICK("Kick", "https://kick.com/api/v2/channels/%s", "https://kick.com/%s",
            body -> isTrue(at(JsonParser.parseString(body), "livestream", "is_live"))),
        TIKTOK("TikTok", "https://www.tiktok.com/api-live/user/room/?aid=1988&sourceType=54&uniqueId=%s",
            "https://www.tiktok.com/@%s/live",
            body -> isOnAir(at(JsonParser.parseString(body), "data", "liveRoom", "status")));

        final String label;
        final String asks;
        final String watch;
        // Reads the answer. It may throw on an answer it cannot read.
        final Predicate<String> live;

        Site(String label, String asks, String watch, Predicate<String> live) {
            this.label = label;
            this.asks = asks;
            this.watch = watch;
            this.live = live;
        }

        static Site labelled(String label) {
            for (Site site : values()) {
                if (site.label.equals(label)) {
                    return site;
                }
            }
            return null;
        }
    }

    // The tab list fills over the first moments of a join.
    private static final int JOIN_SETTLE_TICKS = 3 * SharedConstants.TICKS_PER_SECOND;

    private static final int LOOK_TICKS = SharedConstants.TICKS_PER_SECOND;

    private static final int NOT_FOUND = 404;

    // TikTok numbers the state of a room. A room on the air is two.
    private static final int TIKTOK_ON_AIR = 2;

    private static final List<String> SITE_LABELS = Arrays.stream(Site.values()).map(site -> site.label).toList();

    private final ChoiceListSetting sites = new ChoiceListSetting("Sites",
        "The streaming sites that are checked. Click to pick them.", () -> SITE_LABELS, SITE_LABELS);
    private final BoolSetting onJoin = new BoolSetting("On join",
        "Checks everyone online when you join a server.", true);
    private final BoolSetting newPlayers = new BoolSetting("New players",
        "Checks each player who joins after you.", true);
    private final NumberSetting recheck = new NumberSetting("Recheck",
        "Minutes between checks of everyone online. Nought checks only once.", 10, 0, 60, 1, " minutes");
    private final NumberSetting skipAbove = new NumberSetting("Skip above",
        "Skips the check of everyone whilst more players than this are online.", 50, 5, 500, 5, " players")
        .min(1);
    private final ActionSetting checkNow = new ActionSetting("Check now",
        "Checks everyone online straight away.", this::checkNow);

    // Guarded by itself together with working.
    private final Set<String> waiting = new LinkedHashSet<>();
    private boolean working;
    // Written by the worker and cleared on the game thread.
    private final Set<String> told = ConcurrentHashMap.newKeySet();
    // Set by Check now. The run that empties the queue then says when it found nobody.
    private volatile boolean asked;
    private final AtomicInteger foundLive = new AtomicInteger();
    // Lookups of the run that no site answered. A check that reached nobody found nothing out.
    private final AtomicInteger unanswered = new AtomicInteger();

    private JoinWatch joins = new JoinWatch();
    private final Set<String> seen = new HashSet<>();
    private int settle;
    private int sinceLook;
    private int sinceCheck;

    public Streamers() {
        super("Streamers", "Checks whether the players online are streaming live under the same name.",
            Category.MISC);
        addSettings(sites, onJoin, newPlayers, recheck, skipAbove, checkNow);
        searchTags("livestream", "live", "streamer", "twitch", "youtube", "kick", "tiktok");
    }

    @Override
    protected void onEnable() {
        joins = new JoinWatch();
    }

    @Override
    protected void onDisable() {
        stop();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (joins.joined()) {
            stop();
            seen.clear();
            settle = JOIN_SETTLE_TICKS;
            return;
        }
        if (settle > 0) {
            if (--settle == 0) {
                seen.addAll(ServerInfo.othersOnline());
                sinceCheck = 0;
                if (onJoin.isOn()) {
                    everyone(true);
                }
            }
            return;
        }
        int every = recheck.getInt() * SharedConstants.TICKS_PER_MINUTE;
        if (every > 0 && ++sinceCheck >= every) {
            sinceCheck = 0;
            everyone(false);
        }
        if (++sinceLook >= LOOK_TICKS) {
            sinceLook = 0;
            lookForNewPlayers();
        }
    }

    private void lookForNewPlayers() {
        List<String> now = ServerInfo.othersOnline();
        List<String> fresh = new ArrayList<>();
        for (String name : now) {
            if (!seen.contains(name)) {
                fresh.add(name);
            }
        }
        seen.clear();
        seen.addAll(now);
        if (newPlayers.isOn() && !fresh.isEmpty()) {
            queue(fresh);
        }
    }

    private String checkNow() {
        if (!inGame()) {
            return "Join a world first";
        }
        if (picked().isEmpty()) {
            return "Pick a site first";
        }
        // A run with nobody to check would leave its reply waiting for the next run.
        if (ServerInfo.othersOnline().isEmpty()) {
            return "Nobody else is online";
        }
        told.clear();
        foundLive.set(0);
        unanswered.set(0);
        asked = true;
        return everyone(true);
    }

    private String everyone(boolean sayWhenSkipped) {
        List<String> names = ServerInfo.othersOnline();
        if (names.size() > skipAbove.getInt()) {
            String skipped = "Skipped with " + names.size() + " players online";
            if (sayWhenSkipped) {
                tell(skipped + ".");
            }
            asked = false;
            return skipped;
        }
        queue(names);
        return "Checking " + Tally.counted(names.size(), "player");
    }

    private List<Site> picked() {
        List<Site> picked = new ArrayList<>();
        for (String label : sites.getValue()) {
            Site site = Site.labelled(label);
            if (site != null) {
                picked.add(site);
            }
        }
        return picked;
    }

    private void queue(List<String> names) {
        List<Site> checked = picked();
        if (checked.isEmpty()) {
            return;
        }
        synchronized (waiting) {
            waiting.addAll(names);
            if (working || waiting.isEmpty()) {
                return;
            }
            working = true;
        }
        Web.later("stream check", () -> work(checked));
    }

    private void stop() {
        synchronized (waiting) {
            waiting.clear();
        }
        told.clear();
        asked = false;
    }

    // Runs on the worker until the queue is empty.
    private void work(List<Site> checked) {
        while (true) {
            String name;
            synchronized (waiting) {
                Iterator<String> next = waiting.iterator();
                if (!next.hasNext()) {
                    working = false;
                    break;
                }
                name = next.next();
                next.remove();
            }
            for (Site site : checked) {
                check(name, site);
            }
        }
        if (asked) {
            asked = false;
            if (foundLive.get() == 0) {
                int lost = unanswered.get();
                mc.execute(() -> tell(lost == 0 ? "found nobody live."
                    : "found nobody live. " + Tally.counted(lost, "lookup") + " got no answer."));
            }
        }
    }

    private void check(String name, Site site) {
        String id = URLEncoder.encode(name.toLowerCase(Locale.ROOT), StandardCharsets.UTF_8);
        Web.Page page = Web.get(String.format(Locale.ROOT, site.asks, id));
        String key = name.toLowerCase(Locale.ROOT) + " " + site.label;
        if (page.status() == NOT_FOUND) {
            told.remove(key);
            return;
        }
        if (!page.ok()) {
            unanswered.incrementAndGet();
            return;
        }
        boolean live;
        try {
            live = site.live.test(page.body());
        } catch (RuntimeException e) {
            return;
        }
        if (!live) {
            told.remove(key);
        } else if (told.add(key)) {
            foundLive.incrementAndGet();
            String watch = String.format(Locale.ROOT, site.watch, id);
            // A check still under way when the module goes off says nothing.
            mc.execute(() -> {
                if (isEnabled()) {
                    ChatUtil.component(Component.literal(ChatUtil.from(getName(), "§f" + name + "§7 is live on "))
                        .append(link(site.label, watch)).append("§7."));
                }
            });
        }
    }

    private static Component link(String shown, String address) {
        return Component.literal(shown).withStyle(style -> style.withColor(ChatFormatting.WHITE)
            .withClickEvent(new ClickEvent.OpenUrl(URI.create(address)))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to watch"))));
    }

    private static JsonElement at(JsonElement root, String... keys) {
        JsonElement at = root;
        for (String key : keys) {
            if (at == null || !at.isJsonObject()) {
                return null;
            }
            at = at.getAsJsonObject().get(key);
        }
        return at;
    }

    private static boolean isTrue(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static boolean isOnAir(JsonElement status) {
        return status != null && status.isJsonPrimitive() && status.getAsInt() == TIKTOK_ON_AIR;
    }
}
