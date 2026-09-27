package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// Blowing a horn puts every goat horn on the cooldown that horn sets. The server keeps the
// same cooldown and a player who arrives during it is greeted once it ends.
public final class Honker extends Module {

    private final NumberSetting range = new NumberSetting("Range",
        "How close another player has to come before you honk.", 16, 2, 64, 1, " blocks").min(1);
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Stays quiet when a friend comes into range.", true);

    private final SlotSwap slots = new SlotSwap();

    // Players honked at who have stayed in range since.
    private final Set<UUID> greeted = new HashSet<>();

    public Honker() {
        super("Honker", "Blows a goat horn from your offhand or hotbar when another player comes near.", Category.MISC);
        addSettings(range, ignoreFriends);
        searchTags("goat horn", "horn", "honk");
    }

    @Override
    protected void onDisable() {
        greeted.clear();
        slots.restoreIfMine();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.gameMode == null || mc.player.isSpectator() || mc.player.isDeadOrDying()) {
            return;
        }
        Set<UUID> near = new HashSet<>();
        for (Player player : mc.level.players()) {
            if (player != mc.player && wanted(player) && mc.player.distanceTo(player) <= range.getValue()) {
                near.add(player.getUUID());
            }
        }
        // Someone who walks off and comes back is greeted again.
        greeted.retainAll(near);
        if (greeted.containsAll(near) || mc.gui.screen() != null || mc.player.isUsingItem()) {
            return;
        }
        if (honk()) {
            greeted.addAll(near);
        }
    }

    private boolean wanted(Player player) {
        return player.isAlive() && !player.isSpectator() && !Modules.isBot(player)
            && !(ignoreFriends.isOn() && EntityUtil.isFriend(player));
    }

    // A horn in the offhand is blown where it is. False whilst every horn is cooling down.
    private boolean honk() {
        if (ready(mc.player.getOffhandItem())) {
            return blow(InteractionHand.OFF_HAND);
        }
        int slot = InventoryUtil.hotbarSlot(this::ready);
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        boolean blew = blow(InteractionHand.MAIN_HAND);
        slots.restoreIfMine();
        return blew;
    }

    private boolean ready(ItemStack stack) {
        return stack.is(Items.GOAT_HORN) && !mc.player.getCooldowns().isOnCooldown(stack);
    }

    // The horn is lowered at once. The sound plays out whilst you keep walking at full speed.
    private boolean blow(InteractionHand hand) {
        boolean blew = mc.gameMode.useItem(mc.player, hand).consumesAction();
        if (mc.player.isUsingItem()) {
            mc.gameMode.releaseUsingItem(mc.player);
        }
        return blew;
    }
}
