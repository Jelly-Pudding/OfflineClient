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
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.PacketUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.BooleanSupplier;

/**
 * Packet mode spoofs the ground flag once the drop passes the threshold which
 * keeps short hops honest. Bucket mode breaks the fall with real water.
 */
public final class NoFall extends Module {

    public enum Mode {
        PACKET("Packet"),
        BUCKET("Water bucket"),
        BOTH("Both");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    // A jump between dimensions moves the player further than any fall.
    private static final double TELEPORT_DROP = 64;

    // Blocks of fall the game forgives before it hurts.
    private static final float SAFE_FALL = 3;

    // Ticks to leave the water alone before collecting it.
    private static final int PICKUP_DELAY = 2;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Packet lies about the ground. Bucket puts real water down and survives a strict server.",
        Mode.PACKET);
    private final NumberSetting minFall = new NumberSetting("Min fall",
        "Start spoofing once you have dropped this many blocks.", 3, 1, 20, 0.5, " blocks").min(0.5)
        .visibleWhen(() -> mode.getValue() != Mode.BUCKET);
    private final BoolSetting elytra = new BoolSetting("Elytra",
        "Also protect whilst gliding at the risk of a rubberband.", true)
        .visibleWhen(() -> mode.getValue() != Mode.BUCKET);
    private final NumberSetting minElytraFall = new NumberSetting("Min elytra fall",
        "Separate threshold whilst gliding.", 2, 1, 20, 0.5, " blocks").min(0.5)
        .visibleWhen(() -> elytra.isOn() && mode.getValue() != Mode.BUCKET);
    private final BoolSetting pauseOnMace = new BoolSetting("Pause on mace",
        "Stop spoofing whilst holding a mace that needs a real fall to smash.", true);
    private final BoolSetting pickUp = new BoolSetting("Collect water",
        "Picks the water back up once it has broken your fall.", true)
        .visibleWhen(() -> mode.getValue() != Mode.PACKET);

    private double descent;
    private double lastY;

    /**
     * A single honest packet in the middle of a fall lets the server bank the
     * drop and hurt the player. Read from the packet thread.
     */
    private volatile boolean spoofing;

    private boolean tracking;

    private final InventoryUtil.SlotSwap slots = new InventoryUtil.SlotSwap();
    private BlockPos placedWater;
    private int pickupTimer;

    public NoFall() {
        super("NoFall", "Stops fall damage.", Category.MOVEMENT);
        addSettings(mode, minFall, elytra, minElytraFall, pauseOnMace, pickUp);
        searchTags("fall damage", "water bucket", "clutch");
    }

    @Override
    public String getSuffix() {
        if (placedWater != null) {
            return "water";
        }
        return spoofing ? "active" : null;
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
        descent = 0;
        spoofing = false;
        tracking = false;
        placedWater = null;
        pickupTimer = 0;
        slots.restore();
    }

    private boolean packetMode() {
        return mode.getValue() != Mode.BUCKET;
    }

    private boolean bucketMode() {
        return mode.getValue() != Mode.PACKET;
    }

    // Runs once per tick just before the movement packet is built.
    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (!inGame()) {
            reset();
            return;
        }
        double y = mc.player.getY();
        if (!tracking) {
            lastY = y;
            tracking = true;
        }
        double drop = lastY - y;
        lastY = y;

        if (mc.player.onGround()) {
            descent = 0;
            spoofing = false;
            return;
        }
        if (drop > TELEPORT_DROP || drop < -TELEPORT_DROP) {
            // A teleport is not a fall.
            descent = 0;
            return;
        }
        if (drop > 0) {
            descent += drop;
        } else if (drop < 0 || mc.player.getDeltaMovement().y > 0) {
            // Any climb wipes the fall the server was tracking.
            descent = 0;
        }
        if (!packetMode() || (pauseOnMace.isOn() && holdingMace())) {
            spoofing = false;
            return;
        }
        double threshold = mc.player.isFallFlying() ? minElytraFall.getValue() : minFall.getValue();
        if (descent >= threshold) {
            spoofing = true;
        }
    }

    private boolean holdingMace() {
        return mc.player.getMainHandItem().is(Items.MACE)
            || mc.player.getOffhandItem().is(Items.MACE);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (pickupTimer > 0) {
            pickupTimer--;
        }
        // A mode change mid clutch still has to clear up the water already down.
        if (placedWater != null) {
            collect();
            return;
        }
        if (!bucketMode()) {
            return;
        }
        if (!needsCatching()) {
            slots.restore();
            return;
        }
        placeWater();
    }

    // True whilst a fall is running that would hurt and the ground is within reach.
    private boolean needsCatching() {
        LocalPlayer player = mc.player;
        if (player.getAbilities().invulnerable || player.isFallFlying()
            || player.onGround() || player.isInWater() || player.getDeltaMovement().y >= 0) {
            return false;
        }
        if (pauseOnMace.isOn() && holdingMace()) {
            return false;
        }
        // Water boils away in the nether. A clutch there would do nothing.
        if (mc.level.environmentAttributes()
            .getValue(EnvironmentAttributes.WATER_EVAPORATES, player.blockPosition())) {
            return false;
        }
        return player.fallDistance > SAFE_FALL && groundBelow() != null;
    }

    // The ground straight below within arm's length or null.
    private BlockPos groundBelow() {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.subtract(0, mc.player.blockInteractionRange(), 0);
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end,
            ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    // The bucket raycasts from the live client rotation and the packet carries it.
    // Pointing down for the call itself is enough.
    private void placeWater() {
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.WATER_BUCKET));
        BlockPos ground = groundBelow();
        if (slot == -1 || ground == null) {
            return;
        }
        slots.select(slot);
        boolean placed = lookingDown(() ->
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND).consumesAction());
        if (placed) {
            mc.player.swing(InteractionHand.MAIN_HAND);
            placedWater = ground.above();
            pickupTimer = PICKUP_DELAY;
        }
    }

    private boolean lookingDown(BooleanSupplier action) {
        float pitch = mc.player.getXRot();
        mc.player.setXRot(90f);
        try {
            return action.getAsBoolean();
        } finally {
            mc.player.setXRot(pitch);
        }
    }

    // Takes the water back once the landing is done with it.
    private void collect() {
        if (!pickUp.isOn() || !bucketMode()) {
            placedWater = null;
            slots.restore();
            return;
        }
        if (pickupTimer > 0 || !mc.player.onGround()) {
            return;
        }
        if (mc.level.getFluidState(placedWater).isEmpty()) {
            // Something else already took it.
            placedWater = null;
            slots.restore();
            return;
        }
        int bucket = InventoryUtil.hotbarSlot(stack -> stack.is(Items.BUCKET));
        if (bucket != -1) {
            slots.select(bucket);
            if (lookingDown(() ->
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND).consumesAction())) {
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        }
        placedWater = null;
        slots.restore();
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!spoofing || !(event.getPacket() instanceof ServerboundMovePlayerPacket packet)
            || packet.isOnGround()) {
            return;
        }
        // The packet thread can lose the player mid handler.
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        // Creative mode has no fall damage.
        if (player.getAbilities().invulnerable) {
            return;
        }
        if (player.isFallFlying() && !elytra.isOn()) {
            return;
        }
        event.setPacket(PacketUtil.withOnGround(packet, player, true));
    }
}
