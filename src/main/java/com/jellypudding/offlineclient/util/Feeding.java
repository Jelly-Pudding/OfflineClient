package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.world.entity.player.Inventory;

import java.util.function.BooleanSupplier;

// A module eating or drinking from its own hotbar loan. Owns the held use key and the
// pause after each mouthful. The module only picks what to take and when.
public final class Feeding {

    private final UseHold use = new UseHold();
    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private final BooleanSupplier reselect;
    private boolean active;
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

    public void pause(int ticks) {
        settle = ticks;
    }

    public void stop() {
        release();
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
