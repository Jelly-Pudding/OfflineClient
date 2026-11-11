package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.MenuScript;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.TextLines;
import com.jellypudding.offlineclient.util.WorkedScreens;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

// Plays a list of clicks the player wrote each time a menu opens that the rules below pick out.
// A server names its menus as it likes and a title is only ever read as plain words.
public final class MenuClicker extends Module {

    // Ticks a menu gets for its items to arrive before the rules are checked without them.
    private static final int CONTENTS_WAIT = 20;

    // Letters of a title that are read. A server can send a far longer one.
    private static final int MOST_TITLE = 256;

    private final RegistryListSetting<MenuType<?>> menus = new RegistryListSetting<>("Menus",
        "The kinds of menu the steps play in. Leave it empty for every kind.", BuiltInRegistries.MENU,
        List.of());
    private final TextSetting title = new TextSetting("Title",
        "Words the title of the menu has to hold. Capital letters make no difference."
            + " Leave it empty for any title.", "");
    private final BoolSetting checkSlot = new BoolSetting("Check a slot",
        "The steps only play when one slot of the menu holds a chosen item as it opens.", false);
    private final NumberSetting slot = new NumberSetting("Slot",
        "The number of the slot that is checked. MenuTools can write the numbers over the slots.",
        0, 0, 53, 1)
        .under(checkSlot);
    private final RegistryListSetting<Item> holds = new RegistryListSetting<>("Holds",
        "The items that slot may hold. Click to pick them.", BuiltInRegistries.ITEM, List.of())
        .under(checkSlot);
    private final TextLines steps = TextLines.named("Steps", "Step",
        "How many steps the list has. They play from the first to the last.",
        "One step. " + MenuScript.HELP);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between two clicks.", 2, 0, 20, 1, " ticks");
    private final BoolSetting repeat = new BoolSetting("Repeat",
        "Starts again from the first step for as long as the menu stays open.", false);
    private final NumberSetting pause = new NumberSetting("Pause",
        "A matching menu that opens within this many seconds of the last click is passed over."
            + " Many shops open their menu again after each click.", 3, 0, 30, 1, " seconds");

    // The menu the steps were last decided for. Null whilst none is open.
    private AbstractContainerMenu seen;
    private boolean decided;
    private int waited;
    // What is left to play in the open menu. Null when its steps are done or it was passed over.
    private Deque<MenuScript.Action> queue;
    private int wait;
    // Ticks the module has run for and the tick of the last click the steps made. Minus one
    // until the steps click.
    private int ticks;
    private int lastClick;
    // True once the player heard why a menu was passed over. The next click clears it.
    private boolean toldPause;

    public MenuClicker() {
        super("MenuClicker", "Plays a list of clicks you write whenever a menu you picked opens.",
            Category.PLAYER);
        addSettings(menus, title, checkSlot, slot, holds);
        addSettings(steps.settings());
        addSettings(delay, repeat, pause);
        searchTags("slot click", "menu macro", "gui script", "kit menu", "shop", "auto click menu");
    }

    @Override
    protected void onEnable() {
        seen = null;
        queue = null;
        lastClick = -1;
        toldPause = false;
        if (unfiltered()) {
            disable("Pick a kind of menu or type a title or check a slot first. The steps would play"
                + " in every menu you open.");
            return;
        }
        if (checkSlot.isOn() && holds.size() == 0) {
            disable("Pick the items the checked slot may hold first.");
            return;
        }
        MenuScript script = script();
        if (script != null && script.isEmpty()) {
            disable("Write at least one step first.");
        } else if (script == null) {
            setEnabled(false);
        }
    }

    private boolean unfiltered() {
        return menus.size() == 0 && title.isBlank() && !checkSlot.isOn();
    }

    // The steps as they are written this moment. Null after telling the player which one
    // cannot be read.
    private MenuScript script() {
        MenuScript script = MenuScript.parse(steps.all());
        if (script.unread() == null) {
            return script;
        }
        ChatUtil.error("MenuClicker cannot read the step " + script.unread()
            + ". Rest the pointer on a step row to see how one is written.");
        return null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        ticks++;
        AbstractContainerMenu menu = openMenu();
        if (menu != seen) {
            seen = menu;
            decided = false;
            waited = 0;
            queue = null;
        }
        // ShulkerPacker and our own helpers work the screens they open themselves.
        if (menu == null || Modules.packing() || WorkedScreens.showing()) {
            return;
        }
        if (!decided) {
            // The items follow the screen in a packet of their own and the menu counts from nought until then.
            if (menu.getStateId() == 0 && ++waited < CONTENTS_WAIT) {
                return;
            }
            decided = true;
            wait = 0;
            queue = matches(menu) && !tooSoon() ? fresh(menu) : null;
        }
        if (queue == null || wait-- > 0) {
            return;
        }
        play(menu);
    }

    // The menu of a container screen. Your own inventory is no menu a server opens.
    private AbstractContainerMenu openMenu() {
        if (!inGame() || !InventoryUtil.containerOpen()) {
            return null;
        }
        return ((AbstractContainerScreen<?>) mc.gui.screen()).getMenu();
    }

    private boolean matches(AbstractContainerMenu menu) {
        if (unfiltered() || menus.size() > 0 && (menu.menuType == null || !menus.contains(menu.menuType))) {
            return false;
        }
        String wanted = title.getValue().strip().toLowerCase(Locale.ROOT);
        String shown = mc.gui.screen().getTitle().getString(MOST_TITLE).toLowerCase(Locale.ROOT);
        if (!shown.contains(wanted)) {
            return false;
        }
        if (!checkSlot.isOn()) {
            return true;
        }
        int index = slot.getInt();
        return index < menu.slots.size() && holds.contains(menu.slots.get(index).getItem().getItem());
    }

    // True whilst the last click is too recent for the steps to play in a new menu. The player
    // hears of it once until the steps click again.
    private boolean tooSoon() {
        if (lastClick < 0 || ticks - lastClick > pauseTicks()) {
            return false;
        }
        if (!toldPause) {
            toldPause = true;
            tell("passes over a menu that opens within §f"
                + ChatUtil.duration(pause.getValue()) + " §7of its last click.");
        }
        return true;
    }

    private int pauseTicks() {
        return (int) Math.round(pause.getValue() * SharedConstants.TICKS_PER_SECOND);
    }

    // Null when a step cannot be read or there is nothing to play.
    private Deque<MenuScript.Action> fresh(AbstractContainerMenu menu) {
        MenuScript script = script();
        List<MenuScript.Action> actions = script == null ? List.of() : script.actionsFor(menu.slots.size());
        return actions.isEmpty() ? null : new ArrayDeque<>(actions);
    }

    // One step a tick. A click is followed by the delay and a wait by its own ticks.
    private void play(AbstractContainerMenu menu) {
        switch (queue.poll()) {
            case MenuScript.Click click -> {
                // The game crashes on a click past the last slot.
                if (click.slot() < menu.slots.size()) {
                    MenuClicks.click(menu, click.slot(), click.button(), click.kind());
                    lastClick = ticks;
                    toldPause = false;
                }
                wait = delay.getInt();
            }
            case MenuScript.Wait step -> wait = step.ticks();
            case MenuScript.Close _ -> {
                mc.player.closeContainer();
                queue = null;
                return;
            }
        }
        if (queue.isEmpty()) {
            queue = repeat.isOn() ? fresh(menu) : null;
            // A list with no click in it would otherwise start over every tick.
            wait = Math.max(wait, 1);
        }
    }
}
