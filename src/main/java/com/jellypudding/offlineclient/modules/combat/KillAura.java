package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class KillAura extends Module {

    public enum Priority {
        NEAREST("Nearest"),
        LOWEST_HEALTH("Low health");

        private final String name;

        Priority(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final NumberSetting range = new NumberSetting("Range",
        "Maximum reach in blocks.", 4.2, 1, 6, 0.05);
    private final BoolSetting players = new BoolSetting("Players",
        "Swing at other players.", true);
    private final BoolSetting mobs = new BoolSetting("Mobs",
        "Swing at mobs (hostile and passive).", false);
    private final EnumSetting<Priority> priority = new EnumSetting<>("Priority",
        "Which one to pick first when several are in range.", Priority.NEAREST);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn toward the target while swinging.", true);
    private final BoolSetting walls = new BoolSetting("Through walls",
        "Also swing at targets you cannot see.", true);
    private final BoolSetting pauseOnUse = new BoolSetting("Pause on use",
        "Hold off while eating or blocking or drawing a bow or mining.", true);
    private final BoolSetting pauseOnContainers = new BoolSetting("Pause in GUIs",
        "Don't swing while a chest or inventory screen is open.", true);

    public KillAura() {
        super("KillAura", "Automatically swings at nearby mobs and players.", Category.COMBAT);
        addSettings(range, players, mobs, priority, rotate, walls, pauseOnUse, pauseOnContainers);
    }

    @Override
    public String getSuffix() {
        return range.getValueString();
    }

    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (pauseOnUse.isOn() && (mc.player.isUsingItem() || mc.gameMode.isDestroying())) {
            return;
        }
        if (pauseOnContainers.isOn() && mc.gui.screen() != null) {
            return;
        }
        // Hits before the attack cooldown ends deal reduced damage.
        if (mc.player.getAttackStrengthScale(0.5f) < 1) {
            return;
        }

        LivingEntity target = pickTarget();
        if (target == null) {
            return;
        }

        if (rotate.isOn()) {
            faceEntity(target);
        }

        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private LivingEntity pickTarget() {
        List<LivingEntity> targets = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || entity == mc.player) {
                continue;
            }
            if (!living.isAlive() || living.isSpectator()) {
                continue;
            }
            if (EntityUtil.reachDistance(mc.player, living) > range.getValue()) {
                continue;
            }
            if (!walls.isOn() && !mc.player.hasLineOfSight(living)) {
                continue;
            }
            if (living instanceof Player player) {
                if (!players.isOn()) {
                    continue;
                }
                if (OfflineClient.INSTANCE.getFriendManager()
                    .isFriend(player.getGameProfile().name())) {
                    continue;
                }
            } else if (living instanceof Mob) {
                if (!mobs.isOn()) {
                    continue;
                }
            } else {
                continue;
            }
            targets.add(living);
        }

        return targets.stream().min(switch (priority.getValue()) {
            case NEAREST -> Comparator.comparingDouble(t -> EntityUtil.reachDistance(mc.player, t));
            case LOWEST_HEALTH -> Comparator.comparingDouble(LivingEntity::getHealth);
        }).orElse(null);
    }

    private void faceEntity(LivingEntity target) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 point = target.getBoundingBox().getCenter();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));

        mc.player.setYRot(yaw);
        mc.player.setXRot(Math.clamp(pitch, -90f, 90f));
    }
}
