package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.client.player.ClientInput;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

// CameraMixin positions the camera and MouseHandlerMixin routes the mouse here.
public final class Freecam extends Module {

    private static final int TRACER_COLOR = 0xFF40E0FF;

    private final NumberSetting speed = new NumberSetting("Speed",
        "How fast the camera moves.", 1, 0.1, 5, 0.1);
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
    private boolean initialized;
    private float lastHealth;

    public Freecam() {
        super("Freecam", "Fly the camera around whilst your character stays still.", Category.RENDER);
        addSettings(speed, blockClicks, tracer, disableOnDamage, reloadChunks);
        searchTags("free camera", "spectator", "detach");
    }

    // Consulted by MinecraftMixin before the game handles an attack.
    public boolean blocksClicks() {
        return isEnabled() && blockClicks.isOn();
    }

    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (blocksClicks()) {
            event.cancel();
        }
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    protected void onEnable() {
        initialized = false;
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
        swapInput();
        initialized = true;
    }

    // Hands the player an input object that reads no keys.
    private void swapInput() {
        realInput = mc.player.input;
        dummyInput = new ClientInput();
        mc.player.input = dummyInput;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (!initialized) {
            init();
        }
        // A respawn or dimension change builds a fresh player with real keys.
        if (mc.player.input != dummyInput) {
            swapInput();
        }

        float healthNow = mc.player.getHealth();
        if (disableOnDamage.isOn() && healthNow < lastHealth) {
            lastHealth = healthNow;
            setEnabled(false);
            return;
        }
        lastHealth = healthNow;

        prevCamPos = camPos;
        if (mc.gui.screen() != null) {
            return;
        }

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
        double vertical = 0;
        if (mc.options.keyJump.isDown()) {
            vertical += move;
        }
        if (mc.options.keyShift.isDown()) {
            vertical -= move;
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
        if (mc.player != null && realInput != null && mc.player.input == dummyInput) {
            mc.player.input = realInput;
        }
        realInput = null;
        dummyInput = null;
        initialized = false;
        // The chunks around the body were meshed from the camera's side.
        if (reloadChunks.isOn() && mc.levelExtractor != null) {
            mc.levelExtractor.allChanged();
        }
    }

    // Mouse movement lands here instead of turning the player.
    public void turn(double deltaYaw, double deltaPitch) {
        // The 0.15 factor matches how the game turns the player.
        camYaw += (float) (deltaYaw * 0.15);
        camPitch = Mth.clamp(camPitch + (float) (deltaPitch * 0.15), -90f, 90f);
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
}
