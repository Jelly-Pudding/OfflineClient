package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.EntityAddedEvent;
import com.jellypudding.offlineclient.event.events.EntityDamageEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.event.events.TotemPopEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.GameType;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

// The tab list news and the news of the players round you and the warnings about yourself are
// parts of their own beside this. It holds what they share and hands each its events.
public final class Notifier extends Module {

    // Work the netty thread hands to the game thread in the order it arrived.
    sealed interface Pending {
    }

    record JoinLeave(String name, boolean joined) implements Pending {
    }

    record ModeChange(String name, GameType mode) implements Pending {
    }

    // The tab name is null unless the server set one.
    record Presence(boolean shown, String name, Component tabName) implements Pending {
    }

    record ServerLine(ClientboundSystemChatPacket packet) implements Pending {
    }

    record Rejoin() implements Pending {
    }

    record Swung(int entityId) implements Pending {
    }

    static final int SETTLE_TICKS = 40;

    static final int MAX_TRACKED = 512;

    private static final int MAX_PENDING = 4096;

    private final Queue<Pending> queue = new LinkedBlockingQueue<>(MAX_PENDING);
    private final TabNews tab = new TabNews(this, queue);
    private final PlayerNews players = new PlayerNews(this);
    private final SelfWarnings self = new SelfWarnings(this);

    private final ActionSetting forgetAlarms = new ActionSetting("Forget alarmed players",
        "Lets every player set off the alarms again straight away.", this::forgetAlarms)
        .visibleWhen(() -> tab.alarmOn() || players.alarmsOn());
    private final BoolSetting enemiesOnly = new BoolSetting("Enemies only",
        "The alarms and the lines about burrows and bed carriers only heed players on your enemy list.", false)
        .visibleWhen(() -> tab.alarmOn() || players.alarmsOn() || players.watchesStrangers());
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Stays quiet about people on your friend list.", false);
    private final BoolSetting sound = new BoolSetting("Sound",
        "Plays a soft ping with every message.", false);

    private final WorldWatch world = new WorldWatch();
    private int clock;

    public Notifier() {
        super("Notifier", "Tells you in chat and with alarms what other players and the server do.", Category.MISC);
        addSettings(players.inViewRows());
        addSettings(tab.joinRows());
        addSettings(players.lookRows());
        addSettings(forgetAlarms);
        addSettings(players.nearRows());
        addSettings(enemiesOnly);
        addSettings(tab.listRows());
        addSettings(self.serverRows());
        addSettings(players.cheaterRows());
        addSettings(self.warningRows());
        addSettings(ignoreFriends, sound);
        searchTags("visual range", "totem pop", "alert", "pearl", "join", "leave", "vanish",
            "game mode", "weakness", "poison", "wither", "effect", "alarm", "render distance",
            "watch list", "setback", "lagback", "anti cheat", "teleport", "cheater", "damage", "staff",
            "friend", "spotted", "enemy", "burrow", "bed", "armour", "durability", "rain", "weather");
    }

    @Override
    protected void onEnable() {
        reset();
        if (inGame()) {
            tab.copyTabList();
            self.rememberEffects();
        }
    }

    @Override
    protected void onDisable() {
        reset();
        tab.stopAlarm();
        players.stopAlarms();
    }

    private void reset() {
        queue.clear();
        tab.reset();
        players.reset();
        self.reset();
        world.forget();
    }

    private void forgetAlarms() {
        tab.forgetAlarm();
        players.forgetAlarms();
    }

    private void forgetServer() {
        tab.forgetServer();
        players.forgetServer();
        self.forgetServer();
    }

    private void forgetWorld() {
        players.forgetWorld();
        self.forgetWorld();
    }

    // Fired on the netty thread. The level must not be read here.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        tab.read(event.getPacket());
        if (event.getPacket() instanceof ClientboundSwingAnimationPacket packet && players.countsSwings()) {
            queue.offer(new Swung(packet.entityId()));
        }
    }

    @Subscribe
    private void onEntityAdded(EntityAddedEvent event) {
        players.onEntityAdded(event.getEntity());
    }

    @Subscribe
    private void onDamage(EntityDamageEvent event) {
        self.hit(event);
        players.hurt(event);
    }

    @Subscribe
    private void onTotemPop(TotemPopEvent event) {
        players.onTotemPop(event);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        clock++;
        if (world.changed()) {
            forgetWorld();
        }
        Pending pending;
        while ((pending = queue.poll()) != null) {
            switch (pending) {
                case JoinLeave change -> tab.onJoinLeave(change);
                case ModeChange change -> tab.reportMode(change);
                case Presence change -> tab.onPresence(change);
                case ServerLine line -> tab.onServerLine(line.packet());
                case Rejoin _ -> {
                    forgetServer();
                    tab.onRejoin();
                }
                case Swung swung -> players.swung(swung.entityId());
            }
        }
        tab.tick();
        players.tick();
        self.tick();
    }

    int clock() {
        return clock;
    }

    static boolean friend(String name) {
        return OfflineClient.INSTANCE.getFriendManager().isFriend(name);
    }

    boolean skip(String name) {
        return ignoreFriends.isOn() && friend(name);
    }

    boolean alarms(String name) {
        return !skip(name)
            && (!enemiesOnly.isOn() || OfflineClient.INSTANCE.getFriendManager().isEnemy(name));
    }

    boolean enemiesOnly() {
        return enemiesOnly.isOn();
    }

    void say(String message) {
        say(Component.literal(message));
    }

    void say(Component message) {
        ChatUtil.component(message);
        if (sound.isOn()) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f));
        }
    }
}
