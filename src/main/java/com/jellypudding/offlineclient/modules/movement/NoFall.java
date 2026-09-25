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
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.Lagback;
import com.jellypudding.offlineclient.util.PacketUtil;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

// The server charges for the fall it holds on the packet that claims ground.
// Packet mode claims ground every tick of a fall and is charged for one tick at
// a time. Place mode softens the landing instead.
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

    // A held fall of one block past the safe distance hurts. The claim keeps clear of it.
    private static final double HURTING_FALL = SAFE_FALL + 0.9;

    // Air place goes down before this much fall would hurt at all.
    private static final float DAMAGE_FALL = 2;

    // Ticks to leave the water alone before collecting it and the longest wait.
    private static final int PICKUP_DELAY = 2;
    private static final int PICKUP_TIMEOUT = 20;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the fall is stopped.", Mode.PACKET)
        .describe(Mode.PACKET, "Tells the server you have landed on every packet whilst you fall.")
        .describe(Mode.PLACE, "Drops water or another soft landing under you. Survives a strict server.")
        .describe(Mode.AIR_PLACE, "Puts any block from your hotbar under your feet just before the fall would hurt.")
        .describe(Mode.BOTH, "Claims the landing and drops the soft landing as well.");
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
        "Centres you first to put the landing block right under you.", true)
        .under(mode, Mode.PLACE, Mode.AIR_PLACE, Mode.BOTH);
    private final BoolSetting limitSpeed = new BoolSetting("Limit fall speed",
        "Keeps you under four blocks a tick. Anything faster hurts even with NoFall.", true)
        .under(mode, Mode.PACKET, Mode.BOTH);
    private final NumberSetting minFall = new NumberSetting("Min fall",
        "Short drops are left alone until the fall passes this. Three blocks is the most"
            + " that is safe because a longer one already hurts.",
        2.5, 0, 3, 0.1, " blocks").min(0).max(3);
    private final BoolSetting whilstGliding = new BoolSetting("Whilst gliding",
        "Keep the place modes working whilst you fly with an elytra.", true);
    private final BoolSetting antiBounce = new BoolSetting("Anti bounce",
        "Landing on a slime block or a bed never bounces you.", true);
    private final BoolSetting pauseOnMace = new BoolSetting("Pause on mace",
        "Leaves the fall alone whilst you hold a mace. The smash still lands.", true);

    // The fall as the client sees it. Place modes read this.
    private volatile double descent;
    private double lastY;
    private boolean tracking;

    private final Lagback.Watcher lagback = new Lagback.Watcher();

    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    // Where the soft landing went and what it was. Null once collected or given up.
    private BlockPos placedAt;
    private PlacedItem placedKind;
    private int pickupTimer;
    private int placedTicks;

    public NoFall() {
        super("NoFall", "Stops fall damage.", Category.MOVEMENT);
        addSettings(mode, limitSpeed, placedItem, pickUp, airPlaceWhen, anchor, minFall, whilstGliding,
            antiBounce, pauseOnMace);
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
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        lagback.sync();
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
    }

    private boolean protecting() {
        // The packet thread can lose the player mid handler.
        LocalPlayer player = mc.player;
        if (player == null || player.getAbilities().invulnerable || player.isPassenger()) {
            return false;
        }
        if (player.isFallFlying() && !whilstGliding.isOn()) {
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
        // Claiming ground whilst gliding tells the server the flight has ended.
        if (player.isFallFlying()) {
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
        if (!inGame() || !packetMode() || !limitSpeed.isOn() || !protecting()
            || mc.player.isFallFlying()) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        if (velocity.y < -HURTING_FALL) {
            mc.player.setDeltaMovement(velocity.x, -HURTING_FALL, velocity.z);
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
        BlockPos ground = falling() && descent > SAFE_FALL ? groundBelow() : null;
        if (ground == null) {
            loan.giveBack();
            return;
        }
        placeLanding(ground);
    }

    // True whilst a fall is running that the module ought to be watching.
    private boolean falling() {
        return protecting() && !mc.player.onGround() && !mc.player.isInWater()
            && mc.player.getDeltaMovement().y < 0;
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
    private void placeLanding(BlockPos ground) {
        PlacedItem kind = chosenItem();
        int slot = InventoryUtil.findSlot(kind.item, InventoryUtil.WHOLE_INVENTORY);
        if (slot == -1 || !loan.select(slot)) {
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
        SwingMode.swingArm(InteractionHand.MAIN_HAND);
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
    // The fall is paused for the click. The block lands where the feet are.
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
