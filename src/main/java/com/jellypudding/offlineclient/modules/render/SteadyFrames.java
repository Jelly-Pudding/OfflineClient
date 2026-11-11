package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.WorldWatch;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import com.mojang.blaze3d.platform.VideoMode;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.client.Options;
import net.minecraft.util.Util;

import java.util.concurrent.TimeUnit;

// Trims the render distance the game draws whilst frames are slow. The game's own setting is
// never touched. Only what the renderer reads is lowered and the options file keeps yours
// whatever happens to the game.
public final class SteadyFrames extends Module {

    // The game counts its frames once a second.
    private static final long READING_MILLIS = TimeUnit.SECONDS.toMillis(1);
    // Slow readings in a row before the distance comes down.
    private static final int SLOW_READINGS = 2;
    // A reading this close to the cap the game draws at says nothing about the distance.
    private static final double AT_CEILING = 0.95;
    // A change rebuilds every section and frames dip whilst it does. Readings wait at least
    // this long and then until every section has been built for a second.
    private static final long SETTLE_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private static final long BUILT_MILLIS = TimeUnit.SECONDS.toMillis(1);
    // A world that has just loaded is still building its sections.
    private static final long JOIN_SETTLE_MILLIS = TimeUnit.SECONDS.toMillis(10);
    // Readings wait at most this long for sections that keep coming in.
    private static final long LONGEST_SETTLE_MILLIS = TimeUnit.MINUTES.toMillis(1);
    // How many times the wait before a raise to one distance can double.
    private static final int MOST_DOUBLINGS = 6;
    // The game draws no fewer chunks than this.
    private static final int FEWEST_CHUNKS = 2;
    private static final long BUILDING = Long.MIN_VALUE;

    private final NumberSetting target = new NumberSetting("Frame rate",
        "The frame rate it keeps. Below it the render distance comes down.", 60, 20, 240, 5, " fps").min(1);
    private final NumberSetting least = new NumberSetting("Least distance",
        "The render distance never comes down further than this.", 6, FEWEST_CHUNKS, 32, 1, " chunks");
    private final NumberSetting raiseAfter = new NumberSetting("Raise after",
        "How long the frame rate must have room for one more chunk before the distance goes up by one."
            + " A distance that proved too much waits twice as long each time.",
        10, 2, 60, 1, " seconds").min(1);

    private final WorldWatch world = new WorldWatch();
    // The distance drawn whilst it is trimmed. Nought whilst yours is drawn.
    private int lowered;
    // Your render distance as the game last worked it out with the server's limit.
    private int own;
    private long lastReading;
    private int slow;
    private int quick;
    // The last change and the least time readings wait after it.
    private long changedAt;
    private long settleFor;
    private boolean settling;
    // Since when every section has been built.
    private long builtSince = BUILDING;
    // The distance the last change raised to. Nought after a drop.
    private int raisedTo;
    // How often a raise to each distance came straight back down.
    private final Int2IntOpenHashMap bounces = new Int2IntOpenHashMap();

    public SteadyFrames() {
        super("SteadyFrames", "Brings the render distance down whilst frames are slow and back up once they recover.",
            Category.RENDER);
        addSettings(target, least, raiseAfter);
        searchTags("render distance", "view distance", "draw distance", "fps", "frame rate", "performance", "lag");
    }

    @Override
    public String getSuffix() {
        int drawn = drawn();
        return drawn < own ? drawn + " of " + own : null;
    }

    @Override
    protected void onEnable() {
        restart(JOIN_SETTLE_MILLIS);
        if (inGame()) {
            world.accept();
        }
    }

    @Override
    protected void onDisable() {
        lowered = 0;
        world.forget();
    }

    // The render distance the game draws. The game asks every frame with your own.
    public int effective(int yours) {
        own = yours;
        return drawn();
    }

    // Your render distance as the game last worked it out before the trim.
    public int own() {
        return own;
    }

    private int drawn() {
        return lowered == 0 ? own : Math.min(own, Math.max(lowered, least.getInt()));
    }

    private void restart(long wait) {
        lowered = 0;
        raisedTo = 0;
        bounces.clear();
        settle(Util.getMillis(), wait);
    }

    private void settle(long now, long wait) {
        slow = 0;
        quick = 0;
        changedAt = now;
        settleFor = wait;
        settling = true;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (world.changed()) {
            restart(JOIN_SETTLE_MILLIS);
        }
        long now = Util.getMillis();
        if (!mc.levelRenderer.hasRenderedAllSections()) {
            builtSince = BUILDING;
        } else if (builtSince == BUILDING) {
            builtSince = now;
        }
        if (now - lastReading < READING_MILLIS) {
            return;
        }
        lastReading = now;
        boolean throttled = mc.getFramerateLimitTracker().getThrottleReason()
            != FramerateLimitTracker.FramerateThrottleReason.NONE;
        if (!settled(now) || own == 0 || mc.gui.screen() != null || throttled) {
            slow = 0;
            quick = 0;
            return;
        }
        read(mc.getFps(), now);
    }

    // Readings wait out a change for its least time and then until every section has been
    // built for a second. A rebuild read as slowness would only bring the distance down again.
    private boolean settled(long now) {
        if (settling) {
            long waited = now - changedAt;
            boolean built = builtSince != BUILDING && now - builtSince >= BUILT_MILLIS;
            settling = waited < settleFor || !built && waited < LONGEST_SETTLE_MILLIS;
        }
        return !settling;
    }

    private void read(int fps, long now) {
        double ceiling = ceiling() * AT_CEILING;
        double wanted = Math.min(target.getValue(), ceiling);
        int drawn = drawn();
        if (fps < wanted) {
            quick = 0;
            if (++slow >= SLOW_READINGS && drawn > least.getInt()) {
                lower(drawn, fps, wanted, now);
            }
        } else if (lowered != 0 && (fps >= ceiling || roomForOneMore(fps, drawn, wanted))) {
            // At the frame cap a reading says nothing about the room left and the raise is tried.
            slow = 0;
            if (++quick >= raiseWait(drawn + 1)) {
                raisedTo = drawn + 1;
                lowered = raisedTo >= own ? 0 : raisedTo;
                settle(now, SETTLE_MILLIS);
            }
        } else {
            slow = 0;
            quick = 0;
        }
    }

    // Drawing costs about the square of the distance. The step aims straight at the target.
    private void lower(int drawn, int fps, double wanted, long now) {
        if (drawn == raisedTo) {
            bounces.addTo(drawn, 1);
        }
        raisedTo = 0;
        int aimed = (int) Math.floor(drawn * Math.sqrt(fps / wanted));
        lowered = Math.max(least.getInt(), Math.min(drawn - 1, aimed));
        settle(now, SETTLE_MILLIS);
    }

    // True when one chunk more would still keep the target by the same square rule.
    private static boolean roomForOneMore(int fps, int drawn, double wanted) {
        double share = (double) drawn / (drawn + 1);
        return fps * share * share >= wanted;
    }

    // The readings in a row a raise to that distance waits for. The wait doubles each time a
    // raise to it came straight back down.
    private long raiseWait(int distance) {
        return (long) raiseAfter.getInt() << Math.min(bounces.get(distance), MOST_DOUBLINGS);
    }

    // The most frames a second the game lets itself draw with your frame limit and vertical sync.
    private double ceiling() {
        double ceiling = Double.MAX_VALUE;
        int limit = mc.options.framerateLimit().get();
        if (limit < Options.UNLIMITED_FRAMERATE_CUTOFF) {
            ceiling = limit;
        }
        if (mc.options.enableVsync().get()) {
            VideoMode mode = mc.getWindow().getActiveVideoMode();
            if (mode != null && mode.getRefreshRate() > 0) {
                ceiling = Math.min(ceiling, mode.getRefreshRate());
            }
        }
        return ceiling;
    }
}
