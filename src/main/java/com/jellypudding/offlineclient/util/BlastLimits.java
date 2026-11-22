package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.function.Supplier;

// Most modules that blow something up hold all three rows. A module with its own rule for friends
// holds only the two rows for you.
public final class BlastLimits {

    private final NumberSetting maxSelfDamage;
    // Null whilst the module judges friends itself.
    private final NumberSetting maxFriendDamage;
    private final BoolSetting antiSuicide = new BoolSetting("Anti suicide",
        "Never sets off a blast that could kill you.", true);

    // A friend is spared as much as you spare yourself until the player says otherwise.
    public BlastLimits(double maxDamage, double sliderMax) {
        this(maxDamage, sliderMax, true);
    }

    private BlastLimits(double maxDamage, double sliderMax, boolean friends) {
        maxSelfDamage = new NumberSetting("Max self damage",
            "Never lets you take more than this from your own blast.", maxDamage, 0, sliderMax, 0.5);
        maxFriendDamage = friends ? new NumberSetting("Max friend damage",
            "Never deals a friend more than this with your own blast or enough to kill them.",
            maxDamage, 0, sliderMax, 0.5) : null;
    }

    public static BlastLimits selfOnly(double maxDamage, double sliderMax) {
        return new BlastLimits(maxDamage, sliderMax, false);
    }

    public Setting<?>[] settings() {
        return maxFriendDamage == null ? new Setting<?>[] {maxSelfDamage, antiSuicide}
            : new Setting<?>[] {maxSelfDamage, maxFriendDamage, antiSuicide};
    }

    public <E extends Enum<E>> BlastLimits under(EnumSetting<E> parent, E value) {
        Setting.under(settings(), parent, value);
        return this;
    }

    public BlastLimits under(BoolSetting parent) {
        for (Setting<?> row : settings()) {
            row.under(parent);
        }
        return this;
    }

    public BlastLimits visibleWhen(Supplier<Boolean> visibility) {
        for (Setting<?> row : settings()) {
            row.visibleWhen(visibility);
        }
        return this;
    }

    // The ignored blocks are gone by the time the blast goes off.
    public boolean allows(Vec3 source, float power, BlockPos... ignored) {
        return selfTakes(ExplosionUtil.blastDamage(OfflineClient.MC.player, source, power, Vec3.ZERO, ignored))
            && (maxFriendDamage == null
                || ExplosionUtil.friendsSafe(source, power, maxFriendDamage.getFloat(), ignored));
    }

    public boolean selfTakes(float damage) {
        return damage <= maxSelfDamage.getFloat()
            && (!antiSuicide.isOn() || damage < EntityUtil.totalHealth(OfflineClient.MC.player));
    }
}
