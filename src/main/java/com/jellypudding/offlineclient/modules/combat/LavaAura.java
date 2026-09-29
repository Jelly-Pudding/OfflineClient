package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Ignition;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.LavaPours;
import com.jellypudding.offlineclient.util.TargetFilter;
import com.jellypudding.offlineclient.util.TargetPriority;
import com.jellypudding.offlineclient.util.TickRate;
import com.jellypudding.offlineclient.util.UseBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Pours lava or lights fire on players and mobs and dropped items near you. A pour whose flow
// could run into you before it is scooped never happens. Only fire this aura lit is put out.
public final class LavaAura extends Module {

    public enum Method { LAVA, FIRE }

    public enum Spot { FEET, HEAD }

    // Milliseconds without a word from the server before the aura holds off.
    private static final long LAG_MILLIS = 1000;

    // Ticks one of our fires is remembered past its time when it cannot be reached.
    private static final int GIVE_UP = 100;

    // Targets tried in one round. The flow test that keeps lava off you is costly.
    private static final int MAX_TRIES = 16;

    private final EnumSetting<Method> method = new EnumSetting<>("Method",
        "What the targets are burnt with.", Method.LAVA)
        .describe(Method.LAVA, "Pours lava on them from a bucket anywhere in your inventory.")
        .describe(Method.FIRE, "Lights fire under them with flint and steel or a fire charge.");
    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away targets are considered.", 5, 1, 8, 0.5, " blocks");
    private final NumberSetting wallsRange = new NumberSetting("Walls range",
        "How far fire is lit with no clear view from your eyes. The server never checks.",
        4.5, 0, 6, 0.1, " blocks").min(0).under(method, Method.FIRE);
    private final EntityFilter filter = new EntityFilter("Burn", "burnt", true,
        EntityFilter.Pick.NONE, EntityFilter.Pick.NONE, List.of());
    private final BoolSetting burnFriends = new BoolSetting("Burn friends",
        "Also burns players on your friends list.", false)
        .visibleWhen(filter::wantsPlayers);
    private final TargetFilter targets = new TargetFilter();
    private final EnumSetting<TargetPriority> priority = TargetPriority.setting("Burns",
        TargetPriority.NEAREST);
    private final NumberSetting perRound = new NumberSetting("Targets per round",
        "How many targets get lava or fire in one round.", 1, 1, 6, 1).min(1);
    private final EnumSetting<Spot> spot = new EnumSetting<>("Spot",
        "Where the lava goes.", Spot.FEET)
        .describe(Spot.FEET, "Into the block they stand in.")
        .describe(Spot.HEAD, "Above their head when a block there gives the bucket something to pour against. At their feet otherwise.")
        .under(method, Method.LAVA);
    private final BoolSetting skipBurning = new BoolSetting("Skip burning",
        "Leaves targets that are already on fire alone.", false);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between rounds.", 10, 0, 40, 1, " ticks").min(0);
    private final LavaPours pours = new LavaPours().under(method, Method.LAVA);
    private final Ignition ignition = new Ignition().under(method, Method.FIRE);
    private final BoolSetting putOut = new BoolSetting("Put out",
        "Punches out each fire it lit once it has burnt a while. Fire lit by anyone else is left alone.", true)
        .under(method, Method.FIRE);
    private final NumberSetting putOutAfter = new NumberSetting("Put out after",
        "Ticks a fire burns before it is punched out.", 40, 5, 200, 5, " ticks").min(1)
        .under(putOut);
    private final NumberSetting keepAway = new NumberSetting("Keep away",
        "Never lights fire closer to you than this.", 2, 0, 6, 0.5, " blocks").min(0)
        .under(method, Method.FIRE);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns towards each fire on the server side. Lava always turns since the bucket pours where you look.", true)
        .under(method, Method.FIRE);
    private final BoolSetting pauseOnLag = new BoolSetting("Pause on lag",
        "Holds off whilst the server has stopped ticking.", true);

    private final HotbarLoan loan = new HotbarLoan();
    private final SlotSwap slots = new SlotSwap();
    private final ChatWarning warning = new ChatWarning();

    // Fire this aura lit and the tick it did.
    private final Map<BlockPos, Integer> lit = new LinkedHashMap<>();

    private int timer;
    private String targetName;

    public LavaAura() {
        super("LavaAura", "Pours lava or lights fire on targets near you.", Category.COMBAT);
        addSettings(method, targetRange, wallsRange);
        addSettings(filter.settings());
        addSettings(burnFriends);
        addSettings(targets.settings());
        addSettings(priority, perRound, spot, skipBurning, delay);
        addSettings(pours.settings());
        addSettings(ignition.settings());
        addSettings(putOut, putOutAfter, keepAway, rotate, pauseOnLag);
        searchTags("lava", "bucket", "burn", "fire", "flint and steel", "lava aura", "arson");
    }

    @Override
    public String getSuffix() {
        return targetName;
    }

    @Override
    protected void onEnable() {
        timer = 0;
        targetName = null;
        pours.start();
        lit.clear();
        warning.clear();
        loan.forget();
        slots.forget();
    }

    @Override
    protected void onDisable() {
        loan.giveBack();
        slots.restoreIfMine();
        pours.stop();
        lit.clear();
        targetName = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (pauseOnLag.isOn() && TickRate.INSTANCE.lagging(LAG_MILLIS)) {
            return;
        }
        putOutFires();
        // A scoop takes the tick. Lava poured before a switch to fire is still taken back.
        if (UseBudget.remaining() > 0 && pours.scoopDue(loan)) {
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        String missing = missing();
        if (missing != null) {
            warning.say(missing);
            return;
        }
        warning.clear();
        targetName = null;
        int burnt = 0;
        int tries = 0;
        for (Entity target : EntityUtil.ranked(targetRange.getValue(), priority.getValue(), this::wanted)) {
            if (burnt >= perRound.getInt() || tries++ >= MAX_TRIES || UseBudget.remaining() == 0) {
                break;
            }
            if (burn(target)) {
                burnt++;
                if (targetName == null) {
                    targetName = nameOf(target);
                }
            }
        }
        slots.restoreIfMine();
        if (burnt > 0) {
            timer = delay.getInt();
        }
    }

    // What the method needs and the inventory lacks. Null when it is all there.
    private String missing() {
        if (method.is(Method.FIRE)) {
            return ignition.atHand() ? null : Ignition.NO_LIGHTER;
        }
        return LavaPours.carried() ? null : LavaPours.NO_BUCKET;
    }

    private boolean wanted(Entity entity) {
        if (!entity.isAlive() || entity.fireImmune() || entity.isInPowderSnow) {
            return false;
        }
        // Fire goes out in water and rain at once.
        if (method.is(Method.FIRE) && entity.isInWaterOrRain()) {
            return false;
        }
        if (skipBurning.isOn() && entity.isOnFire()) {
            return false;
        }
        if (EntityUtil.isFriend(entity)) {
            return burnFriends.isOn() && filter.matches(entity) && targets.allows(entity);
        }
        return entity instanceof LivingEntity ? targets.attackable(entity, filter) : filter.matches(entity);
    }

    private static String nameOf(Entity entity) {
        return entity instanceof Player player ? EntityUtil.nameOf(player) : entity.getName().getString();
    }

    private boolean burn(Entity target) {
        return method.is(Method.LAVA) ? pourOn(target) : setAlight(target);
    }

    private boolean pourOn(Entity target) {
        LavaPours.Pour pour = pourFor(target);
        return pour != null && pours.pour(loan, pour);
    }

    // Null unless the lava can go down on this target without any risk of reaching you.
    private LavaPours.Pour pourFor(Entity target) {
        BlockPos feet = target.blockPosition();
        if (!mc.level.getFluidState(feet).isEmpty()) {
            return null;
        }
        // Lava over the head runs down onto them the first time it flows.
        if (spot.is(Spot.HEAD)) {
            LavaPours.Pour overHead = pours.plan(feet.above(2));
            if (overHead != null) {
                return overHead;
            }
        }
        return pours.plan(feet);
    }

    // Fire goes into the space the target stands in with a click on the top of the block below.
    // Grass in the way is broken first and that counts as the target's turn.
    private boolean setAlight(Entity target) {
        BlockPos cell = target.blockPosition();
        if (!fireReaches(cell)) {
            return false;
        }
        if (Ignition.clear(cell)) {
            return true;
        }
        if (!Ignition.fireFits(cell)) {
            return false;
        }
        BlockHitResult hit = Ignition.fireClick(cell, Direction.DOWN);
        if (!ignition.use(slots, hand -> Ignition.strike(hit, hand, rotate.isOn()))) {
            return false;
        }
        lit.put(cell, mc.player.tickCount);
        return true;
    }

    // Clear of you and near enough for the server to take a click on the block under it.
    private boolean fireReaches(BlockPos cell) {
        if (mc.player.position().distanceTo(Vec3.atBottomCenterOf(cell)) < keepAway.getValue()) {
            return false;
        }
        return BlockUtil.serverReaches(cell.below())
            && (BlockUtil.distanceTo(cell) <= wallsRange.getValue() || BlockUtil.canSee(cell));
    }

    // Punches out the fires this aura lit once they have burnt for the set time.
    private void putOutFires() {
        if (!putOut.isOn()) {
            lit.clear();
            return;
        }
        int now = mc.player.tickCount;
        int wait = putOutAfter.getInt();
        Iterator<Map.Entry<BlockPos, Integer>> it = lit.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            BlockPos cell = entry.getKey();
            int age = now - entry.getValue();
            // The clock starts again on a respawn and an age below nought is out of date.
            if (age < 0 || age > wait + GIVE_UP || !Ignition.isFire(cell)) {
                it.remove();
                continue;
            }
            if (age >= wait && BlockUtil.serverReaches(cell) && Ignition.putOut(cell)) {
                it.remove();
            }
        }
    }
}
