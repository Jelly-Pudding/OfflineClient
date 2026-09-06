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
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.BooleanSupplier;

// The server counts your fall and hurts on the packet that claims ground.
// Packet mode climbs first and never fakes ground. Place mode softens the landing.
public final class NoFall extends Module {

    public enum Mode { PACKET, PLACE, AIR_PLACE, BOTH }
    public enum AirPlaceWhen { BEFORE_DAMAGE, BEFORE_DEATH }

    public enum PlacedItem {
        BUCKET(Items.WATER_BUCKET, Blocks.WATER),
        POWDER_SNOW(Items.POWDER_SNOW_BUCKET, Blocks.POWDER_SNOW),
        HAY_BALE(Items.HAY_BLOCK, Blocks.HAY_BLOCK),
        COBWEB(Items.COBWEB, Blocks.COBWEB),
        SLIME_BLOCK(Items.SLIME_BLOCK, Blocks.SLIME_BLOCK);

        private final Item item;
        private final Block block;

        PlacedItem(Item item, Block block) {
            this.item = item;
            this.block = block;
        }

        // Poured from a bucket and taken back with an empty one.
        boolean fromBucket() {
            return this == BUCKET || this == POWDER_SNOW;
        }
    }

    // A jump between dimensions moves the player further than any fall.
    private static final double TELEPORT_DROP = 64;

    // Blocks of fall the game forgives before it hurts.
    private static final float SAFE_FALL = 3;

    // Air place goes down before this much fall would hurt at all.
    private static final float DAMAGE_FALL = 2;

    // How far the wiping packet climbs. Any climb at all does it.
    private static final double CLIMB = 0.001;

    // A landing pauses this far above the ground for its wipe.
    private static final double LANDING_STEP = 0.02;

    // Ticks to leave the water alone before collecting it and the longest wait.
    private static final int PICKUP_DELAY = 2;
    private static final int PICKUP_TIMEOUT = 20;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the fall is stopped.", Mode.PACKET)
        .describe(Mode.PACKET, "Wipes the fall the server has counted with a tiny climb before it can hurt.")
        .describe(Mode.PLACE, "Drops water or another soft landing under you. Survives a strict server.")
        .describe(Mode.AIR_PLACE, "Puts any block from your hotbar under your feet just before the fall would hurt.")
        .describe(Mode.BOTH, "Wipes the count and drops the soft landing as well.");
    private final EnumSetting<PlacedItem> placedItem = new EnumSetting<>("Placed item",
        "What is dropped under you. A bucket turns into powder snow where water boils away.", PlacedItem.BUCKET)
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
        "Centres you on the block first so the landing goes under you and not beside you.", true)
        .under(mode, Mode.PLACE, Mode.AIR_PLACE, Mode.BOTH);
    private final NumberSetting minFall = new NumberSetting("Min fall",
        "Small drops are left alone until the fall passes this. Anything over three blocks will hurt.",
        2.5, 0, 10, 0.1, " blocks").min(0).max(10);
    private final BoolSetting whilstGliding = new BoolSetting("Whilst gliding",
        "Keep working whilst you fly with an elytra.", true);
    private final NumberSetting glideMinFall = new NumberSetting("Glide min fall",
        "The same minimum applied whilst you glide.", 2.5, 0, 10, 0.1, " blocks")
        .min(0).max(10)
        .under(whilstGliding);
    private final BoolSetting antiBounce = new BoolSetting("Anti bounce",
        "Landing on a slime block or a bed never bounces you.", true);
    private final BoolSetting pauseOnMace = new BoolSetting("Pause on mace",
        "Leaves the fall alone whilst you hold a mace. The smash still lands.", true);

    // The count the server holds. Every movement packet that goes out is mirrored here.
    // Unknown after a teleport until a climb has wiped it.
    private double bank;
    private volatile boolean bankKnown;

    // The last position that went out. The climb packet starts from it.
    private double sentX;
    private double sentY;
    private double sentZ;
    private volatile boolean sentKnown;

    // A module such as Blink is holding the movement packets back.
    private boolean heldBack;

    // The fall as the client sees it. Place modes read this.
    private double descent;
    private double lastY;
    private boolean tracking;

    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    // Where the soft landing went and what it was. Null once collected or given up.
    private BlockPos placedAt;
    private PlacedItem placedKind;
    private int pickupTimer;
    private int placedTicks;

    public NoFall() {
        super("NoFall", "Stops fall damage.", Category.MOVEMENT);
        addSettings(mode, placedItem, pickUp, airPlaceWhen, anchor, minFall, whilstGliding,
            glideMinFall, antiBounce, pauseOnMace);
        searchTags("fall damage", "water bucket", "clutch", "air place");
    }

    @Override
    public String getSuffix() {
        return placedKind == null ? null : EnumSetting.label(placedKind);
    }

    // Read by EntityMixin. Slime and beds check this before they bounce.
    public boolean suppressesBounce() {
        return isEnabled() && antiBounce.isOn();
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
        placedAt = null;
        placedKind = null;
        pickupTimer = 0;
        placedTicks = 0;
        loan.giveBack();
    }

    private boolean packetMode() {
        return mode.isAny(Mode.PACKET, Mode.BOTH);
    }

    private boolean placeMode() {
        return mode.isAny(Mode.PLACE, Mode.BOTH);
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
        if (counted <= wipeAt()) {
            return;
        }
        // A landing after a real drop pauses just above the ground.
        // Anything else is one climb.
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

    // The count is wiped and the fall carried on to just above the ground.
    // The real landing packet that follows only counts that last step.
    private void sendLanding(double x, double y, double z) {
        sendClimb();
        boolean collided = mc.player.horizontalCollision;
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            x, y + LANDING_STEP, z, false, collided));
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            x, y + LANDING_STEP + CLIMB, z, false, collided));
    }

    // How much fall the server may hold before a climb wipes it.
    private double wipeAt() {
        return mc.player.isFallFlying() ? glideMinFall.getValue() : minFall.getValue();
    }

    private boolean protecting() {
        LocalPlayer player = mc.player;
        if (player.getAbilities().invulnerable || player.isPassenger()) {
            return false;
        }
        if (player.isFallFlying() && !whilstGliding.isOn()) {
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

    // Mirrors what the server will hold after each movement packet.
    // Runs after every other rewrite so the packet seen here is the one sent.
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
        // A mode change mid clutch still has to clear up what is already down.
        if (placedAt != null) {
            collect();
            return;
        }
        if (mode.is(Mode.AIR_PLACE)) {
            airPlaceTick();
            return;
        }
        if (!placeMode()) {
            return;
        }
        if (!falling() || descent <= SAFE_FALL || groundBelow() == null) {
            loan.giveBack();
            return;
        }
        placeLanding();
    }

    // True whilst a fall is running that the module ought to be watching.
    private boolean falling() {
        LocalPlayer player = mc.player;
        if (player.getAbilities().invulnerable || player.onGround() || player.isInWater()
            || player.getDeltaMovement().y >= 0) {
            return false;
        }
        if (player.isFallFlying() && !whilstGliding.isOn()) {
            return false;
        }
        return !pauseOnMace.isOn() || !holdingMace();
    }

    // The ground straight below within reach or null.
    private BlockPos groundBelow() {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.subtract(0, mc.player.blockInteractionRange(), 0);
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end,
            ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    // The item chosen with the bucket swapped for powder snow where water boils away.
    private PlacedItem chosenItem() {
        if (placedItem.is(PlacedItem.BUCKET) && mc.level.environmentAttributes()
            .getValue(EnvironmentAttributes.WATER_EVAPORATES, mc.player.blockPosition())) {
            return PlacedItem.POWDER_SNOW;
        }
        return placedItem.getValue();
    }

    // A bucket raycasts from the live client rotation and the packet carries it.
    // Pointing down for the call is enough. A block is clicked onto the ground.
    private void placeLanding() {
        PlacedItem kind = chosenItem();
        int slot = InventoryUtil.findSlot(kind.item, InventoryUtil.WHOLE_INVENTORY);
        BlockPos ground = groundBelow();
        if (slot == -1 || ground == null || !loan.select(slot)) {
            return;
        }
        if (anchor.isOn()) {
            BlockUtil.centerPlayer();
        }
        BlockPos target = ground.above();
        boolean placed = kind.fromBucket()
            ? lookingDown(() -> useHeld())
            : BlockUtil.place(target, Direction.DOWN, true, true);
        if (!placed) {
            return;
        }
        if (kind.fromBucket()) {
            placedAt = target;
            placedKind = kind;
            pickupTimer = PICKUP_DELAY;
            placedTicks = 0;
        } else {
            loan.giveBack();
        }
    }

    private boolean useHeld() {
        if (!mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND).consumesAction()) {
            return false;
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
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

    // Any block from the hotbar goes under the feet once the fall is deep enough.
    // The fall is paused for the click so the block lands where the feet are.
    private void airPlaceTick() {
        if (!falling() || descent <= airPlaceThreshold()) {
            loan.giveBack();
            return;
        }
        BlockPos below = mc.player.blockPosition().below();
        int slot = BlockUtil.findBlockSlot();
        if (slot == -1 || !BlockUtil.isReplaceable(below) || !loan.select(slot)) {
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

    // Takes the water or snow back once the landing is done with it.
    private void collect() {
        if (!pickUp.isOn() || !placeMode() || ++placedTicks > PICKUP_TIMEOUT) {
            forgetPlaced();
            return;
        }
        if (pickupTimer > 0 || !landedOnPlaced()) {
            return;
        }
        if (!placedStillThere()) {
            // Something else already took it.
            forgetPlaced();
            return;
        }
        int bucket = InventoryUtil.findSlot(Items.BUCKET, InventoryUtil.WHOLE_INVENTORY);
        if (bucket != -1 && loan.select(bucket)) {
            lookingDown(this::useHeld);
        }
        forgetPlaced();
    }

    // Water is collected once the player is in it and snow once they have sunk to its floor.
    private boolean landedOnPlaced() {
        if (placedKind == PlacedItem.BUCKET) {
            return mc.player.isInWater();
        }
        return mc.player.onGround() && mc.player.fallDistance == 0;
    }

    private boolean placedStillThere() {
        if (placedKind == PlacedItem.BUCKET) {
            return !mc.level.getFluidState(placedAt).isEmpty();
        }
        return mc.level.getBlockState(placedAt).is(placedKind.block);
    }

    private void forgetPlaced() {
        placedAt = null;
        placedKind = null;
        loan.giveBack();
    }
}
