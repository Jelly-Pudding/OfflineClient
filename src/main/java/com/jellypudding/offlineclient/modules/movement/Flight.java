package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.MouseScrollEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Hop;
import com.jellypudding.offlineclient.util.HoverDip;
import com.jellypudding.offlineclient.util.MoveGate;
import com.jellypudding.offlineclient.util.MovementUtil;
import com.jellypudding.offlineclient.util.PacketUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class Flight extends Module {

    public enum Mode { CREATIVE, DIRECT, TELEPORT }
    public enum AntiKick { DIP, PACKET, OFF }

    // How much one wheel notch changes the speed and the teleport step.
    private static final double SCROLL_STEP = 0.1;
    private static final double SCROLL_BLOCKS = 0.5;

    private static final String TIMER_KEY = "flight";

    // The server stops counting hover ticks on a packet that drops past 0.03125.
    private static final double PACKET_DIP = 0.0313;

    // A rise this small wipes the fall the server holds. A teleport drop is followed by
    // one straight away and the first step is one.
    private static final double WIPE_RISE = 0.02;

    // A step that meets something is cut back this much at a time.
    private static final double STEP_BACK = 0.25;

    // A snapped step runs along the compass line this many degrees apart.
    private static final float AXIS_DEGREES = 90;

    // Runs after the modules that rewrite the ground flag of the client's own packet.
    private static final int AFTER_REWRITES = -50;

    // A step is compared with the packet that should carry it this closely.
    private static final double SAME_SPOT = 1.0E-7;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the flight handles.", Mode.CREATIVE)
        .describe(Mode.CREATIVE, "Flies like creative mode. Eases in and drifts to a stop.")
        .describe(Mode.DIRECT, "Moves the instant you press a key and stops dead when you let go.")
        .describe(Mode.TELEPORT, "Moves you in whole steps each tick with no momentum at all.");
    private final NumberSetting horizontalSpeed = new NumberSetting("Horizontal speed",
        "Speed along the ground. 1 matches creative flight.", 1, 0.1, 10, 0.1, "x")
        .visibleWhen(this::flowing);
    private final NumberSetting verticalSpeed = new NumberSetting("Vertical speed",
        "Up and down speed. 1 matches creative flight.", 1, 0.1, 10, 0.1, "x")
        .visibleWhen(this::flowing);
    private final NumberSetting step = new NumberSetting("Step",
        "How far each tick carries you across. Vanilla allows about twenty blocks a tick and Paper far more.",
        5, 0.5, 20, 0.5, " blocks").min(STEP_BACK).under(mode, Mode.TELEPORT);
    private final NumberSetting riseStep = new NumberSetting("Rise step",
        "How far each tick carries you up whilst you hold jump.", 4, 0.5, 20, 0.5, " blocks")
        .min(STEP_BACK).under(mode, Mode.TELEPORT);
    private final NumberSetting dropStep = new NumberSetting("Drop step",
        "How far each tick carries you down whilst you hold sneak.", 4, 0.5, 20, 0.5, " blocks")
        .min(STEP_BACK).under(mode, Mode.TELEPORT);
    private final NumberSetting ramp = new NumberSetting("Ramp",
        "Ticks the steps take to grow to full size once you start moving. Zero starts at full size.",
        0, 0, 20, 1, " ticks").under(mode, Mode.TELEPORT);
    private final BoolSetting snapToAxis = new BoolSetting("Snap to axis",
        "Steps run straight along the compass line you face.", false).under(mode, Mode.TELEPORT);
    private final BoolSetting throughFloors = new BoolSetting("Through floors",
        "Steps up and down pass through floors and ceilings. The server allows it.", false)
        .under(mode, Mode.TELEPORT);
    private final BoolSetting scrollSpeed = new BoolSetting("Scroll to change speed",
        "The mouse wheel changes the horizontal speed or the teleport step whilst you fly.", false);
    private final NumberSetting timer = new NumberSetting("Timer",
        "Speeds up the game whilst you fly. 1 is normal.", 1, 1, 3, 0.1, "x")
        .visibleWhen(this::flowing);
    private final BoolSetting keepFlightOn = new BoolSetting("Keep flight on",
        "Ignores the server when it switches your flight off.", true);
    private final BoolSetting stopWhenOff = new BoolSetting("Stop when switched off",
        "Switching Flight off stops you where you are. Off lets you keep the speed you had.", false)
        .visibleWhen(this::flowing);
    private final BoolSetting avoidLava = new BoolSetting("Avoid lava",
        "Lava stops you like a wall whilst you fly.", true);
    private final EnumSetting<AntiKick> antiKick = new EnumSetting<>("AntiKick",
        "How the vanilla flight kick is dodged.", AntiKick.DIP)
        .describe(AntiKick.DIP, "Drifts down a little now and then and climbs straight back. A climb"
            + " stops for the dip.")
        .describe(AntiKick.PACKET, "Tells the server you dipped whilst you stay put. Needs air below you."
            + " In teleport mode you really drift down and climb straight back.")
        .describe(AntiKick.OFF, "Does nothing about the kick.");
    private final NumberSetting antiKickInterval = new NumberSetting("Kick interval",
        "Server ticks between each little dip. The server kicks at 80. A low number leaves room for a"
            + " dip the server misses and for a game that stalls.", 30, 5, 80, 1, " ticks").min(1)
        .under(antiKick, AntiKick.DIP, AntiKick.PACKET);
    private final NumberSetting dipTicks = new NumberSetting("Dip ticks",
        "How many server ticks each dip lasts before the climb back.", 1, 1, 20, 1, " ticks")
        .under(antiKick, AntiKick.DIP);

    private final HoverDip dip = new HoverDip();

    // The height the server was last told whilst a packet dip lasts and whether the real one
    // still has to go back out.
    private double toldHeight = Double.NaN;
    private volatile boolean restorePending;

    // Where this tick's teleport step began. Fillers go out ahead of a long one.
    private Vec3 stepFrom;
    // This tick's step drops and whether the client's own packet carried it.
    private boolean stepDrops;
    private boolean stepSent;
    // The server may hold a fall that the next step has to wipe first.
    private boolean fallOwed;
    // Ticks the keys have been held for the ramp.
    private int movingTicks;

    public Flight() {
        super("Flight", "Lets you fly like in creative mode.", Category.MOVEMENT);
        addSettings(mode, horizontalSpeed, verticalSpeed, step, riseStep, dropStep, ramp, snapToAxis,
            throughFloors, scrollSpeed, timer, keepFlightOn, stopWhenOff, avoidLava, antiKick, antiKickInterval,
            dipTicks);
        searchTags("fly", "teleport fly");
    }

    @Override
    public String getSuffix() {
        return pace().getValueString();
    }

    // Read by BlockCollisionsMixin for every block you could bump into. Only the lava itself
    // turns solid. Resting on it never counts as hovering on the server. Once you are in lava
    // nothing walls you in and you can fly out.
    public boolean wallsLava(BlockState state) {
        LocalPlayer player = mc.player;
        if (!avoidLava.isOn() || player == null || !player.getAbilities().flying || player.isInLava()) {
            return false;
        }
        return state.getFluidState().is(FluidTags.LAVA);
    }

    // Read by LocalPlayerMixin instead of the fly speed creative flight pushes with.
    // The horizontal setting must not leak into it. A teleport step is pushed by nothing.
    public float verticalFlySpeed() {
        return flowing() && !dip.sinking() ? (float) (MovementUtil.VANILLA_FLY_SPEED * verticalSpeed.getValue())
            : 0;
    }

    @Override
    protected void onEnable() {
        dip.reset();
        toldHeight = Double.NaN;
        restorePending = false;
        fallOwed = true;
        movingTicks = 0;
    }

    @Override
    protected void onDisable() {
        Timer.override(TIMER_KEY, 1f);
        MovementUtil.endFlight();
        if (stopWhenOff.isOn() && flowing() && mc.player != null) {
            mc.player.setDeltaMovement(Vec3.ZERO);
        }
        stepFrom = null;
        stepDrops = false;
    }

    // The modes that fly with momentum rather than in steps.
    private boolean flowing() {
        return !mode.is(Mode.TELEPORT);
    }

    // What the wheel and the suffix show. The teleport step or the horizontal speed.
    private NumberSetting pace() {
        return flowing() ? horizontalSpeed : step;
    }

    // TickEvent stops at a disconnect. ClientTickEvent still runs in the menus.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!inGame()) {
            Timer.override(TIMER_KEY, 1f);
        }
    }

    @Subscribe
    private void onScroll(MouseScrollEvent event) {
        if (!scrollSpeed.isOn() || !inGame()) {
            return;
        }
        NumberSetting pace = pace();
        double notch = flowing() ? SCROLL_STEP : SCROLL_BLOCKS;
        double change = event.getAmount() > 0 ? notch : -notch;
        // Snapped to the notch. The value then reads cleanly after a long scroll.
        double next = Math.round((pace.getValue() + change) / notch) * notch;
        pace.setValue(Math.max(pace.getHardMin(), next));
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
        event.cancel();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        boolean moving = MovementUtil.hasInput()
            || mc.options.keyJump.isDown() || mc.options.keyShift.isDown();
        Timer.override(TIMER_KEY, moving && flowing() ? timer.getFloat() : 1f);

        // The server counts nobody it lets fly. A step that rises has to know of a dip before
        // it is taken.
        boolean counted = !mc.player.isCreative() && !mc.player.isSpectator();
        boolean dips = counted && (antiKick.is(AntiKick.DIP) || antiKick.is(AntiKick.PACKET) && !tellsOfDips());
        if (dips) {
            dip.plan(antiKickInterval.getInt(), antiKick.is(AntiKick.DIP) ? dipTicks.getInt() : 1,
                MovementUtil.verticalInput() == 0);
        } else if (!counted) {
            dip.reset();
        }
        switch (mode.getValue()) {
            case CREATIVE -> creativeTick();
            case DIRECT -> MovementUtil.flyDirect(MovementUtil.FLY_HORIZONTAL * horizontalSpeed.getValue(),
                MovementUtil.FLY_VERTICAL * verticalSpeed.getValue());
            case TELEPORT -> teleportTick();
        }
        if (flowing()) {
            fallOwed = true;
        }
        // The dip sets the speed and has to follow the teleport step that zeroes it.
        if (dips) {
            dip.apply();
        } else if (counted && antiKick.is(AntiKick.PACKET)) {
            packetDipTick();
        }
    }

    private void creativeTick() {
        Abilities abilities = mc.player.getAbilities();
        abilities.flying = true;
        abilities.setFlyingSpeed((float) (MovementUtil.VANILLA_FLY_SPEED * horizontalSpeed.getValue()));
    }

    // True whilst the packet dodge can send a dip of its own. A teleport step already sends a
    // packet every tick and a real dip rides on it. A real dip also stands in whilst Latency
    // holds the positions a packet dip would send.
    private boolean tellsOfDips() {
        return flowing() && !MoveGate.held(false);
    }

    // Hangs still in the air and moves the whole step in one go. The client's own packet
    // carries it to the server. A step straight after the last position would share its
    // server tick and waits.
    private void teleportTick() {
        MovementUtil.flyDirect(0, 0);
        stepFrom = null;
        stepDrops = false;
        stepSent = false;
        LocalPlayer player = mc.player;
        if (player.isPassenger() || Hop.travelling() || !MoveGate.settled()) {
            return;
        }
        Vec3 at = player.position();
        Vec3 to = fallOwed ? at.add(0, WIPE_RISE, 0) : nextStep(at);
        fallOwed = false;
        if (to.equals(at) || !Hop.safeAt(player, to)) {
            return;
        }
        player.setPos(to);
        stepFrom = at;
        stepDrops = to.y < at.y;
    }

    // Up or down first as the server moves a box and then across at the new height. A
    // step through a floor goes on its own.
    private Vec3 nextStep(Vec3 at) {
        // A dip is no dip under a step that rises.
        int vertical = dip.sinking() ? Math.min(0, MovementUtil.verticalInput()) : MovementUtil.verticalInput();
        Vec3 heading = snapToAxis.isOn()
            ? MovementUtil.inputDirection(Math.round(mc.player.getYRot() / AXIS_DEGREES) * AXIS_DEGREES)
            : MovementUtil.inputDirection();
        movingTicks = vertical != 0 || heading != Vec3.ZERO ? movingTicks + 1 : 0;
        if (movingTicks == 0) {
            return at;
        }
        double share = ramp.getValue() <= 0 ? 1 : Math.min(1, movingTicks / ramp.getValue());
        double reach = stepReach(vertical < 0);
        double rise = vertical > 0 ? Math.min(riseStep.getValue() * share, reach)
            : vertical < 0 ? -Math.min(dropStep.getValue() * share, reach) : 0;
        Vec3 mid = furthest(at, new Vec3(0, Math.signum(rise), 0), Math.abs(rise), !throughFloors.isOn());
        if (heading == Vec3.ZERO || !Hop.clearWay(mc.player, at, mid)) {
            return mid;
        }
        double climbed = mid.y - at.y;
        double left = Math.sqrt(Math.max(0, reach * reach - climbed * climbed));
        return furthest(mid, heading, Math.min(step.getValue() * share, left), true);
    }

    // The longest step one tick can carry. A drop leaves room for the rise that wipes its
    // fall in the same tick.
    private static double stepReach(boolean drops) {
        if (!drops || !MoveGate.bursts()) {
            return MoveGate.reach();
        }
        double best = 0;
        for (int fillers = MoveGate.maxFillers(); fillers >= 0; fillers--) {
            best = Math.max(best, Math.min(MoveGate.reach(fillers, 1), MoveGate.reach(fillers, 2)));
        }
        return best;
    }

    // The furthest spot along the way up to the length that lands clear of lava. A swept
    // way has to be open the whole length as well.
    private Vec3 furthest(Vec3 from, Vec3 way, double length, boolean swept) {
        for (double reach = swept ? openReach(from, way, length) : length; reach > 0; reach -= STEP_BACK) {
            Vec3 to = from.add(way.scale(reach));
            if (Hop.safeAt(mc.player, to)) {
                return to;
            }
        }
        return from;
    }

    // How far along the way the server's sweep stays open. A shorter way inside an open
    // one is open too.
    private double openReach(Vec3 from, Vec3 way, double length) {
        if (Hop.clearWay(mc.player, from, from.add(way.scale(length)))) {
            return length;
        }
        double open = 0;
        double shut = length;
        while (shut - open > STEP_BACK) {
            double middle = (open + shut) / 2;
            if (Hop.clearWay(mc.player, from, from.add(way.scale(middle)))) {
                open = middle;
            } else {
                shut = middle;
            }
        }
        return open;
    }

    // A step past what one packet may move is bought with fillers. Only a tick that needs
    // them sends them.
    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (stepFrom == null || mc.player == null) {
            return;
        }
        int fillers = MoveGate.fillersFor(mc.player.position().distanceTo(stepFrom));
        if (fillers > 0) {
            MoveGate.fillers(fillers, false);
        }
        stepFrom = null;
    }

    // Sends the dip itself once the interval is up. Waiting for the client's own packet is
    // no good since a still player only sends one every twenty ticks. The server measures a
    // drop from where it had the player as its own tick began. Every tick of the dip tells it
    // a height lower than the last until it has seen one. A dip into a block would be refused
    // by the server. It waits for air.
    private void packetDipTick() {
        LocalPlayer player = mc.player;
        if (restorePending) {
            // Nothing carried the real height back last tick.
            restorePending = false;
            sendHeight(player.getY());
            return;
        }
        boolean dipping = !Double.isNaN(toldHeight);
        if (dipping ? dip.seen(1) : !dip.due(antiKickInterval.getInt())) {
            endPacketDip();
            return;
        }
        double height = (dipping ? toldHeight : player.getY()) - PACKET_DIP;
        boolean open = mc.level.noCollision(player, player.getBoundingBox().move(0, height - player.getY(), 0));
        if (!open || !sendHeight(height)) {
            endPacketDip();
            return;
        }
        if (!dipping) {
            dip.begin();
        }
        toldHeight = height;
    }

    // The client's own packet of this tick carries the real height back.
    private void endPacketDip() {
        if (!Double.isNaN(toldHeight)) {
            toldHeight = Double.NaN;
            restorePending = true;
        }
    }

    private boolean sendHeight(double y) {
        return MoveGate.send(mc.player.getX(), y, mc.player.getZ(), mc.player.onGround());
    }

    // After NoFall. A teleport drop must not claim a landing the server would charge.
    @Subscribe(priority = AFTER_REWRITES)
    private void onPacketSend(PacketSendEvent event) {
        if (mc.player == null || !(event.getPacket() instanceof ServerboundMovePlayerPacket packet)) {
            return;
        }
        if (stepDrops && carriesStep(packet)) {
            stepSent = true;
            // The rise that follows wipes the fall instead.
            if (packet.isOnGround()) {
                event.setPacket(PacketUtil.withOnGround(packet, mc.player, false));
            }
            return;
        }
        restoreHeight(event, packet);
    }

    // The client's own packet with this tick's step and not another module's.
    private boolean carriesStep(ServerboundMovePlayerPacket packet) {
        Vec3 sent = new Vec3(packet.getX(0), packet.getY(0), packet.getZ(0));
        return packet.hasPosition() && sent.distanceToSqr(mc.player.position()) < SAME_SPOT;
    }

    // Wipes the fall a drop left straight after the packet that carried it. Without a tick
    // end in between the next step rises a hair first.
    @Subscribe
    private void onPostMotion(PostMotionEvent event) {
        if (!stepDrops || mc.player == null) {
            return;
        }
        stepDrops = false;
        if (!stepSent) {
            return;
        }
        Vec3 wipe = mc.player.position().add(0, WIPE_RISE, 0);
        if (Hop.safeAt(mc.player, wipe) && MoveGate.endTick() && MoveGate.send(wipe, false)) {
            mc.player.setPos(wipe);
        } else {
            fallOwed = true;
        }
    }

    // The client's own packet after a dip carries the real height back.
    // One without a position is upgraded to hold it.
    private void restoreHeight(PacketSendEvent event, ServerboundMovePlayerPacket packet) {
        if (!restorePending) {
            return;
        }
        if (packet.hasPosition()) {
            restorePending = false;
            return;
        }
        // Upgrading spends the tick's one position. The dip waits otherwise.
        if (MoveGate.free()) {
            restorePending = false;
            event.setPacket(PacketUtil.withPosition(packet, mc.player, mc.player.getX(),
                mc.player.getY(), mc.player.getZ(), packet.isOnGround()));
        }
    }

    // Fired on the netty thread. The server may not switch the flight off. The packet
    // is rewritten and vanilla lands the rest of it on the game thread.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        LocalPlayer player = mc.player;
        if (!keepFlightOn.isOn() || player == null
            || !(event.getPacket() instanceof ClientboundPlayerAbilitiesPacket packet)) {
            return;
        }
        Abilities current = player.getAbilities();
        Abilities kept = new Abilities();
        kept.invulnerable = packet.isInvulnerable();
        kept.instabuild = packet.canInstabuild();
        kept.setWalkingSpeed(packet.getWalkingSpeed());
        kept.flying = current.flying;
        kept.mayfly = current.mayfly;
        kept.setFlyingSpeed(current.getFlyingSpeed());
        event.setPacket(new ClientboundPlayerAbilitiesPacket(kept));
    }
}
