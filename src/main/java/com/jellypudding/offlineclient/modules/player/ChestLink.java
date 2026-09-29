package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.WorldWatch;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

// Keeps a container open after its screen goes. The inventory key brings the screen back from
// anywhere the server still lets you reach it. Your best items can go in when you are about to die.
public final class ChestLink extends Module {

    public enum Kind { ENDER_CHEST, ANY }

    // The server closes a container once the eyes are this much further than block reach from it.
    private static final double CONTAINER_SLACK = 4;
    // The link lets go this far inside the server's limit. A click the server gets after it closed
    // the menu is ignored and the screen would show items that never moved.
    private static final double LET_GO = 1;
    // The warning comes this many blocks before the link lets go.
    private static final int WARN_BLOCKS = 2;
    // A screen that opens this soon after a right click belongs to the block clicked.
    private static final int OPEN_WINDOW = 20;

    private final EnumSetting<Kind> kind = new EnumSetting<>("Containers",
        "Which containers stay open when you close them.", Kind.ENDER_CHEST)
        .describe(Kind.ENDER_CHEST, "Only your ender chest.")
        .describe(Kind.ANY, "Any chest or barrel or shulker box or other storage. Each closes past the same reach.");
    private final KeybindSetting showKey = new KeybindSetting("Show key",
        "Shows or hides the container kept open. The inventory key shows it as well.",
        KeybindSetting.UNBOUND);
    private final BoolSetting escapeHides = new BoolSetting("Escape hides",
        "Escape tucks the container away as well. Off lets Escape close it for good.", true);
    private final BoolSetting rangeWarning = new BoolSetting("Range warning",
        "Warns in chat when two blocks are left before the container closes.", true);
    private final BoolSetting save = new BoolSetting("Save items",
        "Moves items into the kept container when you are about to die.", true);
    private final NumberSetting health = new NumberSetting("Health",
        "Saves once you drop to this many hearts.", 2, 0, 10, 0.5, " hearts")
        .under(save);
    private final BoolSetting unlessTotem = new BoolSetting("Unless totem",
        "Waits whilst you hold a totem of undying.", true)
        .under(save);
    private final BoolSetting beforeHits = new BoolSetting("Before lethal hits",
        "Counts a hit already on its way such as a crystal in range against your health.", true)
        .under(save);
    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "Saved first. Click to pick them.", BuiltInRegistries.ITEM, savedFirst())
        .under(save);
    private final BoolSetting everything = new BoolSetting("Everything",
        "Then saves the rest of your backpack and hotbar too.", false)
        .under(save);
    private final KeybindSetting saveKey = new KeybindSetting("Save key",
        "Saves the items at once.", KeybindSetting.UNBOUND)
        .under(save);

    // The screen of the container kept open. Null whilst none is.
    private AbstractContainerScreen<?> kept;
    private int keptId;
    // Where the kept container stands. Null for one opened without a block such as by a command.
    private BlockPos keptAt;
    // True whilst the kept screen was up at the last tick.
    private boolean showing;
    private boolean told;
    private boolean warned;
    // Set once a save has run. It waits for the danger to pass before another.
    private boolean saved;

    // The block right clicked last and the tick it happened. A screen that opens soon after is its own.
    private BlockPos clicked;
    private int clickedTick;
    private int openedId = -1;
    private BlockPos openedAt;
    private int ticks;
    private final WorldWatch world = new WorldWatch();

    public ChestLink() {
        super("ChestLink", "Keeps your ender chest open after you close it and shows it again on a key.",
            Category.PLAYER);
        addSettings(kind, showKey, escapeHides, rangeWarning, save, health, unlessTotem, beforeHits, items,
            everything, saveKey);
        searchTags("echest", "ender chest", "portable chest");
    }

    // Netherite gear and the elytra and totems and golden apples and every shulker box.
    private static List<Item> savedFirst() {
        List<Item> first = new ArrayList<>(List.of(Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE,
            Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS, Items.NETHERITE_SWORD, Items.NETHERITE_AXE,
            Items.NETHERITE_PICKAXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE, Items.NETHERITE_SPEAR,
            Items.MACE, Items.ELYTRA, Items.TOTEM_OF_UNDYING, Items.ENCHANTED_GOLDEN_APPLE));
        BuiltInRegistries.ITEM.stream().filter(ItemUtil::isShulkerBox).forEach(first::add);
        return first;
    }

    @Override
    public String getSuffix() {
        if (kept == null) {
            return null;
        }
        return keptAt == null ? "kept" : String.format(Locale.ROOT, "%.1f blocks", Math.max(0, blocksLeft()));
    }

    @Override
    protected void onEnable() {
        told = false;
        world.accept();
    }

    // The kept container closes on both sides.
    @Override
    protected void onDisable() {
        release();
    }

    // Shows the kept ender chest. False whilst none is kept. A command runs inside the chat
    // screen which shuts itself straight after and the screen has to wait for that.
    public boolean showEnderChest() {
        AbstractContainerScreen<?> shown = kept;
        if (shown == null || !InventoryUtil.isEnderChest(shown) || !inGame()) {
            return false;
        }
        mc.schedule(() -> {
            if (kept == shown && inGame()) {
                mc.gui.setScreen(shown);
            }
        });
        return true;
    }

    @Subscribe
    private void onRightClick(RightClickEvent event) {
        BlockHitResult aimed = BlockUtil.aimedBlock();
        if (aimed != null) {
            clicked = aimed.getBlockPos();
            clickedTick = ticks;
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        ticks++;
        if (world.changed()) {
            forget();
        }
        noteOpened();
        if (kept != null) {
            watchLink();
        }
        if (kept != null && save.isOn()) {
            guard();
        }
        quickOpen();
        showing = kept != null && mc.gui.screen() == kept;
    }

    // Remembers which block the newest container screen came from.
    private void noteOpened() {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        int id = screen.getMenu().containerId;
        if (id != openedId) {
            openedId = id;
            openedAt = clicked != null && ticks - clickedTick <= OPEN_WINDOW ? clicked : null;
        }
    }

    // The server closed it or another container took its place. A close you watched needs no word.
    private void watchLink() {
        if (mc.player.containerMenu.containerId != keptId) {
            if (!showing) {
                ChatUtil.error("Your " + keptName() + " closed.");
            }
            forget();
            return;
        }
        if (keptAt == null) {
            return;
        }
        double left = blocksLeft();
        if (left < 0) {
            ChatUtil.error("Your " + keptName() + " closed. You walked out of reach.");
            release();
        } else if (left < WARN_BLOCKS && rangeWarning.isOn() && !warned) {
            warned = true;
            ChatUtil.error(WARN_BLOCKS + " more blocks and your " + keptName() + " closes.");
        } else if (left >= WARN_BLOCKS) {
            warned = false;
        }
    }

    // How much further you may walk before the link lets go. The server measures from the eyes to
    // the nearest point of the block. A double chest closes once either half is out of reach.
    private double blocksLeft() {
        double reach = mc.player.blockInteractionRange() + CONTAINER_SLACK - LET_GO;
        double far = distanceTo(keptAt);
        BlockPos other = BlockUtil.otherChestHalf(keptAt, BlockUtil.state(keptAt));
        if (other != null) {
            far = Math.max(far, distanceTo(other));
        }
        return reach - far;
    }

    private double distanceTo(BlockPos pos) {
        return Math.sqrt(new AABB(pos).distanceToSqr(mc.player.getEyePosition()));
    }

    // Keys reach here before the screen does. Taking one keeps the screen from closing the menu.
    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.getAction() == InputConstants.RELEASE || !inGame()) {
            return;
        }
        int key = event.getKey();
        boolean inventoryKey = InputUtil.isKey(mc.options.keyInventory, key);
        boolean ownKey = showKey.isBound() && key == showKey.getValue();
        boolean escape = key == InputConstants.KEY_ESCAPE && escapeHides.isOn();
        Screen screen = mc.gui.screen();
        if (event.getAction() == InputConstants.REPEAT) {
            // A held key repeats. The screen would close the kept menu for good and with no
            // screen the inventory key would open your own inventory over it.
            if (kept != null && (screen == null || screen == kept) && (inventoryKey || ownKey || escape)) {
                event.cancel();
            }
            return;
        }
        if (saveKey.isBound() && key == saveKey.getValue() && kept != null) {
            saveItems();
            return;
        }
        if (screen == null) {
            if (kept != null && (inventoryKey || ownKey)) {
                mc.gui.setScreen(kept);
                event.cancel();
            }
            return;
        }
        if ((inventoryKey || ownKey || escape) && screen instanceof AbstractContainerScreen<?> container
            && keepable(container)) {
            event.cancel();
            // An item on the cursor would stay there out of sight. It has to be put down first.
            if (container.getMenu().getCarried().isEmpty()) {
                hide(container);
            }
        }
    }

    private boolean keepable(AbstractContainerScreen<?> screen) {
        if (screen.getMenu() != mc.player.containerMenu) {
            return false;
        }
        return screen == kept
            || (kind.is(Kind.ANY) ? InventoryUtil.isStorage(screen) : InventoryUtil.isEnderChest(screen));
    }

    // The screen goes and the menu stays. The server never hears of it.
    private void hide(AbstractContainerScreen<?> screen) {
        if (screen != kept) {
            kept = screen;
            keptId = screen.getMenu().containerId;
            keptAt = keptId == openedId ? openedAt : null;
            warned = false;
            saved = false;
            if (!told) {
                told = true;
                ChatUtil.message("§bChestLink §7keeps your " + keptName() + " open. Press §f"
                    + mc.options.keyInventory.getTranslatedKeyMessage().getString() + "§7 to see it again.");
            }
        }
        mc.gui.setScreen(null);
    }

    private String keptName() {
        return kept != null && InventoryUtil.isEnderChest(kept) ? "ender chest" : "container";
    }

    // Saves once each time the danger comes. A totem in hand saves you instead.
    private void guard() {
        boolean totem = mc.player.getMainHandItem().is(Items.TOTEM_OF_UNDYING)
            || mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        float left = beforeHits.isOn() ? DamageUtil.healthAfterIncoming() : EntityUtil.totalHealth(mc.player);
        boolean danger = left <= health.getValue() * EntityUtil.HEART && !(unlessTotem.isOn() && totem);
        if (!danger) {
            saved = false;
        } else if (!saved) {
            saved = true;
            saveItems();
        }
    }

    // The listed items go in first and with Everything on the rest after them. Each stack goes by one
    // shift click and only while the container has room for it. A hopper or dropper would pass
    // your gear on and gets none.
    private void saveItems() {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu.containerId != keptId || !menu.getCarried().isEmpty() || !InventoryUtil.keeps(menu)) {
            return;
        }
        int moved = saveWhere(menu, stack -> items.contains(stack.getItem()));
        if (everything.isOn()) {
            moved += saveWhere(menu, stack -> true);
        }
        if (moved > 0) {
            ChatUtil.message("§bChestLink §7saved §f" + moved + "§7 stacks into your " + keptName() + ".");
        }
    }

    private int saveWhere(AbstractContainerMenu menu, Predicate<ItemStack> wanted) {
        List<Integer> theirs = MenuClicks.containerSlots(menu);
        int moved = 0;
        for (int slot : MenuClicks.inventorySlots(menu)) {
            ItemStack stack = menu.slots.get(slot).getItem();
            if (!stack.isEmpty() && wanted.test(stack) && MenuClicks.room(menu, theirs, stack) > 0) {
                MenuClicks.quickMove(menu, slot);
                moved++;
            }
        }
        return moved;
    }

    // Holding use as the aim lands on an ender chest opens it on the next tick. The game would
    // otherwise wait out the pause between repeated right clicks first.
    private void quickOpen() {
        if (kept != null || mc.gui.screen() != null || mc.player.isUsingItem()
            || !InputUtil.physicallyHeld(mc.options.keyUse) || ticks - clickedTick <= OPEN_WINDOW) {
            return;
        }
        BlockHitResult aimed = BlockUtil.aimedBlock();
        if (aimed != null && BlockUtil.state(aimed.getBlockPos()).getBlock() instanceof EnderChestBlock) {
            InputUtil.capUseDelay(0);
        }
    }

    // Closes the kept container on both sides. A screen of something else stays open.
    private void release() {
        if (kept != null && inGame() && mc.player.containerMenu.containerId == keptId) {
            if (mc.gui.screen() == kept) {
                mc.player.closeContainer();
            } else {
                mc.player.connection.send(new ServerboundContainerClosePacket(keptId));
                mc.player.containerMenu = mc.player.inventoryMenu;
            }
        }
        forget();
    }

    private void forget() {
        kept = null;
        keptAt = null;
        showing = false;
        warned = false;
        saved = false;
    }
}
