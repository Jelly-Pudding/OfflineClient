package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.BooleanSupplier;

/**
 * The server keeps a count of how far the player has dropped and hurts them
 * for it on the first packet that says ground. It wipes that count on any
 * packet that moves the player up. Packet mode sends a tiny climb ahead of
 * every packet that would otherwise land a hurtful count and never claims
 * ground it does not have. That keeps glides open and works at any speed.
 * Bucket mode breaks the fall with real water instead.
 */
public final class NoFall extends Module {

    public enum Mode { PACKET, WATER_BUCKET, BOTH }

    // A jump between dimensions moves the player further than any fall.
    private static final double TELEPORT_DROP = 64;

    // Blocks of fall the game forgives before it hurts.
    private static final float SAFE_FALL = 3;

    // A count this high is wiped before the next packet. Well under the line that hurts.
    private static final double WIPE_AT = 2.5;

    // How far the wiping packet climbs. Any climb at all does it.
    private static final double CLIMB = 0.001;

    // A landing pauses this far above the ground for its wipe.
    private static final double LANDING_STEP = 0.02;

    // Ticks to leave the water alone before collecting it.
    private static final int PICKUP_DELAY = 2;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the fall is stopped.", Mode.PACKET)
        .describe(Mode.PACKET, "Wipes the fall the server has counted with a tiny climb before it can hurt.")
        .describe(Mode.WATER_BUCKET, "Drops real water under you. Survives a strict server.")
        .describe(Mode.BOTH, "Wipes the count and clutches a bucket as well.");
    private final BoolSetting pickUp = new BoolSetting("Collect water",
        "Picks the water back up once it has broken your fall.", true)
        .under(mode, Mode.WATER_BUCKET, Mode.BOTH);
    private final BoolSetting pauseOnMace = new BoolSetting("Pause on mace",
        "Leaves the fall alone whilst you hold a mace so the smash still lands.", true);

    /**
     * The count the server holds. Every movement packet that goes out is
     * mirrored here. Unknown after a teleport until a climb has wiped it.
     */
    private double bank;
    private volatile boolean bankKnown;

    // The last position that went out. The climb packet starts from it.
    private double sentX;
    private double sentY;
    private double sentZ;
    private volatile boolean sentKnown;

    // A module such as Blink is holding the movement packets back.
    private boolean heldBack;

    // The fall as the client sees it. Bucket mode reads this.
    private double descent;
    private double lastY;
    private boolean tracking;

    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private BlockPos placedWater;
    private int pickupTimer;

    public NoFall() {
        super("NoFall", "Stops fall damage.", Category.MOVEMENT);
        addSettings(mode, pickUp, pauseOnMace);
        searchTags("fall damage", "water bucket", "clutch");
    }

    @Override
    public String getSuffix() {
        return placedWater != null ? "water" : null;
    }

    @Override
    protected void onEnable() {
        reset();
        if (inGame()) {
            // The server may already hold a count. The first climb clears it.
            sentX = mc.player.getX();
            sentY = mc.player.getY();
            sentZ = mc.player.getZ();
            sentKnown = true;
        }
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        bank = 0;
        bankKnown = false;
        sentKnown = false;
        heldBack = false;
        descent = 0;
        tracking = false;
        placedWater = null;
        pickupTimer = 0;
        loan.giveBack();
    }

    private boolean packetMode() {
        return mode.isAny(Mode.PACKET, Mode.BOTH);
    }

    private boolean bucketMode() {
        return mode.isAny(Mode.WATER_BUCKET, Mode.BOTH);
    }

    // Runs once per tick just before the movement packet is built.
    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (!inGame()) {
            reset();
            return;
        }
        trackDescent();
        if (!packetMode() || !protecting()) {
            return;
        }
        if (!sentKnown || heldBack || mc.player.isInWater()) {
            return;
        }
        double y = mc.player.getY();
        double drop = sentY - y;
        // A climb wipes the count on its own.
        if (drop < 0) {
            return;
        }
        double counted = bankKnown ? bank + drop : Double.MAX_VALUE;
        if (counted <= WIPE_AT) {
            return;
        }
        // A landing after a real drop pauses just above the ground. Anything else is one climb.
        if (landingThisTick() && drop > LANDING_STEP) {
            sendLanding(mc.player.getX(), y, mc.player.getZ());
        } else {
            sendClimb();
        }
    }

    // The packet about to go out will carry a ground flag.
    private boolean landingThisTick() {
        if (mc.player.onGround()) {
            return true;
        }
        // FastBreak claims ground whilst a block is being mined.
        return mc.gameMode.isDestroying();
    }

    // A tiny climb from the last sent spot. The server wipes its count on it.
    private void sendClimb() {
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            sentX, sentY + CLIMB, sentZ, false, mc.player.horizontalCollision));
    }

    /**
     * The count is wiped and the fall carried on to just above the ground
     * where it is wiped once more. The real landing packet that follows only
     * counts that last step.
     */
    private void sendLanding(double x, double y, double z) {
        sendClimb();
        boolean collided = mc.player.horizontalCollision;
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            x, y + LANDING_STEP, z, false, collided));
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            x, y + LANDING_STEP + CLIMB, z, false, collided));
    }

    private boolean protecting() {
        LocalPlayer player = mc.player;
        if (player.getAbilities().invulnerable || player.isPassenger()) {
            return false;
        }
        return !pauseOnMace.isOn() || !holdingMace();
    }

    private void trackDescent() {
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
        return mc.player.getMainHandItem().is(Items.MACE)
            || mc.player.getOffhandItem().is(Items.MACE);
    }

    /**
     * Mirrors what the server will hold after each movement packet. Runs after
     * every other rewrite so the packet seen here is the one that goes out.
     */
    @Subscribe(priority = -100)
    private void onPacketSend(PacketSendEvent event) {
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket packet)) {
            return;
        }
        if (event.isCancelled()) {
            heldBack = true;
            return;
        }
        heldBack = false;
        // The packet thread can lose the player mid handler.
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (packet.hasPosition()) {
            double y = packet.getY(player.getY());
            if (sentKnown && y > sentY) {
                bank = 0;
                bankKnown = true;
            } else if (sentKnown && bankKnown) {
                bank += sentY - y;
            }
            sentX = packet.getX(player.getX());
            sentY = y;
            sentZ = packet.getZ(player.getZ());
            sentKnown = true;
        }
        // Water and a ground flag both wipe the count on the server.
        if (packet.isOnGround() || player.isInWater()) {
            bank = 0;
            bankKnown = true;
        }
    }

    // Fired on the netty thread. The server moved the player and the count with it.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            bankKnown = false;
            sentKnown = false;
        }
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
            loan.giveBack();
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
        return descent > SAFE_FALL && groundBelow() != null;
    }

    // The ground straight below within reach or null.
    private BlockPos groundBelow() {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.subtract(0, mc.player.blockInteractionRange(), 0);
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end,
            ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    // The bucket raycasts from the live client rotation and the packet carries it.
    // Pointing down for the call itself is enough. A bucket anywhere in the inventory is borrowed.
    private void placeWater() {
        int slot = findItem(Items.WATER_BUCKET);
        BlockPos ground = groundBelow();
        if (slot == -1 || ground == null || !loan.select(slot)) {
            return;
        }
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
            loan.giveBack();
            return;
        }
        if (pickupTimer > 0 || !mc.player.onGround()) {
            return;
        }
        if (mc.level.getFluidState(placedWater).isEmpty()) {
            // Something else already took it.
            placedWater = null;
            loan.giveBack();
            return;
        }
        int bucket = findItem(Items.BUCKET);
        if (bucket != -1 && loan.select(bucket)) {
            if (lookingDown(() ->
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND).consumesAction())) {
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        }
        placedWater = null;
        loan.giveBack();
    }

    // The first slot anywhere in the inventory holding the item or minus one.
    private int findItem(Item item) {
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }
}
