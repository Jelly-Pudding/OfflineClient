package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.MouseScrollEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * CameraMixin positions the camera and MouseHandlerMixin routes the mouse
 * here. PickMixin asks for the crosshair ray whilst clicks come from the camera.
 */
public final class Freecam extends Module {

    private static final int TRACER_COLOR = 0xFF40E0FF;

    // One wheel notch changes the speed by this much.
    private static final double SCROLL_STEP = 0.1;

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
    private final BoolSetting scrollSpeed = new BoolSetting("Scroll to change speed",
        "The mouse wheel changes the speed instead of the hotbar slot.", true);
    private final BoolSetting hideHand = new BoolSetting("Hide hand",
        "Hides the held item whilst the camera is away.", true);
    private final BoolSetting blockClicks = new BoolSetting("Block clicks",
        "Clicks do nothing whilst the camera is away.", true);
    private final BoolSetting tracer = new BoolSetting("Tracer",
        "Draws a line back to your body.", true);
    private final BoolSetting disableOnDamage = new BoolSetting("Disable on damage",
        "Snaps back to your body when you take a hit.", true);
    private final BoolSetting reloadChunks = new BoolSetting("Reload chunks",
        "Redraws the world around your body when you come back.", false);

    private Vec3 camPos = Vec3.ZERO;
    private Vec3 prevCamPos = Vec3.ZERO;
    private float camYaw;
    private float camPitch;
    private ClientInput dummyInput;
    private ClientInput realInput;
    // The player the camera was seeded from. A respawn or a portal hands out a new one.
    private LocalPlayer seededFor;
    private float lastHealth;

    public Freecam() {
        super("Freecam", "Fly the camera around whilst your character stays still.", Category.RENDER);
        addSettings(speed, verticalSpeed, inputTarget, interactFrom, scrollSpeed, hideHand,
            blockClicks, tracer, disableOnDamage, reloadChunks);
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
        if (!movesCamera() || !scrollSpeed.isOn()) {
            return;
        }
        double change = event.getAmount() > 0 ? SCROLL_STEP : -SCROLL_STEP;
        speed.setValue(speed.getValue() + change);
        event.cancel();
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    protected void onEnable() {
        seededFor = null;
        if (mc.player != null) {
            init();
        }
    }

    private void init() {
        camPos = mc.player.getEyePosition();
        prevCamPos = camPos;
        camYaw = mc.player.getYRot();
        camPitch = mc.player.getXRot();
        lastHealth = mc.player.getHealth();
        seededFor = mc.player;
    }

    // Hands the player an input object that reads no keys.
    private void swapInput() {
        realInput = mc.player.input;
        dummyInput = new ClientInput();
        mc.player.input = dummyInput;
    }

    private void restoreInput() {
        if (mc.player != null && realInput != null && mc.player.input == dummyInput) {
            mc.player.input = realInput;
        }
        realInput = null;
        dummyInput = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (mc.player != seededFor) {
            init();
        }
        syncInput();

        float healthNow = mc.player.getHealth();
        if (disableOnDamage.isOn() && healthNow < lastHealth) {
            lastHealth = healthNow;
            setEnabled(false);
            return;
        }
        lastHealth = healthNow;

        prevCamPos = camPos;
        if (mc.gui.screen() != null || !movesCamera()) {
            return;
        }
        moveCamera();
    }

    /**
     * The body only loses its keys whilst they drive the camera. A respawn or
     * dimension change builds a fresh player with real keys and gets a new dummy.
     */
    private void syncInput() {
        if (!movesCamera()) {
            restoreInput();
            return;
        }
        if (mc.player.input != dummyInput) {
            swapInput();
        }
    }

    private void moveCamera() {
        double move = speed.getValue();
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
            TRACER_COLOR, true);
    }

    @Override
    protected void onDisable() {
        restoreInput();
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

    /**
     * The crosshair ray cast from the camera instead of the eyes. Blocks and
     * entities use their own reach and the nearer hit wins like vanilla.
     */
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
