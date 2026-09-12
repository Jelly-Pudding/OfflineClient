package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.LeftClickEvent;
import com.jellypudding.offlineclient.event.events.MouseScrollEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.path.PathWalker;
import com.jellypudding.offlineclient.path.Trip;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import net.minecraft.client.CameraType;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// CameraMixin positions the camera and MouseHandlerMixin routes the mouse here.
// PickMixin aims the crosshair and VisGraphMixin keeps chunk insides drawn.
public final class Freecam extends Module {

    public enum Start { INSIDE, IN_FRONT, ABOVE }

    // How far a start other than Inside sits from your eyes.
    private static final double START_STEP = 3;

    // Share of the speed one wheel notch changes at sensitivity one.
    private static final double NOTCH_SHARE = 0.25;
    private static final double MIN_SPEED = 0.1;

    // Two clicks this close together count as a double click.
    private static final long DOUBLE_CLICK_MS = 500;

    public enum InputTarget { CAMERA, PLAYER }

    public enum InteractFrom { PLAYER, CAMERA }

    private final NumberSetting speed = new NumberSetting("Horizontal speed",
        "How fast the camera moves along.", 1, 0.1, 5, 0.1);
    private final NumberSetting verticalSpeed = new NumberSetting("Vertical speed",
        "How fast the camera climbs and dives as a share of the speed.", 1, 0.1, 5, 0.1, "x");
    private final EnumSetting<InputTarget> inputTarget = new EnumSetting<>("Apply input to",
        "Who the keys and the mouse steer.", InputTarget.CAMERA)
        .describe(InputTarget.CAMERA, "The camera moves and your body stands still.")
        .describe(InputTarget.PLAYER, "Your body keeps moving whilst the camera hangs where you left it.");
    private final EnumSetting<InteractFrom> interactFrom = new EnumSetting<>("Interact from",
        "Where clicks are aimed from.", InteractFrom.PLAYER)
        .describe(InteractFrom.PLAYER, "Clicks go where your body looks.")
        .describe(InteractFrom.CAMERA, "Clicks go where the camera looks. The server still checks reach from your body.");
    private final NumberSetting scrollSensitivity = new NumberSetting("Scroll sensitivity",
        "How much of the speed one wheel notch adds or takes away. Nought leaves the wheel to the hotbar.",
        0.5, 0, 2, 0.1, "x").min(0).max(5);
    private final BoolSetting hideHand = new BoolSetting("Hide hand",
        "Hides the held item whilst the camera is away.", true);
    private final BoolSetting blockClicks = new BoolSetting("Block clicks",
        "Clicks do nothing whilst the camera is away.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns your body to face whatever the camera crosshair rests on.", false);
    private final BoolSetting staySneaking = new BoolSetting("Stay sneaking",
        "If you were sneaking when the camera left your body keeps sneaking.", true);
    private final BoolSetting stillView = new BoolSetting("Still view",
        "Turns off view bobbing and speed field of view changes whilst the camera is away.", true);
    private final EnumSetting<Start> start = new EnumSetting<>("Start at",
        "Where the camera begins when you switch it on.", Start.INSIDE)
        .describe(Start.INSIDE, "Right where your eyes are.")
        .describe(Start.IN_FRONT, "A few blocks in front of you.")
        .describe(Start.ABOVE, "A few blocks over your head.");
    private final BoolSetting tracer = new BoolSetting("Tracer",
        "Draws a line back to your body.", true);
    private final ColorSetting tracerColor = new ColorSetting("Tracer colour",
        "Colour of the line back to your body.", 193, 0.75f, 1f, false).under(tracer);
    private final BoolSetting disableOnDamage = new BoolSetting("Disable on damage",
        "Snaps back to your body when you take a hit.", true);
    private final BoolSetting disableOnDeath = new BoolSetting("Disable on death",
        "Snaps back to your body when you die.", false);
    private final BoolSetting disableOnLeave = new BoolSetting("Disable on leave",
        "Turns off when you leave the server.", true);
    private final BoolSetting clickToWalk = new BoolSetting("Click to walk",
        "A left click walks your body to the block the camera looks at. Any movement key stops it.",
        false);
    private final BoolSetting doubleClick = new BoolSetting("Double click",
        "Needs two clicks within half a second.", false).under(clickToWalk);
    private final BoolSetting reloadChunks = new BoolSetting("Reload chunks",
        "Redraws the world when the camera leaves and when it comes back so walls stop hiding rooms.", false);

    private Vec3 camPos = Vec3.ZERO;
    private Vec3 prevCamPos = Vec3.ZERO;
    private float camYaw;
    private float camPitch;
    private final Trip trip = new Trip();
    private long lastClick;

    private ClientInput dummyInput;
    private ClientInput realInput;
    // The player the camera was seeded from. A respawn or a portal hands out a new one.
    private LocalPlayer seededFor;
    private float lastHealth;
    private boolean sneakingAtStart;
    private CameraType savedView;
    private double savedFovScale;
    private boolean savedBobbing;
    private boolean viewChanged;
    // Set on the netty thread and acted on next tick.
    private volatile String stopReason;

    public Freecam() {
        super("Freecam", "Fly the camera around whilst your character stays still.", Category.RENDER);
        addSettings(start, speed, verticalSpeed, inputTarget, interactFrom, scrollSensitivity, hideHand,
            blockClicks, rotate, staySneaking, stillView, tracer, tracerColor, disableOnDamage, disableOnDeath,
            disableOnLeave, reloadChunks);
        searchTags("free camera", "spectator", "detach");
    }

    @Override
    public String getSuffix() {
        return speed.getValueString();
    }

    // Consulted by MinecraftMixin before the game handles an attack.
    public boolean blocksClicks() {
        return isEnabled() && blockClicks.isOn();
    }

    // True whilst the keys and the mouse drive the camera rather than the body.
    public boolean movesCamera() {
        return isEnabled() && inputTarget.is(InputTarget.CAMERA);
    }

    public boolean interactsFromCamera() {
        return isEnabled() && interactFrom.is(InteractFrom.CAMERA);
    }

    public boolean hidesHand() {
        return isEnabled() && hideHand.isOn();
    }

    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (blocksClicks()) {
            event.cancel();
        }
    }

    @Subscribe
    private void onScroll(MouseScrollEvent event) {
        if (!movesCamera() || scrollSensitivity.getValue() <= 0 || mc.gui.screen() != null) {
            return;
        }
        double now = speed.getValue();
        speed.setValue(Math.max(MIN_SPEED, now + event.getAmount() * NOTCH_SHARE * scrollSensitivity.getValue() * now));
        event.cancel();
    }

    // Death and dimension changes arrive on the netty thread.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundPlayerCombatKillPacket packet) {
            if (disableOnDeath.isOn() && mc.player != null && packet.playerId() == mc.player.getId()) {
                stopReason = "you died";
            }
        } else if (event.getPacket() instanceof ClientboundRespawnPacket) {
            stopReason = "you changed dimension";
        }
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    protected void onEnable() {
        seededFor = null;
        stopReason = null;
        viewChanged = false;
        if (mc.player != null) {
            init();
        }
        if (reloadChunks.isOn() && mc.levelExtractor != null) {
            mc.levelExtractor.allChanged();
        }
    }

    private void init() {
        camPos = startPos();
        prevCamPos = camPos;
        camYaw = mc.player.getYRot();
        camPitch = mc.player.getXRot();
        lastHealth = mc.player.getHealth();
        sneakingAtStart = mc.player.isShiftKeyDown();
        seededFor = mc.player;
        takeView();
    }

    // Where the camera is placed the moment it is switched on.
    private Vec3 startPos() {
        Vec3 eye = mc.player.getEyePosition();
        return switch (start.getValue()) {
            case INSIDE -> eye;
            case IN_FRONT -> eye.add(mc.player.getViewVector(1).scale(START_STEP));
            case ABOVE -> eye.add(0, START_STEP, 0);
        };
    }

    // The camera always looks out from first person and the view stays still.
    private void takeView() {
        if (viewChanged) {
            return;
        }
        viewChanged = true;
        savedView = mc.options.getCameraType();
        savedFovScale = mc.options.fovEffectScale().get();
        savedBobbing = mc.options.bobView().get();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        if (stillView.isOn()) {
            mc.options.fovEffectScale().set(0.0);
            mc.options.bobView().set(false);
        }
    }

    private void giveBackView() {
        if (!viewChanged) {
            return;
        }
        viewChanged = false;
        mc.options.setCameraType(savedView);
        if (stillView.isOn()) {
            mc.options.fovEffectScale().set(savedFovScale);
            mc.options.bobView().set(savedBobbing);
        }
    }

    // Hands the player an input object that reads no keys. Only the sneak
    // held when the camera left stays down.
    private void swapInput() {
        realInput = mc.player.input;
        dummyInput = new ClientInput();
        if (staySneaking.isOn() && sneakingAtStart) {
            dummyInput.keyPresses = new Input(false, false, false, false, false, true, false);
        }
        mc.player.input = dummyInput;
    }

    private void restoreInput() {
        if (mc.player != null && realInput != null && mc.player.input == dummyInput) {
            mc.player.input = realInput;
        }
        realInput = null;
        dummyInput = null;
    }

    // Fires even without a world. Leaving the server can be seen.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (disableOnLeave.isOn() && mc.level == null && seededFor != null) {
            setEnabled(false);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (mc.player != seededFor) {
            init();
        }
        String reason = stopReason;
        if (reason != null) {
            stopReason = null;
            ChatUtil.message("Freecam turned off because " + reason + ".");
            setEnabled(false);
            return;
        }
        syncInput();

        float healthNow = mc.player.getHealth();
        if (disableOnDamage.isOn() && healthNow < lastHealth) {
            lastHealth = healthNow;
            setEnabled(false);
            return;
        }
        lastHealth = healthNow;
        if (rotate.isOn()) {
            faceCrosshair();
        }

        prevCamPos = camPos;
        if (walkTick() || mc.gui.screen() != null || !movesCamera()) {
            return;
        }
        moveCamera();
    }

    // True whilst the body is walking to a clicked spot and the camera must stay put.
    private boolean walkTick() {
        if (!trip.active()) {
            return false;
        }
        if (InputUtil.physicallyHeld(mc.options.keyUp) || InputUtil.physicallyHeld(mc.options.keyDown)
            || InputUtil.physicallyHeld(mc.options.keyLeft) || InputUtil.physicallyHeld(mc.options.keyRight)) {
            trip.stop();
            return false;
        }
        trip.tick();
        return true;
    }

    @Subscribe
    private void onLeftClick(LeftClickEvent event) {
        if (!clickToWalk.isOn() || !inGame() || mc.gui.screen() != null) {
            return;
        }
        event.cancel();
        long now = System.currentTimeMillis();
        boolean second = now - lastClick <= DOUBLE_CLICK_MS;
        lastClick = now;
        if (doubleClick.isOn() && !second) {
            return;
        }
        HitResult hit = pick(mc.player, 1);
        BlockPos target;
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            target = block.getBlockPos().relative(block.getDirection());
        } else if (hit instanceof EntityHitResult entity) {
            target = entity.getEntity().blockPosition();
        } else {
            return;
        }
        trip.walker().turn(PathWalker.Turn.NONE);
        if (!trip.start(target, 1)) {
            ChatUtil.error("Could not start walking there.");
        }
    }

    // The body only loses its keys whilst they drive the camera. A respawn or
    // dimension change builds a fresh player with real keys and gets a new dummy.
    // A walk hands the keys back to the body for as long as it lasts.
    private void syncInput() {
        if (!movesCamera() || trip.active()) {
            restoreInput();
            return;
        }
        if (mc.player.input != dummyInput) {
            swapInput();
        }
    }

    // Turns the body towards whatever the camera crosshair rests on.
    private void faceCrosshair() {
        HitResult hit = mc.hitResult;
        if (hit instanceof EntityHitResult entityHit) {
            RotationManager.look(entityHit.getEntity().getEyePosition(), RotationPriority.IDLE,
                RotationManager.ENTITY_TOLERANCE);
        } else if (hit instanceof BlockHitResult blockHit && hit.getType() != HitResult.Type.MISS
            && !mc.level.getBlockState(blockHit.getBlockPos()).isAir()) {
            RotationManager.look(hit.getLocation(), RotationPriority.IDLE, RotationManager.BLOCK_TOLERANCE);
        }
    }

    private void moveCamera() {
        // Holding sprint doubles the pace.
        double move = speed.getValue() * (mc.options.keySprint.isDown() ? 1 : 0.5);
        double yaw = Math.toRadians(camYaw);
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));

        Vec3 delta = Vec3.ZERO;
        if (mc.options.keyUp.isDown()) {
            delta = delta.add(forward);
        }
        if (mc.options.keyDown.isDown()) {
            delta = delta.subtract(forward);
        }
        if (mc.options.keyRight.isDown()) {
            delta = delta.add(right);
        }
        if (mc.options.keyLeft.isDown()) {
            delta = delta.subtract(right);
        }
        if (delta.lengthSqr() > 0) {
            delta = delta.normalize().scale(move);
        }
        double climb = move * verticalSpeed.getValue();
        double vertical = 0;
        if (mc.options.keyJump.isDown()) {
            vertical += climb;
        }
        if (mc.options.keyShift.isDown()) {
            vertical -= climb;
        }
        camPos = camPos.add(delta.x, vertical, delta.z);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!tracer.isOn() || !inGame()) {
            return;
        }
        event.getBatch().tracer(
            EntityUtil.lerpedBox(mc.player, event.getPartialTicks()).getCenter(),
            tracerColor.getColor(), true);
    }

    @Override
    protected void onDisable() {
        trip.stop();
        restoreInput();
        giveBackView();
        seededFor = null;
        // The chunks around the body were meshed from the camera's side.
        if (reloadChunks.isOn() && mc.levelExtractor != null) {
            mc.levelExtractor.allChanged();
        }
    }

    // Mouse movement lands here instead of turning the player.
    public void turn(double deltaYaw, double deltaPitch) {
        camYaw += (float) (deltaYaw * InputUtil.MOUSE_TURN);
        camPitch = Mth.clamp(camPitch + (float) (deltaPitch * InputUtil.MOUSE_TURN), -90f, 90f);
    }

    public Vec3 getCamPos(float partialTicks) {
        return Mth.lerp(partialTicks, prevCamPos, camPos);
    }

    public float getCamYaw() {
        return camYaw;
    }

    public float getCamPitch() {
        return camPitch;
    }

    // The crosshair ray cast from the camera instead of the eyes. Blocks and
    // entities use their own reach and the nearer hit wins like vanilla.
    public HitResult pick(LocalPlayer player, float partialTicks) {
        Vec3 start = getCamPos(partialTicks);
        Vec3 direction = Vec3.directionFromRotation(camPitch, camYaw);

        double blockRange = player.blockInteractionRange();
        HitResult result = mc.level.clip(new ClipContext(start, start.add(direction.scale(blockRange)),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));

        double entityRange = player.entityInteractionRange();
        Vec3 entityEnd = start.add(direction.scale(entityRange));
        AABB bounds = new AABB(start, entityEnd).inflate(1);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(player, start, entityEnd, bounds,
            entity -> !entity.isSpectator() && entity.isPickable(), entityRange * entityRange);
        if (entityHit == null) {
            return result;
        }
        boolean nearer = result.getType() == HitResult.Type.MISS
            || entityHit.getLocation().distanceToSqr(start) < result.getLocation().distanceToSqr(start);
        return nearer ? entityHit : result;
    }
}
