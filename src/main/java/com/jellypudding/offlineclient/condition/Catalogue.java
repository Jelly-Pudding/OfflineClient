package com.jellypudding.offlineclient.condition;

import com.jellypudding.offlineclient.condition.Threshold.Side;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Feed;
import com.jellypudding.offlineclient.util.HitsTaken;
import com.jellypudding.offlineclient.util.Holes;
import com.jellypudding.offlineclient.util.IdleTime;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.JoinWatch;
import com.jellypudding.offlineclient.util.KillTracker;
import com.jellypudding.offlineclient.util.PlayerAlarm;
import com.jellypudding.offlineclient.util.PullBacks;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Strangers;
import com.jellypudding.offlineclient.util.TickRate;
import com.jellypudding.offlineclient.util.Totems;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

// The order here is the order their rows are listed in.
final class Catalogue {

    // A danger is a reason to get away. The rest are only of use to a rule.
    private record Entry(Function<Frame, Condition> maker, boolean danger) {
    }

    private static final Minecraft mc = Condition.mc;

    private static final double INSTANT_KILL_RANGE = 8;

    private static final double MILLIS_PER_SECOND = TimeUnit.SECONDS.toMillis(1);
    private static final double MILLIS_PER_MINUTE = TimeUnit.MINUTES.toMillis(1);

    private static final List<Entry> ENTRIES = List.of(
        danger(Health::new),
        danger(Catalogue::hunger),
        danger(Wear::new),
        danger(Catalogue::totems),
        danger(TotemPops::new),
        danger(Catalogue::damageTaken),
        other(Catalogue::death),
        other(Catalogue::respawn),
        other(Catalogue::kill),
        danger(Catalogue::rockets),
        danger(Catalogue::food),
        danger(Catalogue::spareElytras),
        danger(PlayerNear::inView),
        danger(PlayerNear::inRange),
        danger(PlayerNear::withBed),
        danger(Catalogue::instantKill),
        danger(EntitiesNear::new),
        danger(Catalogue::tntMinecart),
        danger(Catalogue::alarm),
        other(Catalogue::inHole),
        danger(InDimension::new),
        danger(Spot::new),
        danger(Region::new),
        danger(OffLine::new),
        danger(Height::new),
        danger(Catalogue::fallingFar),
        danger(Catalogue::fallingFast),
        danger(PortalStay::new),
        danger(Clock::new),
        danger(Catalogue::timeOn),
        danger(Catalogue::idle),
        danger(Catalogue::ping),
        danger(Catalogue::tickRate),
        danger(Catalogue::serverSilent),
        danger(Catalogue::pulledBack),
        other(Catalogue::joining),
        other(Leaving::new));

    private Catalogue() {
    }

    private static Entry danger(Function<Frame, Condition> maker) {
        return new Entry(maker, true);
    }

    private static Entry other(Function<Frame, Condition> maker) {
        return new Entry(maker, false);
    }

    static List<Function<Frame, Condition>> all() {
        return ENTRIES.stream().map(Entry::maker).toList();
    }

    static List<Function<Frame, Condition>> dangers() {
        return ENTRIES.stream().filter(Entry::danger).map(Entry::maker).toList();
    }

    private static Condition hunger(Frame frame) {
        return new Threshold(frame, "Hunger", new NumberSetting(frame.name("Hunger"),
            "Your hunger bar is at or below this many points. A full bar holds twenty.", 6, 0, 20, 1, " points")
            .max(20), Side.AT_OR_BELOW, () -> mc.player.getFoodData().getFoodLevel(),
            "Hunger was at or below %s.");
    }

    private static Condition totems(Frame frame) {
        return new Threshold(frame, "Totems", new NumberSetting(frame.name("Totems"),
            "You carry fewer totems than this.", 1, 1, 10, 1), Side.BELOW, Totems::carried,
            "The totems you carried fell below %s.");
    }

    private static Condition damageTaken(Frame frame) {
        Rise hits = new Rise(HitsTaken.INSTANCE::count);
        return Plain.moment(frame, "Damage taken", "Something hits you.",
            () -> hits.rose() ? "You took a hit." : null, hits::sync);
    }

    private static Condition death(Frame frame) {
        Life life = new Life();
        return Plain.moment(frame, "Death", "You die.",
            () -> life.turn() == Life.DIED ? "You died." : null, life::sync);
    }

    private static Condition respawn(Frame frame) {
        Life life = new Life();
        return Plain.moment(frame, "Respawn", "You come back to life after a death.",
            () -> life.turn() == Life.REVIVED ? "You came back to life." : null, life::sync);
    }

    private static Condition kill(Frame frame) {
        Rise kills = new Rise(KillTracker.INSTANCE::kills);
        return Plain.moment(frame, "Kill", "You kill a player.",
            () -> kills.rose() ? "You killed a player." : null, kills::sync);
    }

    private static Condition rockets(Frame frame) {
        return new Threshold(frame, "Rockets", new NumberSetting(frame.name("Rockets"),
            "You carry fewer firework rockets than this.", 16, 1, 128, 1), Side.BELOW,
            () -> carried(stack -> stack.is(Items.FIREWORK_ROCKET)), "The rockets you carried fell below %s.");
    }

    private static Condition food(Frame frame) {
        return new Threshold(frame, "Food", new NumberSetting(frame.name("Food"),
            "You carry fewer things to eat than this.", 8, 1, 64, 1), Side.BELOW,
            () -> carried(stack -> stack.has(DataComponents.FOOD)), "The food you carried fell below %s.");
    }

    // An elytra down to its last point of durability cannot fly.
    private static Condition spareElytras(Frame frame) {
        return new Threshold(frame, "Spare elytras", new NumberSetting(frame.name("Spare elytras"),
            "You carry fewer spare elytras than this. The one you wear and any that are worn out do not count.",
            1, 1, 8, 1), Side.BELOW,
            () -> carried(stack -> stack.has(DataComponents.GLIDER) && !ItemUtil.nearlyBroken(stack, 1)),
            "The spare elytras you carried fell below %s.");
    }

    // What you wear is left out.
    private static int carried(Predicate<ItemStack> test) {
        return InventoryUtil.countWithOffhand(test, InventoryUtil.WHOLE_INVENTORY);
    }

    private static Condition instantKill(Frame frame) {
        return Plain.state(frame, "Instant kill",
            "A player within eight blocks holds a weapon that could kill you in one hit.", () -> {
                Player killer = Strangers.nearest(INSTANT_KILL_RANGE, false,
                    player -> DamageUtil.attackDamage(player, mc.player) > EntityUtil.totalHealth(mc.player));
                return killer == null ? null
                    : EntityUtil.nameOf(killer) + " held a weapon that could kill you in one hit.";
            });
    }

    private static Condition tntMinecart(Frame frame) {
        return Plain.state(frame, "TNT minecart", "A minecart with TNT comes into view.",
            () -> EntityUtil.nearest(Double.MAX_VALUE, entity -> entity.getType() == EntityTypes.TNT_MINECART)
                == null ? null : "A minecart with TNT came into view.");
    }

    private static Condition alarm(Frame frame) {
        PlayerAlarm.Watcher rings = new PlayerAlarm.Watcher();
        return Plain.moment(frame, "Alarm", "One of the Notifier alarms rings.", () -> {
            List<PlayerAlarm.Ring> fresh = rings.fresh();
            if (fresh.isEmpty()) {
                return null;
            }
            PlayerAlarm.Ring ring = fresh.getFirst();
            return "The " + ring.alarm().toLowerCase(Locale.ROOT) + " alarm rang for " + ring.player() + ".";
        }, rings::sync);
    }

    private static Condition inHole(Frame frame) {
        return Plain.state(frame, "In a hole", "You stand in a hole walled with blast proof blocks.",
            () -> Holes.playerInHole() ? "You were in a hole." : null);
    }

    private static Condition fallingFar(Frame frame) {
        return new Threshold(frame, "Falling far", new NumberSetting(frame.name("Falling far"),
            "You have fallen further than this many blocks.", 5, 1, 50, 0.5, " blocks").min(0), Side.ABOVE,
            () -> mc.player.fallDistance, "You had fallen further than %s.");
    }

    private static Condition fallingFast(Frame frame) {
        return new Threshold(frame, "Falling fast", new NumberSetting(frame.name("Falling fast"),
            "You fall faster than this many blocks a second.", 30, 5, 80, 1, " bps").min(0), Side.ABOVE,
            () -> -mc.player.getDeltaMovement().y * SharedConstants.TICKS_PER_SECOND,
            "You were falling faster than %s.");
    }

    private static Condition timeOn(Frame frame) {
        long[] since = {Util.getMillis()};
        return new Threshold(frame, "Time on", new NumberSetting(frame.name("Time on"),
            "This many minutes have passed since the module went on.", 60, 1, 600, 1, " minutes").min(0),
            Side.AT_OR_ABOVE, () -> (Util.getMillis() - since[0]) / MILLIS_PER_MINUTE,
            "%s had passed since the module went on.").restarting(() -> since[0] = Util.getMillis());
    }

    private static Condition idle(Frame frame) {
        return new Threshold(frame, "Idle", new NumberSetting(frame.name("Idle"),
            "You have touched neither the keyboard nor the mouse for this many minutes.",
            5, 1, 120, 1, " minutes").min(0), Side.AT_OR_ABOVE,
            () -> IdleTime.INSTANCE.millis() / MILLIS_PER_MINUTE,
            "You had touched neither the keyboard nor the mouse for %s.");
    }

    private static Condition ping(Frame frame) {
        return new Threshold(frame, "Ping", new NumberSetting(frame.name("Ping"),
            "Your ping is above this many milliseconds.", 500, 50, 2000, 10, " ms").min(0), Side.ABOVE,
            ServerInfo::ping, "Your ping was above %s.");
    }

    // The live figure counts a stall still under way. The plain one only moves as the server speaks.
    private static Condition tickRate(Frame frame) {
        return new Threshold(frame, "Tick rate", new NumberSetting(frame.name("Tick rate"),
            "The server runs slower than this many ticks a second. A healthy server runs at twenty.",
            10, 1, 20, 0.5, " tps").min(0).max(SharedConstants.TICKS_PER_SECOND), Side.BELOW,
            TickRate.INSTANCE::liveTps, "The server was running slower than %s.");
    }

    private static Condition serverSilent(Frame frame) {
        return new Threshold(frame, "Server silent", new NumberSetting(frame.name("Server silent"),
            "The server has not sent the time for this many seconds. A healthy server sends it every second.",
            10, 2, 60, 1, " seconds"), Side.AT_OR_ABOVE,
            () -> TickRate.INSTANCE.millisSinceLastTick() / MILLIS_PER_SECOND,
            "The server had been silent for %s.");
    }

    private static Condition pulledBack(Frame frame) {
        Feed.Reader<Double> pulls = PullBacks.reader();
        return Plain.moment(frame, "Pulled back", "The server pulls you back to where you were.",
            () -> pulls.happened() ? "The server pulled you back." : null, pulls::sync);
    }

    private static Condition joining(Frame frame) {
        JoinWatch joins = new JoinWatch();
        return Plain.moment(frame, "Joining", "You join a server.",
            () -> joins.joined() ? "You joined the server." : null, joins::accept);
    }

    // A count that fell has started again somewhere else and that is no news.
    private static final class Rise {

        private final IntSupplier count;
        private int seen;

        Rise(IntSupplier count) {
            this.count = count;
        }

        boolean rose() {
            int was = seen;
            seen = count.getAsInt();
            return seen > was;
        }

        void sync() {
            seen = count.getAsInt();
        }
    }

    // Joining whilst dead is no death and joining alive is no return to life.
    private static final class Life {

        static final int DIED = -1;
        static final int SAME = 0;
        static final int REVIVED = 1;

        private final JoinWatch joins = new JoinWatch();
        private boolean dead;

        int turn() {
            boolean was = dead;
            dead = mc.player.isDeadOrDying();
            if (joins.joined() || dead == was) {
                return SAME;
            }
            return dead ? DIED : REVIVED;
        }

        void sync() {
            joins.accept();
            dead = mc.player != null && mc.player.isDeadOrDying();
        }
    }
}
