package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.config.PlayerLedger;
import com.jellypudding.offlineclient.config.PlayerRecord;
import com.jellypudding.offlineclient.config.PlayerRecord.Place;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.EntityDeathEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.event.events.TotemPopEvent;
import com.jellypudding.offlineclient.gui.DossierScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EnchantText;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// Fills the player records of PlayerLedger. The players the tab list shows are read every tick
// and the players in view once a second. Pops and deaths in view go in as they happen. Players
// in view that AntiBot takes for bots and the bodies FakePlayer puts down are left out.
public final class Dossier extends Module {

    // Players in view are looked over this often.
    private static final int LOOK_TICKS = 20;
    // Tab list entries that turn up this soon after you arrive were online already.
    private static final int SETTLE_TICKS = 40;
    // Players online or in view are written this often whilst nothing else about them changes.
    private static final long HEARTBEAT_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int ENCHANT_LETTERS = 4;

    private final BoolSetting gear = new BoolSetting("Gear",
        "Records the armour and items each player is seen with.", true);
    private final BoolSetting places = new BoolSetting("Places",
        "Records where each player was last seen.", true);
    private final BoolSetting lastOnline = new BoolSetting("Last online",
        "Says in chat how long ago a player who joins was last seen online.", false);
    private final ActionSetting open = new ActionSetting("Open",
        "Opens the record of every player on this server.", DossierScreen::open);

    // Who the tab list showed at the last read and who stood in view at the last look. Each
    // has a spare that the next read fills.
    private Set<UUID> online = new HashSet<>();
    private Set<UUID> onlineNext = new HashSet<>();
    private Set<UUID> inView = new HashSet<>();
    private Set<UUID> inViewNext = new HashSet<>();
    private int settle;
    private int clock;
    private long heartbeatAt;

    public Dossier() {
        super("Dossier", "Keeps a record of every player you meet on each server.", Category.HUNTING);
        addSettings(gear, places, lastOnline, open);
        searchTags("opp stats", "player log", "last seen", "intel");
    }

    @Override
    protected void onEnable() {
        online.clear();
        inView.clear();
        settle = SETTLE_TICKS;
        heartbeatAt = System.currentTimeMillis();
    }

    @Override
    protected void onDisable() {
        lostSight(System.currentTimeMillis());
    }

    // Every server you join and every server a proxy moves you to starts outside a world. The
    // players online when you left were online up to that moment and whoever is online when
    // you arrive was there already.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (mc.level != null) {
            return;
        }
        if (!online.isEmpty() || !inView.isEmpty()) {
            lostSight(System.currentTimeMillis());
        }
        settle = SETTLE_TICKS;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        long now = System.currentTimeMillis();
        PlayerLedger ledger = PlayerLedger.INSTANCE;
        // The settling counts down only once the file is read. Otherwise a slow read would let
        // everyone already online count as joining.
        if (!ledger.ready()) {
            return;
        }
        readTab(ledger, now, settle > 0);
        if (settle > 0) {
            settle--;
        }
        if (++clock % LOOK_TICKS == 0) {
            look(ledger, now);
        }
        if (now - heartbeatAt >= HEARTBEAT_MS) {
            heartbeatAt = now;
            online.forEach(ledger::changed);
            inView.forEach(ledger::changed);
        }
    }

    // A join or a leave is the difference between one read and the next. The ping and the
    // moment they were last online change every tick and wait for some other change to be written.
    private void readTab(PlayerLedger ledger, long now, boolean settling) {
        onlineNext.clear();
        UUID self = mc.player.getUUID();
        for (PlayerInfo info : mc.player.connection.getListedOnlinePlayers()) {
            GameProfile profile = info.getProfile();
            UUID id = profile.id();
            PlayerRecord record = id.equals(self) ? null : ledger.recordOf(id, profile.name(), now);
            if (record == null) {
                continue;
            }
            onlineNext.add(id);
            boolean changed = record.rename(profile.name(), now);
            changed |= record.mode(info.getGameMode().getName(), now);
            record.ping(ServerInfo.latency(info));
            if (!online.contains(id)) {
                if (!settling) {
                    tellLastOnline(record, now);
                    record.joined(now);
                }
                changed = true;
            }
            record.online(now);
            if (changed) {
                ledger.changed(id);
            }
        }
        for (UUID id : online) {
            PlayerRecord record = onlineNext.contains(id) ? null : ledger.get(id);
            if (record != null) {
                record.left(now);
                ledger.changed(id);
            }
        }
        Set<UUID> spare = online;
        online = onlineNext;
        onlineNext = spare;
    }

    // The name links to the record in chat.
    private void tellLastOnline(PlayerRecord record, long now) {
        if (!lastOnline.isOn() || record.lastOnline() == 0) {
            return;
        }
        Component name = ChatUtil.commandLink("§f" + record.name(), "dossier " + record.name(),
            "Click to show their record");
        ChatUtil.component(Component.literal("§b" + getName() + " ").append(name)
            .append("§7 was last seen online §f" + ChatUtil.ago(now - record.lastOnline()) + "§7."));
    }

    // Where each player in view stands and what they wear and hold. One who walked out of view
    // since the last look is written with the place they were last seen.
    private void look(PlayerLedger ledger, long now) {
        inViewNext.clear();
        String dimension = ServerInfo.dimension();
        for (AbstractClientPlayer player : mc.level.players()) {
            String name = player.getGameProfile().name();
            PlayerRecord record = EntityUtil.isRealPlayer(player) && player.isAlive()
                ? ledger.recordOf(player.getUUID(), name, now) : null;
            if (record == null) {
                continue;
            }
            UUID id = player.getUUID();
            inViewNext.add(id);
            boolean changed = record.rename(name, now);
            if (places.isOn()) {
                changed |= record.seen(new Place(player.blockPosition(), dimension, now), !inView.contains(id));
            }
            if (gear.isOn()) {
                changed |= record.wear(pieces(player)) | record.hold(inHands(player));
            }
            if (changed) {
                ledger.changed(id);
            }
        }
        for (UUID id : inView) {
            if (!inViewNext.contains(id)) {
                ledger.changed(id);
            }
        }
        Set<UUID> spare = inView;
        inView = inViewNext;
        inViewNext = spare;
    }

    // The tab list players were online up to this moment and the players in view were last seen
    // where they stood. Their records are written as they are.
    private void lostSight(long now) {
        PlayerLedger ledger = PlayerLedger.INSTANCE;
        for (UUID id : online) {
            PlayerRecord record = ledger.get(id);
            if (record != null) {
                record.online(now);
                ledger.changed(id);
            }
        }
        inView.forEach(ledger::changed);
        online.clear();
        inView.clear();
    }

    @Subscribe
    private void onTotemPop(TotemPopEvent event) {
        PlayerRecord record = event.isDeath() ? null : recordOf(event.getPlayer());
        if (record != null) {
            record.popped(System.currentTimeMillis());
            PlayerLedger.INSTANCE.changed(record.id());
        }
    }

    @Subscribe
    private void onDeath(EntityDeathEvent event) {
        PlayerRecord record = event.getEntity() instanceof Player player ? recordOf(player) : null;
        if (record == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Place at = places.isOn() ? new Place(event.getEntity().blockPosition(), ServerInfo.dimension(), now) : null;
        record.died(at, now);
        PlayerLedger.INSTANCE.changed(record.id());
    }

    // Null for a player who is not recorded.
    private PlayerRecord recordOf(Player player) {
        return inGame() && EntityUtil.isRealPlayer(player) ? PlayerLedger.INSTANCE.recordOf(player.getUUID(),
            player.getGameProfile().name(), System.currentTimeMillis()) : null;
    }

    // Each gear slot as its item id and the enchantments on it. An empty slot gives an empty line.
    private static List<String> pieces(Player player) {
        List<String> pieces = new ArrayList<>(PlayerRecord.GEAR_SLOTS.size());
        for (EquipmentSlot slot : PlayerRecord.GEAR_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            StringBuilder piece = new StringBuilder(stack.isEmpty() ? "" : itemId(stack));
            for (EnchantText.Label label : EnchantText.of(stack, id -> true, ENCHANT_LETTERS)) {
                piece.append(' ').append(label.text());
            }
            pieces.add(piece.toString());
        }
        return pieces;
    }

    private static List<String> inHands(Player player) {
        List<String> items = new ArrayList<>();
        for (ItemStack stack : List.of(player.getMainHandItem(), player.getOffhandItem())) {
            if (!stack.isEmpty()) {
                items.add(itemId(stack));
            }
        }
        return items;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
