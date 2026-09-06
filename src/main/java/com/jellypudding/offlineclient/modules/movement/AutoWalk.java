package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.player.InvWalk;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class AutoWalk extends Module {

    public enum Direction { FORWARDS, BACKWARDS, LEFT, RIGHT }

    private final EnumSetting<Direction> direction = new EnumSetting<>("Direction",
        "Which movement key is held down.", Direction.FORWARDS);
    private final BoolSetting autoSprint = new BoolSetting("Auto sprint",
        "Sprint instead of walking.", false);
    private final BoolSetting stopOnInput = new BoolSetting("Stop on input",
        "Switches off as soon as you press a movement key yourself.", false);
    private final BoolSetting stopOnHeightChange = new BoolSetting("Stop on height change",
        "Switches off as soon as you move up or down.", false);
    private final BoolSetting stayInLoadedChunks = new BoolSetting("Stay in loaded chunks",
        "Stops walking whilst the chunk ahead has not loaded yet.", true);

    // Whether each movement key was physically down last tick.
    private final boolean[] wasHeld = new boolean[6];
    private KeyMapping held;

    public AutoWalk() {
        super("AutoWalk", "Holds a movement key for you.", Category.MOVEMENT);
        addSettings(direction, autoSprint, stopOnInput, stopOnHeightChange, stayInLoadedChunks);
    }

    @Override
    public String getSuffix() {
        return direction.getValueString();
    }

    @Override
    protected void onEnable() {
        if (!inGame()) {
            return;
        }
        List<KeyMapping> keys = movementKeys();
        for (int i = 0; i < keys.size(); i++) {
            wasHeld[i] = InputUtil.physicallyHeld(keys.get(i));
        }
    }

    // The key state only changes again on a real key event.
    @Override
    protected void onDisable() {
        letGo();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (stopOnHeightChange.isOn() && mc.player.yo != mc.player.getY()) {
            setEnabled(false);
            return;
        }
        boolean pressed = pressedMovementKey();
        if (stopOnInput.isOn() && pressed) {
            setEnabled(false);
            return;
        }
        KeyMapping wanted = keyFor(direction.getValue());
        if (held != wanted) {
            letGo();
        }
        if (stayInLoadedChunks.isOn() && chunkAheadMissing()) {
            letGo();
            Vec3 motion = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(0, motion.y, 0);
            return;
        }
        held = wanted;
        held.setDown(true);

        if (autoSprint.isOn()) {
            return;
        }
        // A hand started sprint ends on key release.
        if (mc.player.isSprinting() && !InputUtil.physicallyHeld(mc.options.keyUp)
            && !Modules.enabled(Sprint.class)) {
            mc.player.setSprinting(false);
        }
    }

    // Sprint is set late in the tick. The player tick clears sprint set any earlier.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!inGame() || !autoSprint.isOn()) {
            return;
        }
        if (!mc.player.isUsingItem()) {
            mc.player.setSprinting(true);
        }
    }

    private KeyMapping keyFor(Direction direction) {
        return switch (direction) {
            case FORWARDS -> mc.options.keyUp;
            case BACKWARDS -> mc.options.keyDown;
            case LEFT -> mc.options.keyLeft;
            case RIGHT -> mc.options.keyRight;
        };
    }

    private void letGo() {
        if (held != null) {
            InputUtil.release(held);
            held = null;
        }
    }

    // True on the tick a movement key or button goes down. A screen only counts
    // whilst InvWalk lets the keys through it.
    private boolean pressedMovementKey() {
        Screen screen = mc.gui.screen();
        InvWalk invWalk = Modules.active(InvWalk.class);
        boolean listen = screen == null || (invWalk != null && invWalk.allows(screen));
        List<KeyMapping> keys = movementKeys();
        boolean pressed = false;
        for (int i = 0; i < keys.size(); i++) {
            boolean down = InputUtil.physicallyHeld(keys.get(i));
            if (down && !wasHeld[i] && listen) {
                pressed = true;
            }
            wasHeld[i] = down;
        }
        return pressed;
    }

    private List<KeyMapping> movementKeys() {
        return List.of(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft,
            mc.options.keyRight, mc.options.keyShift, mc.options.keyJump);
    }

    // Looks two ticks of travel ahead so the stop comes before the border.
    private boolean chunkAheadMissing() {
        Vec3 motion = mc.player.getDeltaMovement();
        int chunkX = (int) Math.floor((mc.player.getX() + motion.x * 2) / 16);
        int chunkZ = (int) Math.floor((mc.player.getZ() + motion.z * 2) / 16);
        return !mc.level.getChunkSource().hasChunk(chunkX, chunkZ);
    }
}
