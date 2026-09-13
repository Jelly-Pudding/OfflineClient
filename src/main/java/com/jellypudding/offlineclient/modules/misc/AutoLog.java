package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class AutoLog extends Module {

    public enum Count { TOGETHER, EACH }

    // Health points in one heart.
    private static final float HEART = 2;

    // How close a player has to be for a one hit kill to count.
    private static final double INSTANT_KILL_RANGE = 8;

    public enum Mode { QUIT, CHARS, SELF_HURT }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How you leave the server.", Mode.QUIT)
        .describe(Mode.QUIT, "A plain disconnect.")
        .describe(Mode.CHARS, "Sends a chat line the server refuses and gets you kicked. Slips past combat log plugins.")
        .describe(Mode.SELF_HURT, "Attacks yourself which every server kicks you for. Slips past anti cheats too.");
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
        "Disconnect once this many of your totems have popped. Zero ignores pops.", 0, 0, 10, 1);
    private final BoolSetting onlyTrusted = new BoolSetting("Only trusted",
        "Disconnect when any player who is not a friend comes into view.", false);
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
        "Turn AutoReconnect off as you leave so you do not rejoin into the danger.", true);

    // Counted on the network thread and read on the game thread.
    private final AtomicInteger pops = new AtomicInteger();
    private final Map<EntityType<?>, Integer> tally = new HashMap<>();

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
        addSettings(mode, pauseInCreative, health, predict, instantKill, totems, totemPops, onlyTrusted, entities, count,
            combinedLimit, eachLimit, entityRange, toggleOff, rearm, stopReconnect);
        searchTags("auto disconnect", "log out");
    }

    @Override
    public String getSuffix() {
        return health.getValueString();
    }

    @Override
    protected void onEnable() {
        pops.set(0);
    }

    // Fired on the netty thread. Only the counter is touched here.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundEntityEventPacket packet
            && packet.getEventId() == EntityEvent.PROTECTED_FROM_DEATH
            && mc.player != null && packet.entityId == mc.player.getId()) {
            pops.incrementAndGet();
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isDeadOrDying()
            || (pauseInCreative.isOn() && mc.player.isCreative())) {
            return;
        }
        // Absorption hearts from golden apples and totems count as health.
        if (EntityUtil.healthAtOrBelow(health.getValue())) {
            logOut("Health was at or below " + health.getValueString() + ".", true);
            return;
        }
        float line = health.getFloat() * HEART;
        if (predict.isOn()
            && EntityUtil.totalHealth(mc.player) - DamageUtil.possibleIncoming() <= line) {
            logOut("Something nearby was about to take you below " + health.getValueString() + ".", false);
            return;
        }
        if (totems.getInt() > 0 && countTotems() < totems.getInt()) {
            logOut("Fewer than " + totems.getInt() + " totems were left.", false);
            return;
        }
        if (totemPops.getInt() > 0 && pops.get() >= totemPops.getInt()) {
            logOut(pops.get() + " totems had popped.", false);
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
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Player player) || player == mc.player) {
                continue;
            }
            if (onlyTrusted.isOn() && !EntityUtil.isFriend(player)) {
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

    private int countTotems() {
        int offhand = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
            ? mc.player.getOffhandItem().getCount() : 0;
        return offhand + InventoryUtil.count(Items.TOTEM_OF_UNDYING, InventoryUtil.WHOLE_INVENTORY);
    }

    // Turning off first stops an instant logout after reconnecting.
    // A low health logout can leave a watcher behind that turns the module back on.
    private void logOut(String reason, boolean lowHealth) {
        String note = "";
        AutoReconnect autoReconnect = Modules.active(AutoReconnect.class);
        if (stopReconnect.isOn() && autoReconnect != null) {
            autoReconnect.setEnabled(false);
            note = "\n§7AutoReconnect was turned off.";
        }
        if (toggleOff.isOn()) {
            setEnabled(false);
            if (lowHealth && rearm.isOn()) {
                OfflineClient.INSTANCE.getEventBus().register(healWatcher);
            }
        }
        switch (mode.getValue()) {
            case QUIT -> mc.player.connection.getConnection().disconnect(
                Component.literal("§b[§3Offline§b] §fAutoLog saved you.\n§7" + reason + note));
            // The section sign is refused by every server.
            case CHARS -> mc.player.connection.sendChat("§");
            case SELF_HURT -> mc.player.connection.send(new ServerboundAttackPacket(mc.player.getId()));
        }
    }

    private void stopWatching() {
        OfflineClient.INSTANCE.getEventBus().unregister(healWatcher);
    }
}
