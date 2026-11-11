package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;

import java.util.function.BooleanSupplier;

// A module eating or drinking or throwing a bottle at its feet from its own hotbar loan. Owns
// the held use key and the pause after each mouthful. The module only picks what to take and when.
public final class Feeding {

    private final UseHold use = new UseHold();
    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private final BooleanSupplier reselect;
    private boolean active;
    private boolean landing;
    private int settle;

    public Feeding() {
        this(() -> true);
    }

    // Whether the slot held before the loan is picked again once it goes home.
    public Feeding(BooleanSupplier reselect) {
        this.reselect = reselect;
    }

    public boolean isActive() {
        return active;
    }

    // True from a throw until the settle after it runs out. The effect may still be on its way.
    public boolean isLanding() {
        return landing;
    }

    public InventoryUtil.HotbarLoan loan() {
        return loan;
    }

    // True on a tick the module sits out. A death or the pause after a mouthful.
    public boolean waiting() {
        if (OfflineClient.MC.player.isDeadOrDying()) {
            // Respawn rebuilds the inventory.
            loan.forget();
            stop();
            settle = 0;
            return true;
        }
        if (settle > 0) {
            settle--;
            return true;
        }
        landing = false;
        return false;
    }

    // The offhand needs no swap.
    public void begin(int slot) {
        if (slot != Inventory.SLOT_OFFHAND && !loan.select(slot)) {
            return;
        }
        active = true;
        use.begin();
    }

    // Throws the bottle at the slot down at your own feet and then sits out the settle. The use
    // packet carries the angle to the server. True once the throw went.
    public boolean throwDown(int slot, int settleTicks) {
        if (!loan.select(slot)) {
            return false;
        }
        LocalPlayer player = OfflineClient.MC.player;
        boolean thrown = RotationManager.whileFacing(player.getYRot(), RotationManager.STRAIGHT_DOWN,
            () -> OfflineClient.MC.gameMode.useItem(player, InteractionHand.MAIN_HAND).consumesAction());
        loan.giveBack(reselect.getAsBoolean());
        settle = settleTicks;
        landing = thrown;
        return thrown;
    }

    // Holds the key another tick. False once the use has run its course.
    public boolean tick() {
        return use.tick();
    }

    public void finish(int settleTicks, boolean keepSlot) {
        release();
        settle = settleTicks;
        if (!keepSlot) {
            loan.giveBack(reselect.getAsBoolean());
        }
    }

    public void stop() {
        release();
        landing = false;
        // A loan whose return was refused earlier gets another go.
        loan.giveBack(reselect.getAsBoolean());
    }

    private void release() {
        if (active) {
            active = false;
            use.release();
        }
    }
}
