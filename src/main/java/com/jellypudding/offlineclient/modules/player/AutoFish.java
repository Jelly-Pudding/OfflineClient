package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * A bite is spotted from the bobber's synced flag and from the splash sound
 * near the bobber.
 */
public final class AutoFish extends Module {

    // Durability points left that count as about to break.
    private static final int NEARLY_BROKEN = 2;

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

    private final InventoryUtil.SlotSwap slots = new InventoryUtil.SlotSwap();
    private int castTimer;
    private int reelTimer = -1;
    private int patienceTimer;
    // Written from the packet thread.
    private volatile boolean splashHeard;
    // Holding the bobber itself would pin the world.
    private int reeledId = -1;
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
    protected void onDisable() {
        slots.restoreIfMine();
    }

    @Override
    protected void onEnable() {
        castTimer = 0;
        reelTimer = -1;
        patienceTimer = 0;
        splashHeard = false;
        reeledId = -1;
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

        if (autoSwitch.isOn()) {
            int rodSlot = bestRod();
            if (rodSlot != -1) {
                slots.select(rodSlot);
            }
        }
        ItemStack held = mc.player.getMainHandItem();
        if (!(held.getItem() instanceof FishingRodItem)) {
            return;
        }
        // Reeling in wears the rod down as much as casting does.
        if (antiBreak.isOn() && ItemUtil.nearlyBroken(held, NEARLY_BROKEN)) {
            return;
        }

        FishingHook bobber = mc.player.fishing;
        if (bobber == null || bobber.isRemoved()) {
            reelTimer = -1;
            splashHeard = false;
            reeledId = -1;
            if (castTimer > 0) {
                return;
            }
            useRod();
            castTimer = recastDelay.getInt();
            patienceTimer = patience.getInt() * 20;
            return;
        }
        // The old bobber lingers for a tick or two after reeling.
        if (bobber.getId() == reeledId) {
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
        reeledId = bobber.getId();
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
        // The packet thread can drop the player mid handler.
        LocalPlayer player = mc.player;
        FishingHook bobber = player == null ? null : player.fishing;
        if (bobber == null || bobber.isRemoved()) {
            return;
        }
        // Someone else's bobber can splash nearby.
        double dx = Math.abs(sound.getX() - bobber.getX());
        double dz = Math.abs(sound.getZ() - bobber.getZ());
        if (Math.max(dx, dz) <= 1.5) {
            splashHeard = true;
        }
    }

    private void useRod() {
        MultiPlayerGameMode gameMode = mc.gameMode;
        if (gameMode == null) {
            return;
        }
        if (gameMode.useItem(mc.player, InteractionHand.MAIN_HAND).consumesAction()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private int bestRod() {
        int bestSlot = -1;
        int bestScore = -1;
        for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!(stack.getItem() instanceof FishingRodItem)) {
                continue;
            }
            if (antiBreak.isOn() && ItemUtil.nearlyBroken(stack, NEARLY_BROKEN)) {
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
