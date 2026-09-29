package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.ListMode;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.Hop;
import com.jellypudding.offlineclient.util.MoveGate;
import com.jellypudding.offlineclient.util.PacketBudget;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

// Picks up dropped items from afar. Hop mode hops to each one and waits beside it. Pull
// mode sprint jumps on the spot. Each jump adds a push along the yaw and the server's
// next tick carries the pickup reach that far out before it puts the player back.
public final class Fetch extends Module {

    public enum Mode { HOP, PULL }

    private enum Stage { IDLE, GOING, WAITING, RETURNING }

    // A fresher drop may not be ready to pick up. A thrown item waits two seconds.
    private static final int SETTLE_TICKS = 40;
    // Ticks the server is given to hand an item over.
    private static final int PICKUP_WAIT = 10;
    // How far round the player's box the server picks items up.
    private static final double PICKUP_REACH = 1;
    // A pull reaches items lying about level with the feet.
    private static final double PULL_LEVEL = 0.5;

    // Each sprint jump pushes the server's player this far a tick along its yaw.
    private static final double JUMP_PUSH = 0.2;
    // How far each jump packet rises. It stays counted well above y 128.
    private static final double JUMP_RISE = 1.0E-6;
    // Each jump is two positions and two tick ends.
    private static final int PACKETS_PER_JUMP = 4;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode", "How the items are fetched.", Mode.HOP)
        .describe(Mode.HOP, "Hops to each item and waits beside it until it is picked up.")
        .describe(Mode.PULL, "Sprint jumps on the spot until the server sweeps your pickup reach out to the item."
            + " You never move. Twenty blocks of reach cost about five points of hunger. Paper only takes one"
            + " push a quarter second and it does next to nothing there.");
    private final NumberSetting range = new NumberSetting("Range",
        "How far away an item may be.", 40, 5, 100, 1, " blocks").min(1);
    private final EnumSetting<ListMode> listMode = ListMode.setting("List mode", ListMode.BLACKLIST,
        "Fetches only the listed items.", "Fetches everything except the listed items.");
    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "The items the list applies to. Click to pick them.", BuiltInRegistries.ITEM, List.of());
    private final BoolSetting comeBack = new BoolSetting("Come back",
        "Hops back to where you started once every item is in.", true).under(mode, Mode.HOP);
    private final BoolSetting stayOn = new BoolSetting("Stay on",
        "Keeps fetching new drops instead of switching off once none are left.", false);

    private final ChatWarning warning = new ChatWarning();
    private final WorldWatch world = new WorldWatch();

    // Items the server would not hand over. They are left alone from then on.
    private final Set<Integer> tried = new HashSet<>();
    private Stage stage = Stage.IDLE;
    private Vec3 home;
    private ItemEntity fetching;
    private int waited;
    private boolean sendingStart;
    // Counts the times Fetch was switched on. A trip from an earlier run reports to nobody.
    private int runs;

    public Fetch() {
        super("Fetch", "Picks up dropped items from far away.", Category.MOVEMENT);
        addSettings(mode, range, listMode, items, comeBack, stayOn);
        searchTags("item magnet", "collect items", "pickup");
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onEnable() {
        runs++;
        warning.clear();
        forget();
    }

    // Starts afresh with nothing to come back to.
    private void forget() {
        tried.clear();
        stage = Stage.IDLE;
        home = null;
        fetching = null;
    }

    // Read by AntiHunger. The sprint start a pull needs has to reach the server.
    public boolean sendingStart() {
        return sendingStart;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        // A death or a new world leaves the drops and the way home behind.
        if (world.changed() || mc.player.isDeadOrDying()) {
            forget();
            return;
        }
        if (Hop.travelling()) {
            return;
        }
        switch (stage) {
            case IDLE -> next();
            case WAITING -> waitForPickup();
            case GOING, RETURNING -> { }
        }
    }

    private void next() {
        if (mode.is(Mode.PULL)) {
            pullNext();
            return;
        }
        Hop.Result trouble = Hop.plan().problem();
        if (trouble != null) {
            disable(trouble.problem());
            return;
        }
        Entity mover = Hop.mover(mc.player);
        ItemEntity item = nearestWanted(mover.position(), false);
        if (item == null) {
            goHome(mover);
            return;
        }
        Vec3 spot = Hop.nearestFit(mover, item.position());
        if (spot == null || !reaches(mover, spot, item)) {
            tried.add(item.getId());
            return;
        }
        if (home == null) {
            home = mover.position();
        }
        fetching = item;
        stage = Stage.GOING;
        Hop.travel(spot, thisRun(this::arrived));
    }

    // A trip started before Fetch was last switched on reports to nobody.
    private Consumer<Hop.Result> thisRun(Consumer<Hop.Result> callback) {
        int run = runs;
        return result -> {
            if (run == runs) {
                callback.accept(result);
            }
        };
    }

    private void arrived(Hop.Result result) {
        if (result == Hop.Result.MOVED) {
            stage = Stage.WAITING;
            waited = 0;
            return;
        }
        warning.say(result.problem());
        if (result == Hop.Result.LEFT) {
            forget();
            return;
        }
        tried.add(fetching.getId());
        stage = Stage.IDLE;
    }

    // The server hands the item over on its next tick with the player beside it.
    private void waitForPickup() {
        if (!fetching.isRemoved() && ++waited <= PICKUP_WAIT) {
            return;
        }
        if (!fetching.isRemoved()) {
            tried.add(fetching.getId());
        }
        fetching = null;
        stage = Stage.IDLE;
    }

    private void goHome(Entity mover) {
        boolean away = home != null && mover.position().distanceTo(home) >= PICKUP_REACH;
        if (!away || !comeBack.isOn() || mode.is(Mode.PULL)) {
            finish();
            return;
        }
        stage = Stage.RETURNING;
        Hop.travel(home, thisRun(this::cameHome));
    }

    private void cameHome(Hop.Result result) {
        if (result != Hop.Result.MOVED) {
            warning.say(result.problem());
        }
        finish();
    }

    private void finish() {
        home = null;
        stage = Stage.IDLE;
        if (!stayOn.isOn()) {
            setEnabled(false);
        }
    }

    // The closest wanted item within range of where the trip began. A pull only reaches
    // items lying level with the feet beyond the normal pickup reach.
    private ItemEntity nearestWanted(Vec3 from, boolean level) {
        Vec3 start = home == null ? from : home;
        ItemEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity item) || !wanted(item)
                || item.position().distanceTo(start) > range.getValue()) {
                continue;
            }
            if (level && (Math.abs(item.getY() - from.y) > PULL_LEVEL
                || item.position().subtract(from).horizontalDistance() <= PICKUP_REACH)) {
                continue;
            }
            double distance = item.distanceToSqr(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = item;
            }
        }
        return best;
    }

    private boolean wanted(ItemEntity item) {
        return item.isAlive() && item.getAge() >= SETTLE_TICKS && !tried.contains(item.getId())
            && listMode.getValue().admits(items.contains(item.getItem().getItem()));
    }

    // True when the server's pickup reach round the mover at the spot takes in the item.
    private static boolean reaches(Entity mover, Vec3 spot, ItemEntity item) {
        return Hop.boxAt(mover, spot).inflate(PICKUP_REACH, 0, PICKUP_REACH).intersects(item.getBoundingBox());
    }

    // Aims the jumps at the nearest level item with a clear way to it.
    private void pullNext() {
        if (ServerInfo.runsPaper()) {
            disable("Paper only takes one push a quarter second. Hop mode works there.");
            return;
        }
        if (!MoveGate.bursts()) {
            warning.say("AntiPacketKick is spreading teleports out. A pull needs its jumps in one go.");
            return;
        }
        if (mc.player.isPassenger() || !mc.player.onGround()) {
            return;
        }
        if (!mc.player.getFoodData().hasEnoughFood()) {
            warning.say("You are too hungry to sprint.");
            return;
        }
        Vec3 at = mc.player.position();
        ItemEntity item = nearestWanted(at, true);
        if (item == null) {
            finish();
            return;
        }
        Vec3 level = new Vec3(item.getX(), at.y, item.getZ());
        if (!Hop.clearWay(mc.player, at, level)) {
            tried.add(item.getId());
            return;
        }
        int jumps = (int) Math.max(1, Math.round(at.distanceTo(level) / JUMP_PUSH));
        // A pull waits until the packet budget has room for every jump. One that could
        // never fit is out of reach.
        int packets = jumps * PACKETS_PER_JUMP;
        if (packets > PacketBudget.CEILING) {
            tried.add(item.getId());
            return;
        }
        if (packets > MoveGate.spare()) {
            return;
        }
        if (jump(at, RotationManager.yawTo(item.position()), jumps)) {
            fetching = item;
            stage = Stage.WAITING;
            waited = 0;
        }
    }

    // Each pair lands and then rises a hair whilst the server thinks the player stands.
    // That is a jump and a sprinting jump pushes along the yaw the last packet faced. The
    // last packet lands facing where the player looks.
    private boolean jump(Vec3 at, float yaw, int jumps) {
        float pitch = mc.player.getXRot();
        boolean sprinting = mc.player.isSprinting();
        if (!sprinting) {
            sprint(ServerboundPlayerCommandPacket.Action.START_SPRINTING);
        }
        boolean sent = true;
        for (int i = 0; i < jumps && sent; i++) {
            sent = MoveGate.send(at, yaw, pitch, true) && MoveGate.endTick()
                && MoveGate.send(at.add(0, JUMP_RISE, 0), yaw, pitch, false) && MoveGate.endTick();
        }
        MoveGate.send(at, mc.player.getYRot(), pitch, true);
        if (!sprinting) {
            sprint(ServerboundPlayerCommandPacket.Action.STOP_SPRINTING);
        }
        return sent;
    }

    private void sprint(ServerboundPlayerCommandPacket.Action action) {
        sendingStart = action == ServerboundPlayerCommandPacket.Action.START_SPRINTING;
        try {
            mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, action));
        } finally {
            sendingStart = false;
        }
    }
}
