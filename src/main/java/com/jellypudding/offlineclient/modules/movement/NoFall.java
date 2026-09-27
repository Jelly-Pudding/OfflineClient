package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RankSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.Lagback;
import com.jellypudding.offlineclient.util.PacketUtil;
import com.jellypudding.offlineclient.util.SoftLanding;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

// The server charges for the fall it holds on the packet that claims ground.
// Packet mode claims ground every tick of a fall and is charged for one tick at
// a time. Place mode softens the landing instead.
public final class NoFall extends Module {

    public enum Mode { PACKET, PLACE, AIR_PLACE, BOTH }
    public enum AirPlaceWhen { BEFORE_DAMAGE, BEFORE_DEATH }

    // A jump between dimensions moves the player further than any fall.
    private static final double TELEPORT_DROP = 64;

    // A held fall of one block past the safe distance hurts. The claim keeps clear of it.
    private static final double HURTING_FALL = DamageUtil.SAFE_FALL + 0.9;

    // Air place goes down before this much fall would hurt at all.
    private static final float DAMAGE_FALL = 2;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the fall is stopped.", Mode.PACKET)
        .describe(Mode.PACKET, "Tells the server you have landed on every packet whilst you fall.")
        .describe(Mode.PLACE, "Puts one of your landing items under you before you land. Works on any server.")
        .describe(Mode.AIR_PLACE, "Puts any block from your hotbar under your feet just before the fall would hurt.")
        .describe(Mode.BOTH, "Does Packet and Place at once. Either one saves you if the other fails.");
    private final RankSetting<SoftLanding.Kind> landings = new RankSetting<>("Landing items",
        "What Place puts under you. It uses the first one on the list that you carry.",
        SoftLanding.Kind.class, SoftLanding.Kind::icon)
        .under(mode, Mode.PLACE, Mode.BOTH);
    private final BoolSetting pickUp = new BoolSetting("Collect",
        "Picks the water or powder snow back up once it has broken your fall.", true)
        .under(mode, Mode.PLACE, Mode.BOTH);
    private final EnumSetting<AirPlaceWhen> airPlaceWhen = new EnumSetting<>("Air place when",
        "How far you fall before the block goes down.", AirPlaceWhen.BEFORE_DEATH)
        .describe(AirPlaceWhen.BEFORE_DAMAGE, "Before the fall would hurt at all.")
        .describe(AirPlaceWhen.BEFORE_DEATH, "Only once the fall would kill you.")
        .under(mode, Mode.AIR_PLACE);
    private final BoolSetting anchor = new BoolSetting("Anchor",
        "Centres you first to put the landing block right under you.", true)
        .under(mode, Mode.PLACE, Mode.AIR_PLACE, Mode.BOTH);
    private final BoolSetting limitSpeed = new BoolSetting("Limit fall speed",
        "Keeps you under four blocks a tick. Anything faster hurts even with NoFall.", true)
        .under(mode, Mode.PACKET, Mode.BOTH);
    private final NumberSetting minFall = new NumberSetting("Min fall",
        "Short drops are left alone until the fall passes this. Three blocks is the most"
            + " that is safe because a longer one already hurts.",
        2.5, 0, 3, 0.1, " blocks").min(0).max(3);
    private final BoolSetting antiBounce = new BoolSetting("Anti bounce",
        "Landing on a slime block or a bed never bounces you.", true);
    private final BoolSetting pauseOnMace = new BoolSetting("Pause on mace",
        "Leaves the fall alone whilst you hold a mace. The smash still lands.", true);

    // The fall as the client sees it. Place modes read this.
    private volatile double descent;
    private double lastY;
    private boolean tracking;

    private final Lagback.Watcher lagback = new Lagback.Watcher();

    private final SoftLanding landing = new SoftLanding();
    private final InventoryUtil.HotbarLoan airPlaceLoan = new InventoryUtil.HotbarLoan();

    public NoFall() {
        super("NoFall", "Stops fall damage.", Category.MOVEMENT);
        addSettings(mode, limitSpeed, landings, pickUp, airPlaceWhen, anchor, minFall, antiBounce,
            pauseOnMace);
        searchTags("fall damage", "water bucket", "clutch", "air place");
    }

    @Override
    public String getSuffix() {
        SoftLanding.Kind pending = landing.pending();
        return pending == null ? null : EnumSetting.label(pending);
    }

    // Read by EntityMixin. Slime and beds check this before they bounce.
    public boolean suppressesBounce() {
        return isEnabled() && antiBounce.isOn();
    }

    @Override
    protected void onEnable() {
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        lagback.sync();
        descent = 0;
        tracking = false;
        landing.reset();
        airPlaceLoan.giveBack();
    }

    private boolean packetMode() {
        return mode.isAny(Mode.PACKET, Mode.BOTH);
    }

    private boolean placeMode() {
        return mode.isAny(Mode.PLACE, Mode.BOTH);
    }

    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (!inGame()) {
            reset();
            return;
        }
        trackDescent();
    }

    private boolean protecting() {
        // The packet thread can lose the player mid handler.
        LocalPlayer player = mc.player;
        if (player == null || player.getAbilities().invulnerable || player.isPassenger()) {
            return false;
        }
        // An open glide holds the fall the server banks at one block and a ground claim
        // would end the flight. Nothing needs stopping.
        if (player.isFallFlying()) {
            return false;
        }
        return !pauseOnMace.isOn() || !holdingMace();
    }

    // The server moved the player and the fall it holds with them.
    private void forgetOnLagback() {
        if (lagback.happened()) {
            descent = 0;
            tracking = false;
        }
    }

    private void trackDescent() {
        forgetOnLagback();
        double y = mc.player.getY();
        if (!tracking) {
            lastY = y;
            tracking = true;
        }
        double drop = lastY - y;
        lastY = y;
        if (mc.player.onGround() || mc.player.isInWater() || mc.player.isPassenger()) {
            descent = 0;
        } else if (drop > TELEPORT_DROP || drop < 0) {
            // A climb or a teleport wipes what the server was tracking.
            descent = 0;
        } else {
            descent += drop;
        }
    }

    private boolean holdingMace() {
        LocalPlayer player = mc.player;
        return player != null && (player.getMainHandItem().is(Items.MACE)
            || player.getOffhandItem().is(Items.MACE));
    }

    // Damage is floor(held fall minus three). One tick of gravity never reaches
    // four blocks and the claim clears what the server holds.
    @Subscribe(priority = 50)
    private void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled() || !packetMode()
            || !(event.getPacket() instanceof ServerboundMovePlayerPacket packet)
            || packet.isOnGround() || !packet.hasPosition()) {
            return;
        }
        // The packet thread can lose the player mid handler.
        LocalPlayer player = mc.player;
        if (player == null || !claimsGround(player)) {
            return;
        }
        event.setPacket(PacketUtil.withOnGround(packet, player, true));
    }

    private boolean claimsGround(LocalPlayer player) {
        forgetOnLagback();
        if (player.onGround() || player.isInWater() || !protecting()) {
            return false;
        }
        // A descent faster than gravity can carry the held fall past the damage line in a
        // single tick. The claim then goes out a tick early.
        double nextDrop = Math.max(0, -player.getDeltaMovement().y);
        return descent >= minFall.getValue() || descent + nextDrop >= HURTING_FALL;
    }

    // The server charges each landed packet for its own drop past three blocks. This
    // runs last and catches FastFall and Step and Flight alike.
    @Subscribe(priority = -100)
    private void onLimitTick(TickEvent event) {
        if (!inGame() || !packetMode() || !limitSpeed.isOn() || !protecting()) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        if (velocity.y < -HURTING_FALL) {
            mc.player.setDeltaMovement(velocity.x, -HURTING_FALL, velocity.z);
        }
    }

    // Runs late to see the speed every other module has settled on for this tick.
    // A mode change mid clutch still clears up what is already down.
    @Subscribe(priority = -90)
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (placeMode() && falling()) {
            landing.fall(landings.ranked(), descent, anchor.isOn());
        } else {
            landing.settle(placeMode() && pickUp.isOn());
        }
        if (mode.is(Mode.AIR_PLACE)) {
            airPlaceTick();
        }
    }

    // True whilst a fall is running that the module ought to be watching.
    private boolean falling() {
        return protecting() && !mc.player.onGround() && !mc.player.isInWater()
            && mc.player.getDeltaMovement().y < 0;
    }

    // Any block from the hotbar goes under the feet once the fall is deep enough.
    // The fall is paused for the click. The block lands where the feet are.
    private void airPlaceTick() {
        if (!falling() || descent <= airPlaceThreshold()) {
            airPlaceLoan.giveBack();
            return;
        }
        BlockPos below = mc.player.blockPosition().below();
        int slot = BlockUtil.findBlockSlot();
        if (slot == -1 || !BlockUtil.isReplaceable(below) || !airPlaceLoan.select(slot)) {
            return;
        }
        if (anchor.isOn()) {
            BlockUtil.centerPlayer();
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(velocity.x, 0, velocity.z);
        try {
            BlockUtil.placeAny(below, true, true);
        } finally {
            mc.player.setDeltaMovement(velocity);
        }
    }

    // Before any damage or only once the fall would take every heart.
    private float airPlaceThreshold() {
        if (airPlaceWhen.is(AirPlaceWhen.BEFORE_DAMAGE)) {
            return DAMAGE_FALL;
        }
        return Math.max(EntityUtil.totalHealth(mc.player), DAMAGE_FALL);
    }
}
