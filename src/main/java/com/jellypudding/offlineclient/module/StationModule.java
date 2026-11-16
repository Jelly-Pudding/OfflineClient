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

// The shape shared by the modules that work a station such as a crafting table or an anvil.
// Whilst its screen is open the module makes one round of clicks at a steady pace until the
// job is done or the materials run out. A round only starts with nothing on the cursor.
public abstract class StationModule<M extends AbstractContainerMenu> extends Module {

    // Ticks a round waits at most for the server to answer the one before.
    private static final int PATIENCE = 20;

    // Ticks the server gets to show what a module asked for before the module gives up.
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

    // One round of clicks on the open station.
    protected abstract void work(M menu);

    // Drops progress tied to the last station. Runs when it closes or another opens and when
    // the module is switched on.
    protected void closed() {
    }

    // Runs every tick whilst no station of the module's kind is open. A module that opens
    // its station by itself reaches out for it here.
    protected void away() {
    }

    // True whilst the module may reach out for a station by itself. A meal and ShulkerPacker
    // and an item in use come first and nothing is opened over another screen. A fight comes
    // first too unless the module is there to serve one.
    protected final boolean handsFree(boolean inFights) {
        return inGame() && mc.gui.screen() == null && !Hands.tied() && !mc.player.isUsingItem()
            && !Hands.slotsTaken() && (inFights || !Modules.fighting());
    }

    @Override
    protected void onEnable() {
        menu = null;
        closed();
    }

    // Ticks the module has run for. Unlike the player's own count it survives a respawn.
    protected final int ticks() {
        return ticks;
    }

    // Skips rounds until the server sends the menu something new or the patience runs out.
    protected final void awaitServer(M menu) {
        awaitedState = menu.getStateId();
        awaitTicks = PATIENCE;
    }

    // Starts the clock that stalled reads. Opening a station starts it too.
    protected final void startClock() {
        clockStart = ticks;
    }

    // True once the server has had its time to show what the module last asked for.
    protected final boolean stalled() {
        return ticks - clockStart >= STALL_TICKS;
    }

    // Shift clicks a station slot into the inventory. When nothing moves the module cannot go
    // on and the message ends with what the move was for.
    protected final boolean moveOut(M menu, int slot, String what) {
        if (MenuClicks.quickMoved(menu, slot)) {
            return true;
        }
        outOfRoom("No room in the inventory " + what);
        return false;
    }

    // The inventory had no room for a move. The module stops and says why unless it knows a
    // better way to end its work.
    protected void outOfRoom(String reason) {
        disable(reason);
    }

    // Takes a finished stack out of a station slot. It goes into the inventory or onto the
    // ground whilst the module's Throw out row is ticked. False once the module could not go on.
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

    // Counts what one shift click on a result made. The server crafts again and again whilst
    // this side only predicts once. Settle it after the server has answered.
    public static final class Takings {

        private Item item;
        private int before;

        public void start(Item item) {
            this.item = item;
            before = InventoryUtil.count(item, InventoryUtil.WHOLE_INVENTORY);
        }

        // How many arrived since the start. Nought when nothing was started.
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
