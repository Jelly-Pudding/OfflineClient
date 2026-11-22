package com.jellypudding.offlineclient.module;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ThrowOut;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;

// A round only starts with nothing on the cursor.
public abstract class StationModule<M extends AbstractContainerMenu> extends Module {

    private static final int PATIENCE = 20;

    private static final int STALL_TICKS = 40;

    private final Class<M> menuType;
    private final int pace;

    private M menu;
    private int ticks;
    private int wait;
    private int awaitedState;
    private int awaitTicks;
    private int clockStart;

    protected StationModule(String name, String description, Class<M> menuType, int pace) {
        super(name, description, Category.WORLD);
        this.menuType = menuType;
        this.pace = pace;
    }

    protected abstract void work(M menu);

    // Runs when the station closes or another opens and when the module is switched on.
    protected void closed() {
    }

    // A module that opens its station by itself reaches out for it here.
    protected void away() {
    }

    // A meal and ShulkerPacker and an item in use come first and nothing is opened over another
    // screen.
    protected final boolean handsFree(boolean inFights) {
        return inGame() && mc.gui.screen() == null && !Hands.tied() && !mc.player.isUsingItem()
            && !Hands.slotsTaken() && (inFights || !Modules.fighting());
    }

    @Override
    protected void onEnable() {
        menu = null;
        closed();
    }

    // Unlike the player's own count it survives a respawn.
    protected final int ticks() {
        return ticks;
    }

    protected final void awaitServer(M menu) {
        awaitedState = menu.getStateId();
        awaitTicks = PATIENCE;
    }

    protected final void startClock() {
        clockStart = ticks;
    }

    protected final boolean stalled() {
        return ticks - clockStart >= STALL_TICKS;
    }

    protected final boolean moveOut(M menu, int slot, String what) {
        if (MenuClicks.quickMoved(menu, slot)) {
            return true;
        }
        outOfRoom("No room in the inventory " + what);
        return false;
    }

    protected void outOfRoom(String reason) {
        disable(reason);
    }

    protected final boolean takeFinished(ThrowOut out, M menu, int slot, String what) {
        if (!out.isOn()) {
            return moveOut(menu, slot, what);
        }
        out.from(menu, slot);
        return true;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        ticks++;
        M open = openMenu();
        if (open != menu) {
            if (menu != null) {
                closed();
            }
            menu = open;
            wait = 0;
            awaitTicks = 0;
            clockStart = ticks;
        }
        if (open == null) {
            away();
            return;
        }
        if (awaiting(open) || --wait > 0 || !open.getCarried().isEmpty()) {
            return;
        }
        wait = pace;
        work(open);
    }

    private boolean awaiting(M open) {
        if (awaitTicks <= 0) {
            return false;
        }
        if (open.getStateId() != awaitedState) {
            awaitTicks = 0;
            return false;
        }
        awaitTicks--;
        return true;
    }

    private M openMenu() {
        if (mc.gui.screen() instanceof AbstractContainerScreen<?> screen && menuType.isInstance(screen.getMenu())) {
            return menuType.cast(screen.getMenu());
        }
        return null;
    }

    // The server crafts again and again whilst this side only predicts once. Settle it after the
    // server has answered.
    public static final class Takings {

        private Item item;
        private int before;

        public void start(Item item) {
            this.item = item;
            before = InventoryUtil.count(item, InventoryUtil.WHOLE_INVENTORY);
        }

        public int settle() {
            if (item == null) {
                return 0;
            }
            int made = Math.max(0, InventoryUtil.count(item, InventoryUtil.WHOLE_INVENTORY) - before);
            item = null;
            return made;
        }

        public void forget() {
            item = null;
        }
    }
}
