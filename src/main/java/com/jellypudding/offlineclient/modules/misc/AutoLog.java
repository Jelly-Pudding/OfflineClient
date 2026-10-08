package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.event.events.TotemPopEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.PlayerAlarm;
import com.jellypudding.offlineclient.util.Totems;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class AutoLog extends Module {

    public enum Count { TOGETHER, EACH }

    // How close a player has to be for a one hit kill to count.
    private static final double INSTANT_KILL_RANGE = 8;

    // A command that did not get you out of danger is run again after this long at the soonest.
    private static final int COMMAND_GAP_TICKS = 5 * SharedConstants.TICKS_PER_SECOND;

    // The kick that follows a refused chat line or a hit on yourself comes within a round trip.
    // A disconnect much later than this has some other cause.
    private static final long NOTE_MILLIS = TimeUnit.SECONDS.toMillis(30);

    public enum Mode { QUIT, CHARS, SELF_HURT, COMMAND }

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
    private final NumberSetting health = new NumberSetting("Health",
        "Disconnect at or below this many hearts.", 3, 0.5, 9.5, 0.5, " hearts");
    private final BoolSetting predict = new BoolSetting("Predict damage",
        "Also disconnect when a crystal or bed or weapon or fall nearby would take you under the line.",
        true);
    private final BoolSetting instantKill = new BoolSetting("Instant kill",
        "Disconnect when a player within eight blocks holds a weapon that could kill you in one hit.",
        false);
    private final NumberSetting totems = new NumberSetting("Totems",
        "Also disconnect with fewer totems than this. Zero ignores totems.", 0, 0, 10, 1);
    private final NumberSetting totemPops = new NumberSetting("Totem pops",
        "Disconnect once this many of your totems have popped in one life. Zero ignores pops.", 0, 0, 10, 1);
    private final BoolSetting onlyTrusted = new BoolSetting("Only trusted",
        "Disconnect when any player who is not a friend comes into view. Fake players that AntiBot spots never"
            + " count.", false);
    private final BoolSetting onAlarm = new BoolSetting("On alarm",
        "Disconnects when one of the Notifier alarms rings.", false);
    private final RegistryListSetting<EntityType<?>> entities = new RegistryListSetting<>("Entities",
        "Disconnect when too many of these gather near you.", BuiltInRegistries.ENTITY_TYPE,
        List.of(EntityTypes.END_CRYSTAL));
    private final EnumSetting<Count> count = new EnumSetting<>("Count",
        "How the listed entities are counted.", Count.TOGETHER)
        .describe(Count.TOGETHER, "Every listed kind counts towards one total.")
        .describe(Count.EACH, "Each kind is counted on its own.")
        .visibleWhen(() -> entities.size() > 0);
    private final NumberSetting combinedLimit = new NumberSetting("Combined limit",
        "How many listed entities in total trigger the disconnect.", 10, 1, 32, 1)
        .under(count, Count.TOGETHER);
    private final NumberSetting eachLimit = new NumberSetting("Each limit",
        "How many of one kind trigger the disconnect.", 2, 1, 16, 1)
        .under(count, Count.EACH);
    private final NumberSetting entityRange = new NumberSetting("Entity range",
        "How close a listed entity has to be to count.", 5, 1, 16, 1, " blocks")
        .visibleWhen(() -> entities.size() > 0);
    private final BoolSetting toggleOff = new BoolSetting("Toggle off",
        "Turn the module off after it disconnects you.", true);
    private final BoolSetting rearm = new BoolSetting("Rearm when healed",
        "After a low health disconnect turn back on once you are above the health line again.", false)
        .under(toggleOff);
    private final BoolSetting stopReconnect = new BoolSetting("Stop AutoReconnect",
        "Turns AutoReconnect off as you leave to stop you rejoining the danger.", true);

    private final Map<EntityType<?>, Integer> tally = new HashMap<>();
    private final PlayerAlarm.Watcher alarms = new PlayerAlarm.Watcher();
    private int commandWait;

    // What the last kick it caused was for. The disconnected screen shows it under the kick text.
    private Component leaveNote;
    private long leftAt;

    // Listens whilst the module is off to come back once healed.
    private final Object healWatcher = new Object() {
        @Subscribe
        private void onTick(TickEvent event) {
            if (isEnabled()) {
                stopWatching();
                return;
            }
            if (inGame() && !mc.player.isDeadOrDying()
                && !EntityUtil.healthAtOrBelow(health.getValue())) {
                stopWatching();
                setEnabled(true);
                ChatUtil.message("AutoLog is back on now that you have healed.");
            }
        }
    };

    public AutoLog() {
        super("AutoLog", "Logs you out when your health gets low.", Category.MISC);
        addSettings(mode, command, pauseInCreative, health, predict, instantKill, totems, totemPops, onlyTrusted,
            onAlarm, entities, count, combinedLimit, eachLimit, entityRange, toggleOff, rearm, stopReconnect);
        searchTags("auto disconnect", "log out", "antisocial", "staff");
    }

    @Override
    protected void onEnable() {
        alarms.sync();
        commandWait = 0;
    }

    @Override
    public String getSuffix() {
        return health.getValueString();
    }

    @Subscribe
    private void onTotemPop(TotemPopEvent event) {
        if (event.isDeath() || event.getPlayer() != mc.player || totemPops.getInt() == 0
            || (pauseInCreative.isOn() && mc.player.isCreative())) {
            return;
        }
        if (event.getCount() >= totemPops.getInt()) {
            logOut(event.getCount() + " totems had popped.", false);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        List<PlayerAlarm.Ring> rings = alarms.fresh();
        if (commandWait > 0) {
            commandWait--;
        }
        if (!inGame() || mc.player.isDeadOrDying()
            || (pauseInCreative.isOn() && mc.player.isCreative())) {
            return;
        }
        if (onAlarm.isOn() && !rings.isEmpty()) {
            PlayerAlarm.Ring ring = rings.getFirst();
            logOut("The " + ring.alarm().toLowerCase(Locale.ROOT) + " alarm rang for " + ring.player() + ".", false);
            return;
        }
        // Absorption hearts from golden apples and totems count as health.
        if (EntityUtil.healthAtOrBelow(health.getValue())) {
            logOut("Health was at or below " + health.getValueString() + ".", true);
            return;
        }
        float line = health.getFloat() * EntityUtil.HEART;
        if (predict.isOn()
            && DamageUtil.healthAfterIncoming() <= line) {
            logOut("Something nearby was about to take you below " + health.getValueString() + ".", false);
            return;
        }
        if (totems.getInt() > 0 && Totems.carried() < totems.getInt()) {
            logOut("Fewer than " + totems.getInt() + " totems were left.", false);
            return;
        }
        if (!checkPlayers()) {
            checkEntities();
        }
    }

    // True when a player caused a logout.
    private boolean checkPlayers() {
        if (!onlyTrusted.isOn() && !instantKill.isOn()) {
            return false;
        }
        for (Player player : mc.level.players()) {
            if (!EntityUtil.isOtherPlayer(player)) {
                continue;
            }
            if (onlyTrusted.isOn() && EntityUtil.isEnemy(player)) {
                logOut(EntityUtil.nameOf(player) + " came into view and is not a friend.", false);
                return true;
            }
            if (instantKill.isOn() && EntityUtil.isEnemy(player)
                && player.distanceTo(mc.player) <= INSTANT_KILL_RANGE
                && DamageUtil.attackDamage(player, mc.player) > EntityUtil.totalHealth(mc.player)) {
                logOut(EntityUtil.nameOf(player) + " held a weapon that could kill you in one hit.", false);
                return true;
            }
        }
        return false;
    }

    // True when the listed entities caused a logout.
    private boolean checkEntities() {
        if (entities.size() == 0) {
            return false;
        }
        tally.clear();
        int total = 0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!entities.contains(entity.getType())
                || entity.distanceTo(mc.player) > entityRange.getValue()) {
                continue;
            }
            total++;
            tally.merge(entity.getType(), 1, Integer::sum);
        }
        if (count.is(Count.TOGETHER)) {
            if (total >= combinedLimit.getInt()) {
                logOut(total + " of the listed entities were within " + entityRange.getInt() + " blocks.",
                    false);
                return true;
            }
            return false;
        }
        for (Map.Entry<EntityType<?>, Integer> entry : tally.entrySet()) {
            if (entry.getValue() >= eachLimit.getInt()) {
                logOut(entry.getValue() + " " + entry.getKey().getDescription().getString()
                    + " were within " + entityRange.getInt() + " blocks.", false);
                return true;
            }
        }
        return false;
    }

    // Turning off first stops an instant logout after reconnecting.
    // A low health logout can leave a watcher behind that turns the module back on.
    // A command keeps you on the server and AutoReconnect has nothing to do.
    private void logOut(String reason, boolean lowHealth) {
        boolean staying = mode.is(Mode.COMMAND) && !command.isBlank();
        if (staying && commandWait > 0) {
            return;
        }
        String note = "";
        if (!staying && stopReconnect.isOn() && Modules.stopReconnecting()) {
            note = "\n§7AutoReconnect was turned off.";
        }
        if (toggleOff.isOn()) {
            setEnabled(false);
            if (lowHealth && rearm.isOn()) {
                watch(healWatcher);
            }
        }
        if (staying) {
            commandWait = COMMAND_GAP_TICKS;
            // Without its slash the command would go out as a chat line.
            String typed = command.getValue().trim();
            String line = typed.startsWith("/") ? typed : "/" + typed;
            ChatUtil.say(line);
            ChatUtil.message("§7AutoLog ran §f" + line + "§7. " + reason);
            return;
        }
        String text = "AutoLog saved you.\n§7" + reason + note;
        Mode way = mode.getValue();
        // The server ignores a hit from a player holding a spear.
        if (way == Mode.SELF_HURT && mc.player.getMainHandItem().has(DataComponents.PIERCING_WEAPON)) {
            way = Mode.QUIT;
        }
        if (way == Mode.CHARS || way == Mode.SELF_HURT) {
            // The server writes its own kick text and the disconnected screen adds this under it.
            leaveNote = Component.literal("\n\n§b" + text);
            leftAt = System.currentTimeMillis();
        }
        switch (way) {
            // The section sign is refused by every server.
            case CHARS -> mc.player.connection.sendChat("§");
            case SELF_HURT -> mc.player.connection.send(new ServerboundAttackPacket(mc.player.getId()));
            default -> ChatUtil.leaveServer(text);
        }
    }

    // The words for the disconnected screen after a kick AutoLog caused. Null once read and for a
    // disconnect long after it acted.
    public Component takeLeaveNote() {
        Component note = System.currentTimeMillis() - leftAt <= NOTE_MILLIS ? leaveNote : null;
        leaveNote = null;
        return note;
    }

    private void stopWatching() {
        unwatch(healWatcher);
    }
}
