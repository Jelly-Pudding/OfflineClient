package com.jellypudding.offlineclient.rig.core;

import com.jellypudding.offlineclient.gui.LatchedScale;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// The keyboard and the mouse of the test player. Every press goes through the game's own
// input handlers. The real keyboard and mouse of the machine are never read or moved.
public final class Controls {

    // Where the pointer waits whilst nothing should be hovered.
    private static final double PARKED = -100;
    private static final double FACE_INSET = 0.5;

    private final Rig rig;
    private final TestInput input;
    private final Set<InputConstants.Key> held = new LinkedHashSet<>();
    // The keyboard keys held right now. The game asks from any thread.
    private static final Set<Integer> HELD_CODES = ConcurrentHashMap.newKeySet();

    Controls(Rig rig) {
        this.rig = rig;
        this.input = rig.context().getInput();
    }

    // One press and release of a key. The game hears it within the next tick.
    public void press(int key) {
        input.pressKey(key);
    }

    public void hold(int key) {
        InputConstants.Key down = InputConstants.Type.KEYBOARD.getOrCreate(key);
        held.add(down);
        HELD_CODES.add(key);
        input.holdKey(down);
    }

    public void release(int key) {
        InputConstants.Key up = InputConstants.Type.KEYBOARD.getOrCreate(key);
        held.remove(up);
        HELD_CODES.remove(key);
        input.releaseKey(up);
    }

    // True whilst the rig holds that key of the keyboard down. Read by the rig's own mixin where
    // the game asks the keyboard itself.
    public static boolean holds(int key) {
        return HELD_CODES.contains(key);
    }

    // One click of a mouse button of the game's own numbering.
    public void click(int button) {
        input.pressMouse(button);
    }

    public void holdMouse(int button) {
        InputConstants.Key down = InputConstants.Type.MOUSE.getOrCreate(button);
        held.add(down);
        input.holdKey(down);
    }

    public void releaseMouse(int button) {
        InputConstants.Key up = InputConstants.Type.MOUSE.getOrCreate(button);
        held.remove(up);
        input.releaseKey(up);
    }

    // Lets go of everything a case left down.
    void letGo() {
        for (InputConstants.Key key : Set.copyOf(held)) {
            input.releaseKey(key);
        }
        held.clear();
        HELD_CODES.clear();
    }

    public void type(String text) {
        input.typeChars(text);
    }

    public void scroll(double amount) {
        input.scroll(amount);
    }

    public void look(float yaw, float pitch) {
        input.lookAt(yaw, pitch);
        rig.ticks(1);
    }

    // Turns the player to face a point and gives the game one tick to see what is there.
    public void aimAt(Vec3 point) {
        rig.act(mc -> {
            Vec3 eyes = mc.player.getEyePosition();
            double dx = point.x - eyes.x;
            double dy = point.y - eyes.y;
            double dz = point.z - eyes.z;
            float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90;
            float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
        });
        rig.ticks(1);
    }

    // Aims at the middle of one face of a block and fails unless the crosshair lands on it.
    public void aimAtBlock(BlockPos pos, Direction face) {
        Vec3 point = Vec3.atCenterOf(pos).add(face.getUnitVec3().scale(FACE_INSET));
        aimAt(point);
        HitResult hit = rig.ask(mc -> mc.hitResult);
        if (!(hit instanceof BlockHitResult block) || block.getType() == HitResult.Type.MISS
            || !block.getBlockPos().equals(pos) || block.getDirection() != face) {
            throw new RigFailure("The crosshair is not on the " + face + " face of the block at " + pos.toShortString()
                + " but on " + told(hit));
        }
    }

    public void aimAtEntity(Entity entity) {
        aimAt(entity.getBoundingBox().getCenter());
        HitResult hit = rig.ask(mc -> mc.hitResult);
        if (!(hit instanceof EntityHitResult found) || found.getEntity().getId() != entity.getId()) {
            throw new RigFailure("The crosshair is not on " + entity.getName().getString() + " but on " + told(hit));
        }
    }

    private static String told(HitResult hit) {
        if (hit instanceof BlockHitResult block && block.getType() != HitResult.Type.MISS) {
            return "the " + block.getDirection() + " face of the block at " + block.getBlockPos().toShortString();
        }
        if (hit instanceof EntityHitResult entity) {
            return entity.getEntity().getName().getString();
        }
        return "nothing";
    }

    // Moves the pointer to a place given in the pixels a vanilla screen is laid out in.
    public void pointer(double guiX, double guiY) {
        double[] spot = rig.ask(mc -> {
            Window window = mc.getWindow();
            return new double[] {guiX * window.getScreenWidth() / window.getGuiScaledWidth(),
                guiY * window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        input.setCursorPos(spot[0], spot[1]);
    }

    // The same for a place in the pixels the client's own screens are laid out in.
    public void pointerInView(double viewX, double viewY) {
        float scale = rig.ask(mc -> LatchedScale.guiScale());
        pointer(viewX * scale, viewY * scale);
    }

    // Takes the pointer off the screen. Nothing is hovered in a picture taken afterwards.
    public void parkPointer() {
        input.setCursorPos(PARKED, PARKED);
    }

    public void clickInView(double viewX, double viewY, int button) {
        pointerInView(viewX, viewY);
        click(button);
    }
}
