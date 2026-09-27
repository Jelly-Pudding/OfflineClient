package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

// Throws a wind charge straight down at the feet and jumps on the next tick. The charge
// bursts on the ground two server ticks after the throw and the server measures the push
// from where it saw you last. Feet just off the ground on that tick take nearly the whole
// push and the jump already under way adds to it.
public final class WindLaunch {

    private static final Minecraft MC = OfflineClient.MC;

    // A burst after this long belongs to some other charge.
    private static final long BLAST_WAIT_MILLIS = 1000;

    // Twice the burst radius of a wind charge. Nothing further out is pushed.
    private static final double BLAST_REACH = 2.4;

    // Read on the network thread by NoKnockback.
    private static volatile Vec3 thrownAt;
    private static volatile long blastDueUntil;

    private final HotbarLoan loan = new HotbarLoan();
    private boolean jumpNext;

    // Read by NoKnockback. The burst of a charge thrown here always lifts you. The sound
    // tells it apart from a crystal or bed going off beside the same spot.
    public static boolean ownBlast(ClientboundExplodePacket blast) {
        Vec3 spot = thrownAt;
        return spot != null && System.currentTimeMillis() < blastDueUntil
            && blast.explosionSound().is(SoundEvents.WIND_CHARGE_BURST.key())
            && spot.distanceTo(blast.center()) <= BLAST_REACH;
    }

    // Whether a charge is to hand at all. The offhand counts as well.
    public static boolean hasCharge(boolean wholeInventory) {
        return MC.player.getOffhandItem().is(Items.WIND_CHARGE)
            || InventoryUtil.findSlot(Items.WIND_CHARGE, limit(wholeInventory)) != -1;
    }

    // Standing on the ground with a charge to hand and the last throw cooled down.
    public static boolean canThrow(boolean wholeInventory) {
        LocalPlayer player = MC.player;
        return player.onGround() && !player.isUsingItem() && hasCharge(wholeInventory)
            && !player.getCooldowns().isOnCooldown(new ItemStack(Items.WIND_CHARGE));
    }

    private static int limit(boolean wholeInventory) {
        return wholeInventory ? InventoryUtil.WHOLE_INVENTORY : InventoryUtil.HOTBAR_SIZE;
    }

    // Throws from the offhand when a charge sits there. Otherwise one is borrowed into the
    // main hand for the throw. False when nothing could be thrown.
    public boolean start(boolean wholeInventory) {
        if (jumpNext || !canThrow(wholeInventory)) {
            return false;
        }
        LocalPlayer player = MC.player;
        boolean offhand = player.getOffhandItem().is(Items.WIND_CHARGE);
        if (!offhand && !loan.select(InventoryUtil.findSlot(Items.WIND_CHARGE, limit(wholeInventory)))) {
            return false;
        }
        InteractionHand hand = offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        RotationManager.whileFacing(player.getYRot(), RotationManager.STRAIGHT_DOWN,
            () -> MC.gameMode.useItem(player, hand).consumesAction());
        thrownAt = player.position();
        blastDueUntil = System.currentTimeMillis() + BLAST_WAIT_MILLIS;
        jumpNext = true;
        return true;
    }

    // Called by the owner at the start of every tick. True on the tick the jump goes off
    // which ends the launch.
    public boolean tick() {
        if (!jumpNext) {
            return false;
        }
        jumpNext = false;
        LocalPlayer player = MC.player;
        // A held jump key jumps anyway and a second jump would add the sprint push twice.
        if (player.onGround() && !player.input.keyPresses.jump()) {
            player.jumpFromGround();
        }
        loan.giveBack();
        // The throw left the server looking at the ground.
        RotationManager.request(player.getYRot(), player.getXRot(), RotationPriority.IDLE);
        return true;
    }

    // True between the throw and the jump.
    public boolean busy() {
        return jumpNext;
    }

    public void stop() {
        jumpNext = false;
        loan.release();
    }
}
