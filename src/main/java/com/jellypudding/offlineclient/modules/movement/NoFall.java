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
import com.jellypudding.offlineclient.util.GlideMirror;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.Lagback;
import com.jellypudding.offlineclient.util.MoveGate;
import com.jellypudding.offlineclient.util.PacketUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.SoftLanding;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

// The server charges for the fall it holds on the packet that claims ground.
// Packet mode claims ground every tick of a fall and is charged for one tick at
// a time. Place mode softens the landing instead.
public final class NoFall extends Module {

    public enum Mode { PACKET, AIRBORNE, PLACE, AIR_PLACE, BOTH }
    public enum AirPlaceWhen { BEFORE_DAMAGE, BEFORE_DEATH }

    // A jump between dimensions moves the player further than any fall.
    private static final double TELEPORT_DROP = 64;

    // A held fall of one block past the safe distance hurts. The claim keeps a tenth of a block clear of it.
    private static final double HURT_MARGIN = 0.9;

    // Air place goes down before this much fall would hurt at all.
    private static final float DAMAGE_FALL = 2;

    // A position this far above the last one counts as a climb and the server forgets the
    // fall it holds.
    private static final double WIPE_RISE = 0.001;

    // A pull back this soon after a rising packet is the server's answer to it. The answer
    // time the tab list gives reads low for some time after a join.
    private static final int REFUSAL_TICKS = 2 * SharedConstants.TICKS_PER_SECOND;

    // Through a glide the server cuts the fall it holds to this much on every tick its own
    // copy of the glide sinks slower than this.
    private static final double GLIDE_KEPT = 1;
    private static final double GLIDE_CUT_SINK = -0.5;

    // The server judges a cut by the sink its copy had going into its tick and that tick
    // can fall a whole tick either side of where the mirror puts it. A cut only counts
    // once the copy has sunk slowly for this many steps running.
    private static final int SLOW_STEPS = 3;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the fall is stopped.", Mode.PACKET)
        .describe(Mode.PACKET, "Tells the server you have landed on every packet whilst you fall.")
        .describe(Mode.AIRBORNE, "Never tells the server you are on the ground. A fall it never sees land costs"
            + " nothing. Blocks break slower by hand meanwhile.")
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
    private final BoolSetting switchBack = new BoolSetting("Switch back",
        "Puts the hotbar back on the slot you held once you have landed. Switched off it stays where the"
            + " landing left it.", true)
        .under(mode, Mode.PLACE, Mode.AIR_PLACE, Mode.BOTH);
    private final BoolSetting anchor = new BoolSetting("Anchor",
        "Centres you first to put the landing block right under you.", true)
        .under(mode, Mode.PLACE, Mode.AIR_PLACE, Mode.BOTH);
    private final BoolSetting limitSpeed = new BoolSetting("Limit fall speed",
        "Keeps you under four blocks a tick. Anything faster hurts even with NoFall.", true)
        .under(mode, Mode.PACKET, Mode.BOTH);
    private final BoolSetting dives = new BoolSetting("Whilst gliding",
        "Takes the fall out of a glide that dives into the ground. The server hears you touch down"
            + " one tick late.", false)
        .under(mode, Mode.PACKET, Mode.BOTH);
    private final NumberSetting minFall = new NumberSetting("Min fall",
        "Short drops are left alone until the fall passes this. Three blocks is the most"
            + " that is safe because a longer one already hurts.",
        2.5, 0, 3, 0.1, " blocks").max(3)
        .under(mode, Mode.PACKET, Mode.PLACE, Mode.AIR_PLACE, Mode.BOTH);
    private final BoolSetting antiBounce = new BoolSetting("Anti bounce",
        "Landing on a slime block or a bed never bounces you.", true);
    private final BoolSetting pauseOnMace = new BoolSetting("Pause on mace",
        "Leaves the fall alone whilst you hold a mace. The smash still lands.", true)
        .under(mode, Mode.PACKET, Mode.PLACE, Mode.AIR_PLACE, Mode.BOTH);

    // The fall as the client sees it. Place modes read this.
    private volatile double descent;
    private double lastY;
    private boolean tracking;
    // True whilst the last movement packet claimed ground for a player in the air.
    private boolean claimed;
    // The last position that left. Null until one has.
    private Vec3 sent;
    // Whether the player glided on the last tick and whether a glide has just ended in the air.
    private boolean glided;
    private boolean wipe;
    // The fall a rising packet hid. The next position banks it on top of its own tick.
    private double hidden;
    // The tick up to which a pull back means the server refused the rising packet and
    // whether the rise that is due is the second go at one it refused.
    private int refusedBy = Integer.MIN_VALUE;
    private boolean retry;
    // The fall the server holds through a glide and whether its next tick cuts it. Then
    // the steps its copy of the glide has sunk slowly for without a break.
    private double glideFall;
    private boolean cutDue;
    private int slowSteps;
    // True when this tick's packet lands a dive the server would charge for. That packet is
    // told where to hold the player instead and once it has left like that the landing
    // itself is owed on the next tick.
    private boolean touchdown;
    private Vec3 hold;
    private boolean landingOwed;
    // True when the mode was Airborne on the tick before.
    private boolean airborne;

    private final Lagback.Watcher lagback = new Lagback.Watcher();
    private final GlideMirror mirror = GlideMirror.get();

    private final SoftLanding landing = new SoftLanding();
    private final LastRise lastRise = new LastRise();
    private final InventoryUtil.HotbarLoan airPlaceLoan = new InventoryUtil.HotbarLoan();

    public NoFall() {
        super("NoFall", "Stops fall damage.", Category.MOVEMENT);
        addSettings(mode, limitSpeed, dives, landings, pickUp, airPlaceWhen, switchBack, anchor, minFall,
            antiBounce, pauseOnMace);
        searchTags("fall damage", "water bucket", "clutch", "air place", "no ground");
    }

    @Override
    public String getSuffix() {
        SoftLanding.Kind pending = landing.pending();
        return pending == null ? null : EnumSetting.label(pending);
    }

    // Read by the pathfinder. The claim on every packet of a fall leaves no height that hurts
    // for as long as the module would make that claim.
    public boolean takesAnyDrop() {
        LocalPlayer player = mc.player;
        return isEnabled() && player != null && (mode.is(Mode.AIRBORNE) || packetMode() && guarded(player));
    }

    // Read by EntityMixin. Slime and beds check this before they bounce.
    public boolean suppressesBounce() {
        return isEnabled() && antiBounce.isOn();
    }

    @Override
    protected void onEnable() {
        reset();
    }

    // The mode may have changed in the tick that switches the module off. The bank is still
    // there for as long as the last packets went out airborne.
    @Override
    protected void onDisable() {
        if (airborne) {
            forgetBank();
        }
        reset();
    }

    // Airborne leaves the server holding every drop since the last climb. The first movement
    // packet to leave once the mode is over goes out a hair above the last position instead.
    // The server takes that for a climb and forgets them. The truth follows on the next packet.
    private void forgetBank() {
        if (inGame() && sent != null) {
            lastRise.from = sent;
            watch(lastRise);
        }
    }

    private final class LastRise {

        private Vec3 from;

        // After the module's own handler. This packet is not for it to rewrite.
        @Subscribe(priority = 40)
        private void onPacketSend(PacketSendEvent event) {
            LocalPlayer player = mc.player;
            if (player == null) {
                unwatch(this);
            } else if (event.getPacket() instanceof ServerboundMovePlayerPacket packet) {
                unwatch(this);
                event.setPacket(PacketUtil.withPosition(packet, player, from.x, from.y + WIPE_RISE, from.z, false));
            }
        }
    }

    private void reset() {
        lagback.sync();
        descent = 0;
        tracking = false;
        claimed = false;
        sent = null;
        glided = false;
        wipe = false;
        hidden = 0;
        refusedBy = Integer.MIN_VALUE;
        retry = false;
        glideFall = 0;
        cutDue = false;
        slowSteps = 0;
        touchdown = false;
        hold = null;
        landingOwed = false;
        airborne = false;
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
        boolean holdsBank = mode.is(Mode.AIRBORNE);
        if (airborne && !holdsBank) {
            forgetBank();
        }
        airborne = holdsBank;
        trackDescent();
        sendOwedLanding();
    }

    // A ground claim would end an open glide and the fall of one is left to its landing.
    private boolean protecting() {
        // The packet thread can lose the player mid handler.
        LocalPlayer player = mc.player;
        return player != null && guarded(player) && !player.isFallFlying();
    }

    // True whilst the player has a fall worth stopping at all.
    private boolean guarded(LocalPlayer player) {
        if (player.getAbilities().invulnerable || player.isPassenger()) {
            return false;
        }
        return !pauseOnMace.isOn() || !holdingMace();
    }

    // A pull back moves the player and leaves the fall the server holds as it was. The
    // count carries on from the height the server chose.
    private void followLagback() {
        if (lagback.happened()) {
            tracking = false;
        }
    }

    // A pull back straight after a rising packet means the server refused the rise and still
    // holds the whole fall. The rise gets one more go from where the server put the player.
    // It is armed as the answer to the pull back leaves. The server throws away every
    // position sent before that answer and takes the one that follows it.
    private void retryRise(LocalPlayer player) {
        if (player.tickCount <= refusedBy) {
            refusedBy = Integer.MIN_VALUE;
            retry = true;
            wipe = true;
            descent = 0;
            hidden = 0;
        }
    }

    private void trackDescent() {
        followLagback();
        boolean gliding = mc.player.isFallFlying();
        // A glide covers the tick it ends on.
        boolean flown = gliding || glided;
        wipe |= glided && !gliding;
        glided = gliding;
        double y = mc.player.getY();
        if (!tracking) {
            lastY = y;
            tracking = true;
        }
        double drop = lastY - y;
        lastY = y;
        trackGlide(flown, drop);
        if (mc.player.onGround() || mc.player.isInWater() || mc.player.isPassenger()) {
            descent = 0;
        } else if (drop > TELEPORT_DROP || drop < 0) {
            // A climb or a teleport wipes what the server was tracking.
            descent = 0;
        } else {
            descent += drop;
        }
    }

    // The server banks every drop of a glide. It cuts the bank on each tick of its own that
    // its copy of the glide went in sinking slowly and a climb or water wipes it. A dive is
    // banked whole. A count that runs high here holds a landing back for nothing and one
    // that runs low lets a dive through.
    private void trackGlide(boolean flown, double drop) {
        if (!flown || drop < 0 || drop > TELEPORT_DROP || mc.player.isInWater()) {
            glideFall = 0;
            cutDue = false;
            slowSteps = 0;
            touchdown = false;
            return;
        }
        if (cutDue) {
            glideFall = Math.min(glideFall, GLIDE_KEPT);
        }
        glideFall += drop;
        // The mirror is only asked whilst the option wants it. Without it nothing is cut.
        boolean watched = dives.isOn() && packetMode();
        boolean sinksSlowly = watched && mirror.ahead(mc.player).next().movement().y > GLIDE_CUT_SINK;
        slowSteps = sinksSlowly ? Math.min(SLOW_STEPS, slowSteps + 1) : 0;
        cutDue = slowSteps == SLOW_STEPS;
        touchdown = watched && mc.player.onGround() && guarded(mc.player)
            && glideFall > 0 && glideFall >= minFall.getValue();
    }

    // The landing a held packet kept from the server. A player already off the ground
    // again has none to tell.
    private void sendOwedLanding() {
        if (!landingOwed) {
            return;
        }
        landingOwed = false;
        touchdown = false;
        if (mc.player.onGround()) {
            MoveGate.send(mc.player.position(), true);
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
        // The packet thread can lose the player mid handler.
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (event.getPacket() instanceof ServerboundPlayerCommandPacket command) {
            takeBackClaim(player, command);
            return;
        }
        if (event.getPacket() instanceof ServerboundAcceptTeleportationPacket) {
            retryRise(player);
            return;
        }
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket packet)) {
            return;
        }
        if (mode.is(Mode.AIRBORNE)) {
            // A claim another module makes for a block it breaks is left as it is.
            if (packet.isOnGround() && !PacketUtil.isGroundClaim(packet)) {
                event.setPacket(PacketUtil.withOnGround(packet, player, false));
            }
            return;
        }
        if (touchdown && packet.hasPosition() && packet.isOnGround() && holdsLanding(event, packet, player)) {
            wipe = false;
            return;
        }
        // Place counts the glide itself and needs no fall forgotten.
        if (wipe && packet.hasPosition()) {
            wipe = false;
            if (packetMode() && wipesGlideFall(event, packet, player)) {
                return;
            }
        }
        if (packet.hasPosition()) {
            hidden = 0;
        }
        claimed = packetMode() && !packet.isOnGround() && packet.hasPosition() && claimsGround(player);
        if (claimed) {
            event.setPacket(PacketUtil.withOnGround(packet, player, true));
        }
    }

    // A glide that ends in the air leaves behind whatever the server banked during it. The
    // server only cut that to one block whilst its own copy of the glide sank slowly and a
    // claim would pay for the rest. The tick's own position goes out a hair above the last
    // one instead. The server takes that for a climb and forgets the lot. No ground is
    // claimed on it and the drop it hides is banked with the next packet.
    private boolean wipesGlideFall(PacketSendEvent event, ServerboundMovePlayerPacket packet, LocalPlayer player) {
        boolean again = retry;
        retry = false;
        double y = packet.getY(player.getY());
        if (sent == null && !again) {
            return false;
        }
        // A second go rises from the packet's own height. That is where the server holds the player.
        double from = again ? y : sent.y;
        if (y > from || player.onGround() || player.isInWater() || !protecting()) {
            return false;
        }
        double risen = from + WIPE_RISE;
        event.setPacket(PacketUtil.withPosition(packet, player, packet.getX(player.getX()), risen,
            packet.getZ(player.getZ()), false));
        descent = risen - player.getY();
        hidden = descent;
        claimed = false;
        if (!again) {
            refusedBy = player.tickCount + Math.max(ServerInfo.answerTicks(), REFUSAL_TICKS);
        }
        return true;
    }

    // A dive that touches down pays for all the server banked on the way. The packet that
    // would land it tells the server the spot it holds the player at a hair higher instead.
    // The server takes that for a climb and forgets the bank. The landing follows a tick
    // later and pays for the one tick of the dive it covers. No glide sinks four blocks a tick.
    private boolean holdsLanding(PacketSendEvent event, ServerboundMovePlayerPacket packet, LocalPlayer player) {
        if (sent == null || packet.getY(player.getY()) > sent.y) {
            return false;
        }
        Vec3 held = sent.add(0, WIPE_RISE, 0);
        if (!player.level().noCollision(player, player.getBoundingBox().move(held.subtract(player.position())))) {
            return false;
        }
        event.setPacket(PacketUtil.withPosition(packet, player, held.x, held.y, held.z, false));
        hold = held;
        return true;
    }

    // Last of all because only a packet that leaves moves the server's copy of the player.
    // A packet MoveGate sends leaves as it was built and another module may rewrite one
    // after this. The landing is only owed once the hold itself has left. A ground claim
    // that leaves instead is charged for all the server banked and nothing is left to hold.
    @Subscribe(priority = Subscribe.LAST)
    private void onPacketSent(PacketSendEvent event) {
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket packet) || !packet.hasPosition()) {
            return;
        }
        sent = new Vec3(packet.getX(0), packet.getY(0), packet.getZ(0));
        if (packet.isOnGround()) {
            glideFall = 0;
            touchdown = false;
        } else if (sent.equals(hold)) {
            touchdown = false;
            landingOwed = true;
        }
        hold = null;
    }

    // The server opens a glide only for a player it takes to be in the air. A claim still
    // standing from the tick before would make it refuse and it is taken back first. An
    // opener raises the flag before its command leaves and a close lowers it first.
    private void takeBackClaim(LocalPlayer player, ServerboundPlayerCommandPacket command) {
        if (claimed && player.isFallFlying()
            && command.getAction() == ServerboundPlayerCommandPacket.Action.START_FALL_FLYING) {
            claimed = false;
            PacketUtil.claimAir(player);
        }
    }

    private boolean claimsGround(LocalPlayer player) {
        followLagback();
        if (player.onGround() || player.isInWater() || !protecting()) {
            return false;
        }
        // A descent faster than gravity can carry the held fall past the damage line in a
        // single tick. The claim then goes out a tick early.
        double nextDrop = Math.max(0, -player.getDeltaMovement().y);
        return descent >= minFall.getValue() || descent + nextDrop >= hurtingFall(player);
    }

    private static double hurtingFall(LocalPlayer player) {
        return DamageUtil.safeFall(player) + HURT_MARGIN;
    }

    // The server charges each landed packet for its own drop past the safe distance. This
    // runs last and catches FastFall and Step and Flight alike.
    @Subscribe(priority = -100)
    private void onLimitTick(TickEvent event) {
        if (!packetMode() || !limitSpeed.isOn() || !protecting()) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        double most = fastestFall();
        if (velocity.y < -most) {
            mc.player.setDeltaMovement(velocity.x, -most, velocity.z);
        }
    }

    // The most the player may drop this tick. A rising packet hides the fall of its own tick
    // and the packet after it banks two ticks at once. The pair stays under the line together.
    private double fastestFall() {
        double hurting = hurtingFall(mc.player);
        if (glided && !mc.player.onGround()) {
            return hurting / 2;
        }
        return Math.max(0, hurting - hidden);
    }

    // Runs late to see the speed every other module has settled on for this tick.
    // A mode change mid clutch still clears up what is already down.
    @Subscribe(priority = -90)
    private void onTick(TickEvent event) {
        landing.switchBack(switchBack.isOn());
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
    // The block lands where the feet are.
    private void airPlaceTick() {
        if (!falling() || descent <= airPlaceThreshold()) {
            airPlaceLoan.giveBack(switchBack.isOn());
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
        BlockUtil.placeAny(below, true, true);
    }

    // Before any damage or only once the fall would take every heart.
    private float airPlaceThreshold() {
        if (airPlaceWhen.is(AirPlaceWhen.BEFORE_DAMAGE)) {
            return DAMAGE_FALL;
        }
        return Math.max(EntityUtil.totalHealth(mc.player), DAMAGE_FALL);
    }
}
