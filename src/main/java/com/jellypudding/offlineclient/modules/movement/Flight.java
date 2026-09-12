package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.MouseScrollEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.HoverDip;
import com.jellypudding.offlineclient.util.MovementUtil;
import com.jellypudding.offlineclient.util.PacketUtil;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.phys.Vec3;

public final class Flight extends Module {

    public enum Mode { CREATIVE, DIRECT }
    public enum AntiKick { DIP, PACKET, OFF }

    // How much one wheel notch changes the speed.
    private static final double SCROLL_STEP = 0.1;

    private static final String TIMER_KEY = "flight";

    // The server stops counting hover ticks on a packet that drops past 0.03125.
    private static final double PACKET_DIP = 0.0313;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the flight handles.", Mode.CREATIVE)
        .describe(Mode.CREATIVE, "Flies like creative mode. Eases in and drifts to a stop.")
        .describe(Mode.DIRECT, "Moves the instant you press a key and stops dead when you let go.");
    private final NumberSetting horizontalSpeed = new NumberSetting("Horizontal speed",
        "Speed along the ground. 1 matches creative flight.", 1, 0.1, 10, 0.1, "x").min(0.1);
    private final NumberSetting verticalSpeed = new NumberSetting("Vertical speed",
        "Up and down speed. 1 matches creative flight.", 1, 0.1, 10, 0.1, "x").min(0.1);
    private final BoolSetting scrollSpeed = new BoolSetting("Scroll to change speed",
        "The mouse wheel changes the horizontal speed whilst you fly.", false);
    private final NumberSetting timer = new NumberSetting("Timer",
        "Also speeds up the game whilst you fly. 1 does nothing.", 1, 1, 3, 0.1, "x").min(1);
    private final BoolSetting throughFluids = new BoolSetting("Through fluids",
        "Water and lava neither slow you down nor put you in the swimming pose.", true);
    private final BoolSetting holdAbilities = new BoolSetting("Hold abilities",
        "Stops a server ability update from flicking the flight off for a tick.", true)
        .under(mode, Mode.CREATIVE);
    private final EnumSetting<AntiKick> antiKick = new EnumSetting<>("AntiKick",
        "How the vanilla flight kick is dodged.", AntiKick.DIP)
        .describe(AntiKick.DIP, "Drifts down a little now and then and climbs straight back.")
        .describe(AntiKick.PACKET, "Tells the server you dipped whilst you stay put. Needs air below you.")
        .describe(AntiKick.OFF, "Does nothing about the kick.");
    private final NumberSetting antiKickInterval = new NumberSetting("Kick interval",
        "Ticks between each little dip.", 70, 5, 80, 1, " ticks").min(1)
        .under(antiKick, AntiKick.DIP, AntiKick.PACKET);
    private final NumberSetting dipTicks = new NumberSetting("Dip ticks",
        "How many ticks each dip lasts before the climb back.", 1, 1, 20, 1, " ticks").min(1)
        .under(antiKick, AntiKick.DIP);

    private final HoverDip dip = new HoverDip();

    // Ticks since the last packet dip and whether the real height still has to go back out.
    private int packetTicks;
    private volatile boolean restorePending;

    public Flight() {
        super("Flight", "Lets you fly like in creative mode.", Category.MOVEMENT);
        addSettings(mode, horizontalSpeed, verticalSpeed, scrollSpeed, timer, throughFluids,
            holdAbilities, antiKick, antiKickInterval, dipTicks);
        searchTags("fly");
    }

    @Override
    public String getSuffix() {
        return horizontalSpeed.getValueString();
    }

    // Read by LocalPlayerMixin instead of the fly speed creative flight pushes with.
    // The horizontal setting must not leak into it.
    public float verticalFlySpeed() {
        return (float) (MovementUtil.VANILLA_FLY_SPEED * verticalSpeed.getValue());
    }

    // Read by EntityMixin. Fluids stop counting as fluids for the local player.
    public boolean throughFluids() {
        return throughFluids.isOn();
    }

    @Override
    protected void onEnable() {
        dip.reset();
        packetTicks = 0;
        restorePending = false;
    }

    @Override
    protected void onDisable() {
        Timer.override(TIMER_KEY, 1f);
        if (mc.player == null) {
            return;
        }
        Abilities abilities = mc.player.getAbilities();
        abilities.setFlyingSpeed(MovementUtil.VANILLA_FLY_SPEED);
        if (!mc.player.isCreative() && !mc.player.isSpectator()) {
            abilities.flying = false;
        }
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
        double step = event.getAmount() > 0 ? SCROLL_STEP : -SCROLL_STEP;
        // Snapped to the step. The value then reads cleanly after a long scroll.
        double next = Math.round((horizontalSpeed.getValue() + step) / SCROLL_STEP) * SCROLL_STEP;
        horizontalSpeed.setValue(Math.max(horizontalSpeed.getHardMin(), next));
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
        event.cancel();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        boolean moving = MovementUtil.inputDirection().lengthSqr() > 0
            || mc.options.keyJump.isDown() || mc.options.keyShift.isDown();
        Timer.override(TIMER_KEY, moving ? timer.getFloat() : 1f);

        if (mode.is(Mode.CREATIVE)) {
            creativeTick();
        } else {
            directTick();
        }
        switch (antiKick.getValue()) {
            case DIP -> dip.tick(antiKickInterval.getInt(), dipTicks.getInt());
            case PACKET -> packetDipTick();
            case OFF -> { }
        }
    }

    private void creativeTick() {
        Abilities abilities = mc.player.getAbilities();
        abilities.flying = true;
        abilities.setFlyingSpeed((float) (MovementUtil.VANILLA_FLY_SPEED * horizontalSpeed.getValue()));
    }

    private void directTick() {
        Abilities abilities = mc.player.getAbilities();
        // The flying flag turns gravity off and zero speed disables the vanilla push.
        abilities.flying = true;
        abilities.setFlyingSpeed(0);

        double vertical = MovementUtil.FLY_VERTICAL * verticalSpeed.getValue();
        double vy = 0;
        if (mc.options.keyJump.isDown()) {
            vy += vertical;
        }
        if (mc.options.keyShift.isDown()) {
            vy -= vertical;
        }

        double horizontal = MovementUtil.FLY_HORIZONTAL * horizontalSpeed.getValue();
        Vec3 heading = MovementUtil.inputDirection();
        mc.player.setDeltaMovement(heading.x * horizontal, vy, heading.z * horizontal);
    }

    // Sends the dip itself once the interval is up. Waiting for the client's own
    // packet is no good since a still player only sends one every twenty ticks.
    // A dip into a block would be refused by the server. It waits for air.
    private void packetDipTick() {
        if (restorePending) {
            // Nothing carried the real height back last tick.
            restorePending = false;
            sendHeight(mc.player.getY());
            return;
        }
        packetTicks++;
        if (packetTicks < antiKickInterval.getInt()) {
            return;
        }
        if (!mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(0, -PACKET_DIP, 0))) {
            return;
        }
        packetTicks = 0;
        sendHeight(mc.player.getY() - PACKET_DIP);
        restorePending = true;
    }

    private void sendHeight(double y) {
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(mc.player.getX(), y,
            mc.player.getZ(), mc.player.onGround(), mc.player.horizontalCollision));
    }

    // The client's own packet after a dip carries the real height back.
    // One without a position is upgraded to hold it.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!restorePending || mc.player == null
            || !(event.getPacket() instanceof ServerboundMovePlayerPacket packet)) {
            return;
        }
        restorePending = false;
        if (!packet.hasPosition()) {
            event.setPacket(PacketUtil.withPosition(packet, mc.player, mc.player.getX(),
                mc.player.getY(), mc.player.getZ(), packet.isOnGround()));
        }
    }

    // Fired on the netty thread. The server may not switch the flight off.
    // The rest of what the packet carries still lands.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (!holdAbilities.isOn() || !mode.is(Mode.CREATIVE) || mc.player == null
            || !(event.getPacket() instanceof ClientboundPlayerAbilitiesPacket packet)) {
            return;
        }
        event.cancel();
        Abilities abilities = mc.player.getAbilities();
        abilities.invulnerable = packet.isInvulnerable();
        abilities.instabuild = packet.canInstabuild();
        abilities.setWalkingSpeed(packet.getWalkingSpeed());
    }
}
