package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Casts the rod and reels it back in when something bites. A bite is
 * spotted from the bobber's own synced flag and from the splash sound
 * near the bobber.
 */
public final class AutoFish extends Module {

    private final NumberSetting recastDelay = new NumberSetting("Recast delay",
        "Ticks to wait after reeling in before casting again.", 15, 1, 60, 1, " ticks");
    private final NumberSetting catchDelay = new NumberSetting("Catch delay",
        "Ticks to wait after a bite before reeling in.", 4, 0, 20, 1, " ticks");
    private final NumberSetting patience = new NumberSetting("Patience",
        "Seconds to wait without a bite before reeling in and recasting.", 60, 10, 120, 1, "s");
    private final BoolSetting autoSwitch = new BoolSetting("Auto switch",
        "Move to the best fishing rod in your hotbar.", true);
    private final BoolSetting antiBreak = new BoolSetting("Anti break",
        "Never use a rod that is about to break.", true);

    private int castTimer;
    private int reelTimer = -1;
    private int patienceTimer;
    private boolean splashHeard;
    private FishingHook reeled;
    private int caught;

    public AutoFish() {
        super("AutoFish", "Casts and reels the fishing rod for you.", Category.PLAYER);
        addSettings(recastDelay, catchDelay, patience, autoSwitch, antiBreak);
        searchTags("fishing", "afk fish", "rod");
    }

    @Override
    public String getSuffix() {
        return caught + " caught";
    }

    @Override
    protected void onEnable() {
        castTimer = 0;
        reelTimer = -1;
        patienceTimer = 0;
        splashHeard = false;
        reeled = null;
        caught = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (castTimer > 0) {
            castTimer--;
        }
        if (mc.gui.screen() != null) {
            return;
        }

        int rodSlot = bestRod();
        if (autoSwitch.isOn() && rodSlot != -1
            && rodSlot != mc.player.getInventory().getSelectedSlot()) {
            mc.player.getInventory().setSelectedSlot(rodSlot);
        }
        if (!(mc.player.getMainHandItem().getItem() instanceof FishingRodItem)) {
            return;
        }

        FishingHook bobber = mc.player.fishing;
        if (bobber == null || bobber.isRemoved()) {
            reelTimer = -1;
            splashHeard = false;
            reeled = null;
            if (castTimer > 0) {
                return;
            }
            useRod();
            castTimer = recastDelay.getInt();
            patienceTimer = patience.getInt() * 20;
            return;
        }
        // The old bobber lingers for a tick or two after reeling. Leave it alone.
        if (bobber == reeled) {
            return;
        }

        boolean bite = bobber.biting || splashHeard || bobber.getHookedIn() != null;
        if (bite && reelTimer < 0) {
            reelTimer = catchDelay.getInt();
        }
        if (reelTimer < 0 && patienceTimer > 0) {
            patienceTimer--;
            if (patienceTimer == 0) {
                reelTimer = 0;
            }
        }
        if (reelTimer < 0) {
            return;
        }
        if (reelTimer > 0) {
            reelTimer--;
            return;
        }

        if (bite) {
            caught++;
        }
        useRod();
        reeled = bobber;
        reelTimer = -1;
        splashHeard = false;
        castTimer = recastDelay.getInt();
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPacket() instanceof ClientboundSoundPacket sound)) {
            return;
        }
        if (sound.getSound().value() != SoundEvents.FISHING_BOBBER_SPLASH) {
            return;
        }
        FishingHook bobber = mc.player == null ? null : mc.player.fishing;
        if (bobber == null || bobber.isRemoved()) {
            return;
        }
        // Someone else's bobber can splash nearby. Only trust sounds right on ours.
        double dx = Math.abs(sound.getX() - bobber.getX());
        double dz = Math.abs(sound.getZ() - bobber.getZ());
        if (Math.max(dx, dz) <= 1.5) {
            splashHeard = true;
        }
    }

    private void useRod() {
        if (mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND).consumesAction()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    /**
     * Hotbar slot of the best rod or minus one. Lure and Luck of the Sea and
     * Mending and Unbreaking each add to the score.
     */
    private int bestRod() {
        int bestSlot = -1;
        int bestScore = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!(stack.getItem() instanceof FishingRodItem)) {
                continue;
            }
            if (antiBreak.isOn() && stack.getMaxDamage() - stack.getDamageValue() <= 2) {
                continue;
            }
            int score = ItemUtil.enchantLevel(Enchantments.LURE, stack)
                + ItemUtil.enchantLevel(Enchantments.LUCK_OF_THE_SEA, stack)
                + ItemUtil.enchantLevel(Enchantments.MENDING, stack)
                + ItemUtil.enchantLevel(Enchantments.UNBREAKING, stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }
}
