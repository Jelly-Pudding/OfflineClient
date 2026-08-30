package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.MouseScrollEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.HoverDip;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.phys.Vec3;

public final class Flight extends Module {

    public enum Mode { CREATIVE, DIRECT }


    /**
     * The pace creative flight settles at in blocks per tick. Direct mode
     * uses the same numbers. A speed of one means the same in both.
     */
    // How much one wheel notch changes the speed.
    private static final double SCROLL_STEP = 0.1;

    private static final String TIMER_KEY = "flight";

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
    private final BoolSetting antiKick = new BoolSetting("AntiKick",
        "Drifts down a little now and then to dodge the vanilla flight kick.", true);
    private final NumberSetting antiKickInterval = new NumberSetting("Kick interval",
        "Ticks between each little dip.", 70, 5, 80, 1, " ticks")
        .under(antiKick);

    private final HoverDip dip = new HoverDip();

    public Flight() {
        super("Flight", "Lets you fly like in creative mode.", Category.MOVEMENT);
        addSettings(mode, horizontalSpeed, verticalSpeed, scrollSpeed, timer, antiKick,
            antiKickInterval);
        searchTags("fly");
    }

    @Override
    public String getSuffix() {
        return horizontalSpeed.getValueString();
    }

    /**
     * Read by LocalPlayerMixin in place of the fly speed creative flight
     * pushes up and down with. The horizontal setting must not leak into it.
     */
    public float verticalFlySpeed() {
        return (float) (MovementUtil.VANILLA_FLY_SPEED * verticalSpeed.getValue());
    }

    @Override
    protected void onEnable() {
        dip.reset();
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
        if (antiKick.isOn()) {
            dip.tick(antiKickInterval.getInt());
        }
    }

    private void creativeTick() {
        Abilities abilities = mc.player.getAbilities();
        abilities.flying = true;
        abilities.setFlyingSpeed((float) (MovementUtil.VANILLA_FLY_SPEED * horizontalSpeed.getValue()));
    }

    private void directTick() {
        Abilities abilities = mc.player.getAbilities();
        // The flying flag turns gravity off and zero fly speed disables the vanilla push.
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
}
