package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.phys.Vec3;

public final class Flight extends Module {

    public enum Mode { ABILITIES, DIRECT }

    private static final float VANILLA_FLY_SPEED = 0.05f;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Abilities flies like creative mode. Direct stops dead the moment you let go.",
        Mode.ABILITIES);
    private final NumberSetting speed = new NumberSetting("Speed",
        "Fly speed. 1 matches creative flight.", 1, 0.1, 10, 0.1, "x");
    private final NumberSetting verticalSpeed = new NumberSetting("Vertical",
        "Up and down speed in direct mode.", 1, 0.1, 10, 0.1, "x")
        .visibleWhen(() -> mode.is(Mode.DIRECT));
    private final NumberSetting timer = new NumberSetting("Timer",
        "Also speeds up the game whilst you fly. 1 does nothing.", 1, 1, 3, 0.1, "x").min(1);
    private final BoolSetting antiKick = new BoolSetting("AntiKick",
        "Drifts down a little now and then to dodge the vanilla flight kick.", true);
    private final NumberSetting antiKickInterval = new NumberSetting("Kick interval",
        "Ticks between each little dip.", 70, 5, 80, 1, " ticks")
        .visibleWhen(antiKick::isOn);

    private int tickCounter;

    public Flight() {
        super("Flight", "Lets you fly like in creative mode.", Category.MOVEMENT);
        addSettings(mode, speed, verticalSpeed, timer, antiKick, antiKickInterval);
        searchTags("fly");
    }

    @Override
    public String getSuffix() {
        return speed.getValueString();
    }

    @Override
    protected void onEnable() {
        tickCounter = 0;
    }

    @Override
    protected void onDisable() {
        Timer.override("flight", 1f);
        if (mc.player == null) {
            return;
        }
        Abilities abilities = mc.player.getAbilities();
        abilities.setFlyingSpeed(VANILLA_FLY_SPEED);
        if (!mc.player.isCreative() && !mc.player.isSpectator()) {
            abilities.flying = false;
        }
    }

    // TickEvent stops at a disconnect. ClientTickEvent still runs in the menus.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!inGame()) {
            Timer.override("flight", 1f);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        boolean moving = MovementUtil.inputDirection().lengthSqr() > 0
            || mc.options.keyJump.isDown() || mc.options.keyShift.isDown();
        Timer.override("flight", moving ? timer.getFloat() : 1f);

        if (mode.is(Mode.ABILITIES)) {
            abilitiesTick();
        } else {
            directTick();
        }
        if (antiKick.isOn()) {
            doAntiKick();
        }
    }

    private void abilitiesTick() {
        Abilities abilities = mc.player.getAbilities();
        abilities.flying = true;
        abilities.setFlyingSpeed((float) (VANILLA_FLY_SPEED * speed.getValue()));
    }

    private void directTick() {
        Abilities abilities = mc.player.getAbilities();
        // The flying flag turns gravity off and zero fly speed disables the vanilla push.
        abilities.flying = true;
        abilities.setFlyingSpeed(0);

        // Creative flight moves about 10.9 blocks a second.
        double h = 0.6 * speed.getValue();
        double v = 0.42 * verticalSpeed.getValue();

        double vy = 0;
        if (mc.options.keyJump.isDown()) {
            vy += v;
        }
        if (mc.options.keyShift.isDown()) {
            vy -= v;
        }

        Vec3 heading = MovementUtil.inputDirection();
        mc.player.setDeltaMovement(heading.x * h, vy, heading.z * h);
    }

    private void doAntiKick() {
        double dip = 0.04;
        if (tickCounter >= antiKickInterval.getInt()) {
            tickCounter = 0;
        }
        switch (tickCounter) {
            case 0 -> mc.player.setDeltaMovement(
                mc.player.getDeltaMovement().add(0, -dip, 0));
            case 1 -> mc.player.setDeltaMovement(
                mc.player.getDeltaMovement().add(0, dip, 0));
            default -> {
            }
        }
        tickCounter++;
    }
}
