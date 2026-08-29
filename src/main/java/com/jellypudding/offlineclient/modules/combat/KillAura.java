package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.AttackTimer;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class KillAura extends Module {

    public enum Priority { NEAREST, CLOSEST_ANGLE, LOW_HEALTH }

    private final NumberSetting range = new NumberSetting("Range",
        "Maximum reach in blocks.", 4.2, 1, 6, 0.05);
    private final NumberSetting wallsRange = new NumberSetting("Walls range",
        "Shorter reach for targets you cannot see.", 3.5, 0, 6, 0.05);
    private final NumberSetting fov = new NumberSetting("FOV",
        "Only hit targets within this angle of your view.", 360, 30, 360, 5, " degrees")
        .max(360);
    private final BoolSetting players = new BoolSetting("Players",
        "Swing at other players.", true);
    private final BoolSetting mobs = new BoolSetting("Mobs",
        "Swing at mobs both hostile and passive.", false);
    private final EnumSetting<Priority> priority = new EnumSetting<>("Priority",
        "Which one to pick first when several are in range.", Priority.NEAREST)
        .describe(Priority.NEAREST, "Attacks the closest target first.")
        .describe(Priority.CLOSEST_ANGLE, "Attacks whoever is nearest your crosshair first.")
        .describe(Priority.LOW_HEALTH, "Attacks whoever has the least health left first.");
    private final NumberSetting maxTargets = new NumberSetting("Max targets",
        "How many entities to swing at in one go.", 1, 1, 8, 1);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward the target without moving your view.", true);
    private final NumberSetting rotateSpeed = new NumberSetting("Rotate speed",
        "How many degrees the look packet may turn in one tick.", 45, 15, 180, 5, " degrees")
        .min(1).max(180)
        .under(rotate);
    private final BoolSetting walls = new BoolSetting("Through walls",
        "Also swing at targets you cannot see.", true);
    private final BoolSetting autoWeapon = new BoolSetting("Auto weapon",
        "Switches to your best sword or axe before each hit.", false);
    private final BoolSetting weaponSwapBack = new BoolSetting("Swap back",
        "Returns to the slot you had the moment the hit has gone out. Off stays on the weapon.", false)
        .under(autoWeapon);
    private final BoolSetting breakShields = new BoolSetting("Break shields",
        "Reach for an axe whilst the target holds a raised shield.", true);
    private final BoolSetting weaponOnly = new BoolSetting("Weapon only",
        "Never swing whilst holding something that is not a sword or an axe.", false);
    private final BoolSetting onlyOnClick = new BoolSetting("Only on click",
        "Only swing whilst you hold the attack key down.", false);
    private final BoolSetting ignoreCreative = new BoolSetting("Ignore creative",
        "Skip players in creative mode.", true)
        .under(players);
    private final BoolSetting ignoreNamed = new BoolSetting("Ignore named",
        "Skip mobs wearing a name tag.", true)
        .under(mobs);
    private final BoolSetting ignoreTamed = new BoolSetting("Ignore tamed",
        "Skip tamed animals.", true)
        .under(mobs);
    private final BoolSetting ignoreBabies = new BoolSetting("Ignore babies",
        "Skip baby animals.", true)
        .under(mobs);
    private final BoolSetting ignoreCooldown = new BoolSetting("Ignore cooldown",
        "Swings before the attack cooldown ends. Hits land weaker but far more often.", false);
    private final BoolSetting pauseOnUse = new BoolSetting("Pause on use",
        "Hold off whilst eating or blocking or drawing a bow or mining.", true);
    private final BoolSetting pauseOnContainers = new BoolSetting("Pause in GUIs",
        "No swinging whilst a chest or inventory screen is open.", true);

    private final AttackTimer timer = new AttackTimer();
    private final SlotSwap slots = new SlotSwap();

    public KillAura() {
        super("KillAura", "Automatically swings at nearby mobs and players.", Category.COMBAT);
        addSettings(range, wallsRange, fov, players, mobs, priority, maxTargets);
        addSettings(timer.settings());
        addSettings(rotate, rotateSpeed, walls, autoWeapon, weaponSwapBack, breakShields,
            weaponOnly, onlyOnClick, ignoreCreative, ignoreNamed, ignoreTamed,
            ignoreBabies, ignoreCooldown, pauseOnUse, pauseOnContainers);
        searchTags("aura", "multi aura", "aimbot");
    }

    @Override
    public String getSuffix() {
        return range.getValueString();
    }

    @Override
    protected void onEnable() {
        timer.clear();
        slots.forget();
    }

    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (!inGame() || mc.player.isSpectator() || holdingFire()) {
            return;
        }
        if (!timer.ready()) {
            return;
        }
        List<LivingEntity> targets = pickTargets();
        if (!targets.isEmpty()) {
            strike(targets);
        }
    }

    // Every reason the aura sits this tick out.
    private boolean holdingFire() {
        if (pauseOnUse.isOn() && (mc.player.isUsingItem() || mc.gameMode.isDestroying())) {
            return true;
        }
        if (pauseOnContainers.isOn() && mc.gui.screen() != null) {
            return true;
        }
        if (onlyOnClick.isOn() && !mc.options.keyAttack.isDown()) {
            return true;
        }
        if (weaponOnly.isOn() && !holdingWeapon()) {
            return true;
        }
        // Hits before the attack cooldown ends deal reduced damage.
        if (!ignoreCooldown.isOn() && mc.player.getAttackStrengthScale(0.5f) < 1) {
            return true;
        }
        return Modules.eating() || crystalsBusy();
    }

    // Crystals hurt far more than a sword. Only the ticks around a real
    // place or break are given up.
    private boolean crystalsBusy() {
        CrystalAura crystals = Modules.get(CrystalAura.class);
        return crystals != null && crystals.isEnabled() && crystals.isActing();
    }

    // Hits everything in reach on one swing then sets the next wait.
    private void strike(List<LivingEntity> targets) {
        LivingEntity primary = targets.getFirst();
        // The swing waits for the turn to land on the server.
        if (rotate.isOn() && !RotationManager.look(primary.getBoundingBox().getCenter(),
            RotationPriority.ATTACK, RotationManager.ENTITY_TOLERANCE, rotateSpeed.getFloat())) {
            return;
        }

        selectWeapon(primary);
        // One swing animation covers every entity hit on the tick.
        for (LivingEntity target : targets) {
            mc.gameMode.attack(mc.player, target);
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
        if (weaponSwapBack.isOn()) {
            slots.restore();
        } else {
            slots.forget();
        }
        timer.spent();
    }

    private boolean holdingWeapon() {
        ItemStack held = mc.player.getInventory().getSelectedItem();
        return held.is(ItemTags.SWORDS) || held.is(ItemTags.AXES) || held.is(Items.MACE);
    }

    // Holds the best weapon for the target until the hit has landed.
    private void selectWeapon(LivingEntity target) {
        if (!autoWeapon.isOn()) {
            return;
        }
        // AutoWeapon and AttributeSwap keep the slot themselves.
        if (Modules.enabled(AutoWeapon.class) || Modules.enabled(AttributeSwap.class)) {
            return;
        }
        // An axe staggers a raised shield whilst a sword bounces off it.
        boolean blocking = breakShields.isOn() && target.isBlocking();
        int best = blocking
            ? AutoWeapon.bestWeaponSlot(target, false, 0, true)
            : AutoWeapon.bestWeaponSlot(target);
        if (best != -1) {
            slots.select(best);
        }
    }

    private List<LivingEntity> pickTargets() {
        List<LivingEntity> targets = new ArrayList<>();
        double furthest = walls.isOn()
            ? Math.max(range.getValue(), wallsRange.getValue())
            : range.getValue();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || entity == mc.player) {
                continue;
            }
            if (!living.isAlive() || living.isSpectator()) {
                continue;
            }
            double distance = EntityUtil.reachDistance(mc.player, living);
            if (distance > furthest) {
                continue;
            }
            if (living instanceof Player player) {
                if (!players.isOn()) {
                    continue;
                }
                if (ignoreCreative.isOn() && player.isCreative()) {
                    continue;
                }
                if (EntityUtil.isFriend(player)) {
                    continue;
                }
            } else if (living instanceof Mob mob) {
                if (!mobs.isOn() || ignored(mob)) {
                    continue;
                }
            } else {
                continue;
            }
            if (!inFov(living)) {
                continue;
            }
            // The line of sight raycast costs the most.
            boolean visible = canSee(living);
            if (!visible && !walls.isOn()) {
                continue;
            }
            if (distance > (visible ? range.getValue() : wallsRange.getValue())) {
                continue;
            }
            targets.add(living);
        }

        Comparator<LivingEntity> order = switch (priority.getValue()) {
            case NEAREST -> Comparator.comparingDouble(t -> EntityUtil.reachDistance(mc.player, t));
            case CLOSEST_ANGLE -> Comparator.comparingDouble(EntityUtil::lookAngleTo);
            case LOW_HEALTH -> Comparator.comparingDouble(LivingEntity::getHealth);
        };
        targets.sort(order);
        int wanted = maxTargets.getInt();
        return targets.size() <= wanted ? targets : targets.subList(0, wanted);
    }

    // Either the head or the feet being visible is enough to land a hit.
    private boolean canSee(LivingEntity target) {
        if (mc.player.hasLineOfSight(target)) {
            return true;
        }
        Vec3 eye = mc.player.getEyePosition();
        Vec3 feet = target.position().add(0, 0.1, 0);
        return mc.level.clip(new ClipContext(eye, feet, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    private boolean ignored(Mob mob) {
        if (ignoreNamed.isOn() && mob.hasCustomName()) {
            return true;
        }
        if (ignoreTamed.isOn() && mob instanceof TamableAnimal tamable && tamable.isTame()) {
            return true;
        }
        return ignoreBabies.isOn() && mob.isBaby();
    }

    private boolean inFov(LivingEntity target) {
        double limit = fov.getValue();
        return limit >= 360 || EntityUtil.lookAngleTo(target) <= limit / 2;
    }
}
