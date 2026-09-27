package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.modules.combat.AntiBot;
import com.jellypudding.offlineclient.modules.combat.CrystalAura;
import com.jellypudding.offlineclient.modules.combat.Knockback;
import com.jellypudding.offlineclient.modules.combat.MaceCombo;
import com.jellypudding.offlineclient.modules.combat.PistonAura;
import com.jellypudding.offlineclient.modules.movement.Sneak;
import com.jellypudding.offlineclient.modules.player.AutoEat;
import com.jellypudding.offlineclient.modules.player.AutoExtinguish;
import com.jellypudding.offlineclient.modules.player.AutoGap;
import com.jellypudding.offlineclient.modules.player.AutoPotion;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Module lookup for mixins and other hot paths. ModuleManager is null until the
// client has started and this hands back null instead of throwing.
public final class Modules {

    private static final Map<Class<?>, Module> CACHE = new ConcurrentHashMap<>();
    private static final Set<Class<?>> MISSING = ConcurrentHashMap.newKeySet();

    private Modules() {
    }

    // Null before the client has started. Reached from other threads.
    public static <T extends Module> T get(Class<T> type) {
        Module cached = CACHE.get(type);
        if (cached != null) {
            return type.cast(cached);
        }
        if (MISSING.contains(type)) {
            return null;
        }
        ModuleManager manager = OfflineClient.INSTANCE.getModuleManager();
        if (manager == null) {
            return null;
        }
        T module;
        try {
            module = manager.get(type);
        } catch (IllegalStateException e) {
            // A mixin asking for an unregistered module must not take the game down.
            MISSING.add(type);
            OfflineClient.LOG.error("No module is registered for {}", type.getSimpleName());
            return null;
        }
        CACHE.put(type, module);
        return module;
    }

    public static boolean enabled(Class<? extends Module> type) {
        Module module = get(type);
        return module != null && module.isEnabled();
    }

    // True whilst a feeder other than the asker holds the use key.
    public static boolean feeding(Module asker) {
        AutoEat eat = get(AutoEat.class);
        if (eat != null && eat != asker && eat.isBusy()) {
            return true;
        }
        AutoGap gap = get(AutoGap.class);
        if (gap != null && gap != asker && gap.isBusy()) {
            return true;
        }
        AutoPotion potion = get(AutoPotion.class);
        if (potion != null && potion != asker && potion.isBusy()) {
            return true;
        }
        AutoExtinguish extinguish = get(AutoExtinguish.class);
        return extinguish != null && extinguish != asker && extinguish.isBusy();
    }

    // True whilst AutoEat or AutoGap is eating with Pause combat on.
    public static boolean feedersPauseCombat() {
        AutoEat autoEat = get(AutoEat.class);
        if (autoEat != null && autoEat.isEating()) {
            return true;
        }
        AutoGap autoGap = get(AutoGap.class);
        return autoGap != null && autoGap.isEating();
    }

    // Only the ticks around a real crystal place or break count.
    public static boolean crystalsActing() {
        CrystalAura crystals = active(CrystalAura.class);
        return crystals != null && crystals.isActing();
    }

    // True whilst PistonAura turns for a piston or waits on the crystal it pushes.
    public static boolean pistonFiring() {
        PistonAura piston = active(PistonAura.class);
        return piston != null && piston.isFiring();
    }

    // True whilst MaceCombo is between its throw and its smash.
    public static boolean maceComboAirborne() {
        MaceCombo combo = active(MaceCombo.class);
        return combo != null && combo.isAirborne();
    }

    // True whilst Knockback sends the sprint start that earns a hit its knockback.
    public static boolean renewingSprint() {
        Knockback knockback = active(Knockback.class);
        return knockback != null && knockback.sendingStart();
    }

    // The sneak key held or Sneak keeping the player down.
    public static boolean sneaking() {
        return OfflineClient.MC.player.isShiftKeyDown() || enabled(Sneak.class);
    }

    // True whilst AntiBot is on and takes the entity for a fake player.
    public static boolean isBot(Entity entity) {
        AntiBot antiBot = active(AntiBot.class);
        return antiBot != null && antiBot.isBot(entity);
    }

    // True whilst AntiBot leaves this bot out of the given render module.
    public static boolean hidesBot(AntiBot.View view, Entity entity) {
        AntiBot antiBot = active(AntiBot.class);
        return antiBot != null && antiBot.hides(view, entity);
    }

    // The module only whilst it is switched on. Null otherwise.
    public static <T extends Module> T active(Class<T> type) {
        T module = get(type);
        return module != null && module.isEnabled() ? module : null;
    }
}
