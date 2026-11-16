package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.AttackTimer;
import com.jellypudding.offlineclient.util.Chase;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TargetFilter;
import com.jellypudding.offlineclient.util.Targeting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

import java.util.List;

// A bodyguard. Sticks to one player or mob and hits whatever comes close.
public final class Protect extends Module implements Targeting {

    public enum Ward { NEAREST_PLAYER, NEAREST_MOB, NAME }

    // How far off the ward may get before the bot leaves a fight to catch up.
    private static final double STRAY = 24;
    private static final double KEEP_FROM_WARD = 2;
    private static final double KEEP_FROM_ENEMY = 3;

    private final EnumSetting<Ward> wardChoice = new EnumSetting<>("Guard",
        "Who is guarded.", Ward.NEAREST_PLAYER)
        .describe(Ward.NEAREST_PLAYER, "The nearest player when this is switched on.")
        .describe(Ward.NEAREST_MOB, "The nearest mob of any kind when this is switched on.")
        .describe(Ward.NAME, "The nearest player or mob with the name you type in.");
    private final TextSetting wardName = new TextSetting("Name",
        "A player name or the name on a name tag or the name of a kind such as Villager. Click to"
            + " type it.", "")
        .under(wardChoice, Ward.NAME);
    private final NumberSetting range = new NumberSetting("Range",
        "How close an enemy has to be before the hits start.", 4.25, 1, 6, 0.05, " blocks");
    private final NumberSetting alertRange = new NumberSetting("Alert range",
        "How close to you an enemy has to come to be fought at all.", 6, 2, 16, 0.5, " blocks")
        .min(1);
    private final BoolSetting usePath = Chase.pathSwitch(false);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final BoolSetting pauseInContainers = new BoolSetting("Pause in containers",
        "Holds off whilst a chest or another container is open.", true);
    private final EntityFilter filter = EntityFilter.living("Fights", "fought", false,
        EntityFilter.Pick.ALL, List.of());
    private final TargetFilter targets = new TargetFilter();
    private final AttackTimer timer = new AttackTimer();

    private final Chase chase = new Chase();
    private LivingEntity ward;
    private Entity enemy;

    public Protect() {
        super("Protect", "Follows a player or a mob about and fights off whatever comes near.", Category.COMBAT);
        addSettings(wardChoice, wardName, range, alertRange, usePath, swing, pauseInContainers);
        addSettings(filter.settings());
        addSettings(targets.settings());
        addSettings(timer.settings());
        searchTags("bodyguard", "protect", "guard");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        return ward == null ? null : "guarding " + EntityUtil.displayNameOf(ward);
    }

    // What it fights off near the ward. Null between fights and whilst it holds off.
    @Override
    public Entity getFought() {
        return enemy;
    }

    @Override
    protected void onEnable() {
        timer.clear();
        enemy = null;
        ward = inGame() ? pickWard() : null;
        if (ward == null) {
            disable("Nobody to guard was found.");
        }
    }

    @Override
    protected void onDisable() {
        chase.stop();
        ward = null;
        enemy = null;
    }

    private LivingEntity pickWard() {
        String wanted = wardName.getValue().trim();
        Entity found = EntityUtil.nearest(STRAY * 4, entity -> guardable(entity) && switch (wardChoice.getValue()) {
            case NEAREST_PLAYER -> entity instanceof Player;
            case NEAREST_MOB -> entity instanceof Mob;
            case NAME -> EntityUtil.isCalled(entity, wanted);
        });
        return found instanceof LivingEntity living ? living : null;
    }

    // Anything alive that the server really sent and that you do not sit on.
    private static boolean guardable(Entity entity) {
        return entity instanceof LivingEntity living && living.isAlive() && !(entity instanceof ArmorStand)
            && !Modules.isBot(entity) && !Modules.isLocalBody(entity) && !EntityUtil.ridesWithYou(entity);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        enemy = null;
        if (Hands.tied()) {
            chase.stop();
            return;
        }
        if (ward == null || ward.isRemoved() || !ward.isAlive() || ward.level() != mc.level) {
            ChatUtil.message("Whoever you guarded is gone. Protect switched off.");
            setEnabled(false);
            return;
        }
        if (pauseInContainers.isOn() && InventoryUtil.containerOpen()) {
            chase.stop();
            return;
        }
        if (mc.player.distanceTo(ward) < STRAY) {
            enemy = EntityUtil.nearest(alertRange.getValue(), this::wouldFight);
        }
        boolean fighting = enemy != null;
        Entity target = fighting ? enemy : ward;
        chase.tick(target, fighting ? KEEP_FROM_ENEMY : KEEP_FROM_WARD, usePath.isOn());
        if (fighting && AttackTimer.charged() && timer.ready()
            && EntityUtil.reachDistance(mc.player, enemy) <= range.getValue()) {
            timer.strike(enemy, swing.getValue());
        }
    }

    @Override
    public boolean wouldFight(Entity entity) {
        return entity != ward && targets.attackable(entity, filter);
    }
}
