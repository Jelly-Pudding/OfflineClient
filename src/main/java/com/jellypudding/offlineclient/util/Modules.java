package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.modules.combat.AntiBot;
import com.jellypudding.offlineclient.modules.combat.CrystalAura;
import com.jellypudding.offlineclient.modules.combat.Knockback;
import com.jellypudding.offlineclient.modules.combat.MaceCombo;
import com.jellypudding.offlineclient.modules.combat.PistonAura;
import com.jellypudding.offlineclient.modules.misc.AntiPacketKick;
import com.jellypudding.offlineclient.modules.misc.FakePlayer;
import com.jellypudding.offlineclient.modules.misc.Latency;
import com.jellypudding.offlineclient.modules.movement.Fetch;
import com.jellypudding.offlineclient.modules.movement.Flight;
import com.jellypudding.offlineclient.modules.movement.Sneak;
import com.jellypudding.offlineclient.modules.player.AutoEat;
import com.jellypudding.offlineclient.modules.player.AutoExtinguish;
import com.jellypudding.offlineclient.modules.player.AutoGap;
import com.jellypudding.offlineclient.modules.player.AutoPotion;
import com.jellypudding.offlineclient.modules.player.AutoTool;
import com.jellypudding.offlineclient.modules.render.Waypoints;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
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

    // True whilst Knockback sends the sprint start that earns a hit its knockback or Fetch
    // sends the one its pull needs.
    public static boolean renewingSprint() {
        Knockback knockback = active(Knockback.class);
        if (knockback != null && knockback.sendingStart()) {
            return true;
        }
        Fetch fetch = active(Fetch.class);
        return fetch != null && fetch.sendingStart();
    }

    // Switches Flight off. A flying player never lands on the block a builder puts under them.
    public static void stopFlight() {
        Flight flight = active(Flight.class);
        if (flight != null) {
            flight.setEnabled(false);
        }
    }

    // True whilst AntiPacketKick keeps every teleport to one step a tick.
    public static boolean spreadsTeleports() {
        AntiPacketKick antiKick = active(AntiPacketKick.class);
        return antiKick != null && antiKick.spreadsTeleports();
    }

    // True whilst Latency holds packets of this kind on their way out. Reached from any thread.
    public static boolean delays(PacketType<?> type) {
        Latency latency = get(Latency.class);
        return latency != null && latency.delays(type);
    }

    // The hotbar slot to mine the block with. AutoTool's rules decide whilst it is on and the
    // fastest tool does otherwise. Minus one keeps what is held.
    public static int toolSlot(BlockState state) {
        AutoTool autoTool = active(AutoTool.class);
        return autoTool != null ? autoTool.hotbarPick(state) : ItemUtil.bestToolSlot(state);
    }

    // True whilst AutoTool holds back a break that would snap the tool in hand.
    public static boolean toolHeldBack() {
        AutoTool autoTool = active(AutoTool.class);
        return autoTool != null && autoTool.holdsBack();
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

    // True for a body only this client has such as the copy FakePlayer or Blink puts down.
    // The server never sent it and it may carry your name and your gear.
    public static boolean isLocalBody(Entity entity) {
        return entity instanceof FakePlayer.Body;
    }

    // The hue the next saved waypoint takes. The Waypoints module picks it whether or not it
    // is switched on and without it the colour comes from the name.
    public static int nextWaypointHue() {
        Waypoints waypoints = get(Waypoints.class);
        return waypoints == null ? WaypointStore.Waypoint.AUTO_HUE : waypoints.nextHue();
    }

    // The module only whilst it is switched on. Null otherwise.
    public static <T extends Module> T active(Class<T> type) {
        T module = get(type);
        return module != null && module.isEnabled() ? module : null;
    }

    // Every module that is also a T in the order they are listed. Empty before the client has started.
    public static <T> List<T> all(Class<T> type) {
        ModuleManager manager = OfflineClient.INSTANCE.getModuleManager();
        List<T> all = new ArrayList<>();
        if (manager == null) {
            return all;
        }
        for (Module module : manager.getAll()) {
            if (type.isInstance(module)) {
                all.add(type.cast(module));
            }
        }
        return all;
    }
}
