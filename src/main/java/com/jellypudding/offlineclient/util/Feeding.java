package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

// Owns the held use key and the pause after each mouthful. The module only picks what to take and
// when.
public final class Feeding {

    private static final List<Feeding> ALL = new ArrayList<>();

    private final UseHold use = new UseHold();
    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private final Module owner;
    private final BooleanSupplier reselect;
    private final BooleanSupplier pausesCombat;
    private boolean active;
    private boolean landing;
    private int settle;

    public Feeding(Module owner) {
        this(owner, () -> true, () -> false);
    }

    // Whether the slot held before the loan is picked again once it goes home. And whether the
    // combat modules stand back whilst this one feeds.
    public Feeding(Module owner, BooleanSupplier reselect, BooleanSupplier pausesCombat) {
        this.owner = owner;
        this.reselect = reselect;
        this.pausesCombat = pausesCombat;
        ALL.add(this);
    }

    // Whether a module other than the asker holds the use key to feed.
    public static boolean busy(Module asker) {
        return ALL.stream().anyMatch(each -> each.owner != asker && each.busy());
    }

    public static boolean pausingCombat() {
        return ALL.stream().anyMatch(each -> each.busy() && each.pausesCombat.getAsBoolean());
    }

    // True from a throw until the settle after it runs out. The effect may still be on its way
    // and it may be the one another feeder is about to take. The use key and the hotbar are free
    // meanwhile.
    public static boolean bottleLanding() {
        return ALL.stream().anyMatch(each -> each.landing && each.owner.isEnabled());
    }

    private boolean busy() {
        return active && owner.isEnabled();
    }

    public boolean isActive() {
        return active;
    }

    public InventoryUtil.HotbarLoan loan() {
        return loan;
    }

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

    // The hand stays with an item the player is using. A meal begun behind a raised shield could
    // not start and would only take the weapon out of the hand.
    public void begin(int slot) {
        if (OfflineClient.MC.player.isUsingItem() || slot != Inventory.SLOT_OFFHAND && !loan.select(slot)) {
            return;
        }
        active = true;
        use.begin();
    }

    // The use packet carries the angle to the server. True once the throw went.
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

    // False once the use has run its course.
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
