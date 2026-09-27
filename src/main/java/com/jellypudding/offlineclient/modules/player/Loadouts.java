package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.config.LoadoutStore;
import com.jellypudding.offlineclient.config.LoadoutStore.Loadout;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.combat.AutoTotem;
import com.jellypudding.offlineclient.modules.combat.Offhand;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

// Puts the inventory back into a layout saved with the loadout command. Each move brings
// the biggest stack of the right kind into the first slot that holds the wrong thing.
public final class Loadouts extends Module {

    // The order slots are filled in. The hotbar and the offhand matter most in a fight.
    private static final int[] ORDER = IntStream.concat(
        IntStream.concat(IntStream.range(0, InventoryUtil.HOTBAR_SIZE), IntStream.of(Inventory.SLOT_OFFHAND)),
        IntStream.range(InventoryUtil.MAIN_START, InventoryUtil.WHOLE_INVENTORY)).toArray();

    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between moves. Zero moves everything at once.", 1, 0, 10, 1, " ticks").min(0);

    private Loadout restoring;
    private int timer;

    // A restore carries on whether the module is on or off. The module only answers the keys.
    private final Object restorer = new Object() {
        @Subscribe
        private void onTick(TickEvent event) {
            restoreTick();
        }
    };

    public Loadouts() {
        super("Loadouts", "Puts your inventory back into a layout saved with the loadout command.",
            Category.PLAYER);
        addSettings(delay);
        searchTags("kit", "layout", "hotbar", "crystal");
    }

    @Override
    public String getSuffix() {
        return restoring == null ? null : restoring.name();
    }

    // Every filled slot of the inventory as the kind of item in it.
    public Map<Integer, String> currentLayout() {
        Map<Integer, String> layout = new TreeMap<>();
        for (int index : ORDER) {
            String kind = LoadoutStore.kindOf(mc.player.getInventory().getItem(index));
            if (kind != null) {
                layout.put(index, kind);
            }
        }
        return layout;
    }

    public void restore(Loadout loadout) {
        restoring = loadout;
        timer = 0;
        watch(restorer);
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.getAction() != InputConstants.PRESS || mc.gui.screen() != null || !inGame()) {
            return;
        }
        List<Loadout> bound = LoadoutStore.get().boundTo(event.getKey());
        if (!bound.isEmpty()) {
            restore(bound.getFirst());
        }
    }

    private void restoreTick() {
        if (!inGame() || mc.player.isDeadOrDying() || restoring == null) {
            stop();
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        // A container or a stack on the cursor waits until it is gone.
        if (!InventoryUtil.cursorFree()) {
            return;
        }
        int moves = delay.getInt() == 0 ? ORDER.length : 1;
        for (int i = 0; i < moves; i++) {
            if (!moveOne()) {
                finish();
                return;
            }
        }
        timer = delay.getInt();
    }

    // Fills the first slot that holds the wrong thing. False once no slot can be put right.
    private boolean moveOne() {
        for (int target : ORDER) {
            String wanted = restoring.slots().get(target);
            if (wanted == null || !mine(target) || wanted.equals(kindAt(target))) {
                continue;
            }
            int source = sourceFor(wanted, target);
            if (source != -1) {
                MenuClicks.swap(mc.player.inventoryMenu, InventoryUtil.networkSlot(source),
                    InventoryUtil.networkSlot(target));
                return true;
            }
        }
        return false;
    }

    // The biggest stack of the kind that is not already sitting where the loadout wants it.
    private int sourceFor(String wanted, int target) {
        int best = -1;
        int bestCount = 0;
        for (int index : ORDER) {
            if (index == target || !mine(index) || !wanted.equals(kindAt(index))
                || wanted.equals(restoring.slots().get(index))) {
                continue;
            }
            int count = mc.player.getInventory().getItem(index).getCount();
            if (count > bestCount) {
                best = index;
                bestCount = count;
            }
        }
        return best;
    }

    // AutoTotem and Offhand keep the offhand themselves. A restore that moved it too would
    // trade it back and forth with them for ever. It stays out whilst either holds it.
    private static boolean mine(int index) {
        if (index != Inventory.SLOT_OFFHAND) {
            return true;
        }
        AutoTotem autoTotem = Modules.get(AutoTotem.class);
        return !Modules.enabled(Offhand.class) && (autoTotem == null || !autoTotem.isLocked());
    }

    private String kindAt(int index) {
        return LoadoutStore.kindOf(mc.player.getInventory().getItem(index));
    }

    private void finish() {
        long missing = restoring.slots().entrySet().stream()
            .filter(slot -> mine(slot.getKey()) && !slot.getValue().equals(kindAt(slot.getKey())))
            .count();
        String name = "§b" + restoring.name() + " §7is in place";
        ChatUtil.message(missing == 0 ? name + "." : name + " apart from §b" + missing
            + " §7slots you have nothing for.");
        stop();
    }

    private void stop() {
        restoring = null;
        unwatch(restorer);
    }
}
