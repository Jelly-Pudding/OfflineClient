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
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TargetFilter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

// A bodyguard. Sticks to one friend and hits whatever comes close.
public final class Protect extends Module {

    public enum Friend { NEAREST, NAME }

    // How far off the friend may get before the bot leaves a fight to catch up.
    private static final double STRAY = 24;
    private static final double KEEP_FROM_FRIEND = 2;
    private static final double KEEP_FROM_ENEMY = 3;

    private final EnumSetting<Friend> friendChoice = new EnumSetting<>("Friend",
        "Who is guarded.", Friend.NEAREST)
        .describe(Friend.NEAREST, "The nearest player when this is switched on.")
        .describe(Friend.NAME, "The player whose name you type in.");
    private final TextSetting friendName = new TextSetting("Name",
        "The name of the player to guard. Click to type it.", "")
        .under(friendChoice, Friend.NAME);
    private final NumberSetting range = new NumberSetting("Range",
        "How close an enemy has to be before the hits start.", 4.25, 1, 6, 0.05).min(1).max(6);
    private final NumberSetting alertRange = new NumberSetting("Alert range",
        "How close to you an enemy has to come to be fought at all.", 6, 2, 16, 0.5, " blocks")
        .min(1);
    private final BoolSetting usePath = new BoolSetting("Use pathfinder",
        "Finds a way round what is in between instead of running straight.", false);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final BoolSetting pauseInContainers = new BoolSetting("Pause in containers",
        "Holds off whilst a chest or another container is open.", true);
    private final EntityFilter filter = EntityFilter.living("Fight", "fought", false,
        EntityFilter.Pick.ALL, List.of());
    private final TargetFilter targets = new TargetFilter();
    private final AttackTimer timer = new AttackTimer();

    private final Chase chase = new Chase();
    private LivingEntity friend;
    private Entity enemy;

    public Protect() {
        super("Protect", "Follows a friend about and fights off whatever comes near.", Category.COMBAT);
        addSettings(friendChoice, friendName, range, alertRange, usePath, swing, pauseInContainers);
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
        return friend == null ? null : "guarding " + friend.getName().getString();
    }

    @Override
    protected void onEnable() {
        timer.clear();
        enemy = null;
        friend = inGame() ? pickFriend() : null;
        if (friend == null) {
            ChatUtil.error("Nobody to guard was found.");
            setEnabled(false);
        }
    }

    @Override
    protected void onDisable() {
        chase.stop();
        friend = null;
        enemy = null;
    }

    private LivingEntity pickFriend() {
        String wanted = friendName.getValue().trim();
        Entity found = EntityUtil.nearest(STRAY * 4, entity -> entity instanceof Player player
            && player != mc.player && player.isAlive()
            && (friendChoice.is(Friend.NEAREST) || player.getName().getString().equalsIgnoreCase(wanted)));
        return found instanceof LivingEntity living ? living : null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || mc.player.isDeadOrDying()) {
            chase.stop();
            return;
        }
        if (friend == null || friend.isRemoved() || !friend.isAlive() || friend.level() != mc.level) {
            ChatUtil.message("The friend is gone. Protect switched off.");
            setEnabled(false);
            return;
        }
        if (pauseInContainers.isOn() && InventoryUtil.isStorage(mc.gui.screen())) {
            chase.stop();
            return;
        }
        enemy = EntityUtil.nearest(alertRange.getValue(), this::attackable);
        boolean fighting = enemy != null && mc.player.distanceTo(friend) < STRAY;
        Entity target = fighting ? enemy : friend;
        chase.tick(target, fighting ? KEEP_FROM_ENEMY : KEEP_FROM_FRIEND, usePath.isOn());
        if (!fighting || !timer.ready()
            || EntityUtil.reachDistance(mc.player, enemy) > range.getValue()) {
            return;
        }
        mc.gameMode.attack(mc.player, enemy);
        swing.getValue().swing(InteractionHand.MAIN_HAND);
        timer.spent();
    }

    private boolean attackable(Entity entity) {
        return entity != friend && targets.attackable(entity, filter);
    }
}
