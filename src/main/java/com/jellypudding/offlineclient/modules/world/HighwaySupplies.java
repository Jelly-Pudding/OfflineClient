package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.PlacedBox;
import com.jellypudding.offlineclient.util.Rebreak;
import com.jellypudding.offlineclient.util.Restock;
import com.jellypudding.offlineclient.util.RotationPriority;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

// Keeps a HighwayBuilder run supplied.
final class HighwaySupplies {

    private static final Minecraft MC = OfflineClient.MC;

    private static final int GRIND_LIMIT = 2400;

    private static final float THROW_PITCH = -25;

    private enum Stage { NONE, GRIND }

    private final BoolSetting restock = new BoolSetting("Restock hotbar",
        "Moves blocks and a pickaxe up from your inventory when the hotbar runs dry.", true);
    private final NumberSetting inventoryDelay = new NumberSetting("Inventory delay",
        "Ticks between one inventory click and the next.", 3, 0, 20, 1, " ticks");
    private final BoolSetting searchShulkers = new BoolSetting("Search shulkers",
        "Sets a shulker box down behind you and takes the build blocks out of it.", true);
    private final BoolSetting searchEnderChest = new BoolSetting("Search ender chest",
        "Sets your ender chest down behind you and takes the build blocks out once the shulkers are empty. A Silk Touch pickaxe gets the chest back.",
        false);
    private final BoolSetting mineEnderChests = new BoolSetting("Mine ender chests",
        "With obsidian on the list and no blocks left an ender chest goes down behind you and is mined for obsidian.",
        true);
    private final NumberSetting saveEnderChests = new NumberSetting("Save ender chests",
        "Ender chests never used below this count.", 2, 0, 64, 1)
        .under(mineEnderChests);
    private final NumberSetting grindAmount = new NumberSetting("Grind amount",
        "How much obsidian one grind gathers before building goes on.", 64, 8, 256, 8).min(1)
        .under(mineEnderChests);
    private final BoolSetting instantGrind = new BoolSetting("Instant rebreak",
        "Breaks every ender chest of a grind after the first at once with a single packet. The first"
            + " is mined the usual way.", false)
        .under(mineEnderChests);
    private final BoolSetting grindBlockade = new BoolSetting("Grind blockade",
        "Walls you in with whatever blocks you have whilst the chests are ground.", true)
        .under(mineEnderChests);
    private final BoolSetting ejectShulkers = new BoolSetting("Eject empty shulkers",
        "Turns round and drops any empty shulker box.", true);
    private final NumberSetting emptySlots = new NumberSetting("Minimum empty slots",
        "Free slots to keep clear after a restock.", 3, 0, 9, 1);
    private final BoolSetting throwTrash = new BoolSetting("Throw out trash",
        "Turns round and drops the listed items to keep room free.", false);
    private final RegistryListSetting<Item> trash = new RegistryListSetting<>("Trash",
        "The items thrown out. Click to pick them.", BuiltInRegistries.ITEM,
        List.of(Items.NETHERRACK, Items.QUARTZ, Items.GOLD_NUGGET, Items.GOLDEN_SWORD,
            Items.GLOWSTONE_DUST, Items.GLOWSTONE, Items.BLACKSTONE, Items.BASALT,
            Items.GHAST_TEAR, Items.SOUL_SAND, Items.SOUL_SOIL, Items.ROTTEN_FLESH,
            Items.MAGMA_BLOCK))
        .under(throwTrash);

    private final SlotSwap slots;
    private final Predicate<Block> allowed;
    private final BooleanSupplier rotatesToPlace;
    private final BooleanSupplier rotatesToMine;
    // A container that would not open stops restocking from containers until the module is switched
    // on again.
    private final Restock containers;

    private int sinceInventory;
    private Stage stage = Stage.NONE;
    private BlockPos grindPos;
    private final Rebreak grindRebreak = new Rebreak();
    private int grindGoal;
    private int stageTicks;

    HighwaySupplies(SlotSwap slots, Predicate<Block> allowed, BooleanSupplier rotatesToPlace,
        BooleanSupplier rotatesToMine) {
        this.slots = slots;
        this.allowed = allowed;
        this.rotatesToPlace = rotatesToPlace;
        this.rotatesToMine = rotatesToMine;
        containers = new Restock(slots, "HighwayBuilder");
    }

    Setting<?>[] settings() {
        return new Setting<?>[] {restock, inventoryDelay, searchShulkers, searchEnderChest, mineEnderChests,
            saveEnderChests, grindAmount, instantGrind, grindBlockade, ejectShulkers, emptySlots, throwTrash, trash};
    }

    boolean grinding() {
        return stage == Stage.GRIND;
    }

    boolean restocking() {
        return containers.active();
    }

    void reset() {
        sinceInventory = 0;
        stage = Stage.NONE;
        grindPos = null;
        containers.reset();
    }

    void end() {
        stage = Stage.NONE;
        grindPos = null;
        containers.end();
    }

    // True when this took the tick.
    boolean tick() {
        if (stage == Stage.GRIND) {
            return enderChestGrind();
        }
        if (containers.active()) {
            return shulkerRestock();
        }
        if (sinceInventory < inventoryDelay.getInt()) {
            sinceInventory++;
            return false;
        }
        if (restock.isOn() && restockHotbar()) {
            return true;
        }
        if (searchShulkers.isOn() && !containers.refused() && shulkerRestock()) {
            return true;
        }
        if (mineEnderChests.isOn() && enderChestGrind()) {
            return true;
        }
        if (searchEnderChest.isOn() && !containers.refused() && enderChestRestock()) {
            return true;
        }
        if (ejectShulkers.isOn() && dropEmptyShulker()) {
            return true;
        }
        return throwTrash.isOn() && dropTrash();
    }

    private boolean shulkerRestock() {
        if (!containers.active()) {
            return startRestock();
        }
        return containers.tick(this::emptyShulker, rotatesToPlace.getAsBoolean(), rotatesToMine.getAsBoolean());
    }

    private boolean startRestock() {
        return setDownContainer(this::usefulShulker);
    }

    private boolean enderChestRestock() {
        return setDownContainer(stack -> stack.is(Items.ENDER_CHEST));
    }

    private boolean setDownContainer(Predicate<ItemStack> which) {
        if (hasBlocksSomewhere() || MC.player.getInventory().getFreeSlot() == -1) {
            return false;
        }
        return containers.setDown(which, this::moveToHotbar, rotatesToPlace.getAsBoolean());
    }

    private boolean enderChestGrind() {
        if (stage == Stage.NONE) {
            if (hasBlocksSomewhere() || !allowed.test(Blocks.OBSIDIAN)
                || enderChests() <= saveEnderChests.getInt()) {
                return false;
            }
            BlockPos spot = PlacedBox.spot();
            if (spot == null) {
                return false;
            }
            grindPos = spot;
            grindGoal = obsidian() + grindAmount.getInt();
            stage = Stage.GRIND;
            stageTicks = 0;
            return true;
        }
        BlockState state = BlockUtil.state(grindPos);
        boolean chestDown = state.is(Blocks.ENDER_CHEST);
        if (++stageTicks > GRIND_LIMIT || obsidian() >= grindGoal
            || (!chestDown && enderChests() <= saveEnderChests.getInt())) {
            endGrind();
            return false;
        }
        if (grindBlockade.isOn() && buildBlockade()) {
            return true;
        }
        if (chestDown) {
            if (!holdPlainPickaxe(state)) {
                ChatUtil.error("HighwayBuilder needs a pickaxe without Silk Touch to grind ender chests.");
                endGrind();
                return false;
            }
            if (instantGrind.isOn() && grindRebreak.strike(grindPos)) {
                return true;
            }
            BlockMiner.mineWithHeld(grindPos, rotatesToMine.getAsBoolean());
            return true;
        }
        BlockMiner.release();
        grindRebreak.emptied();
        if (!BlockUtil.isReplaceable(grindPos)) {
            endGrind();
            return false;
        }
        int slot = InventoryUtil.findSlot(Items.ENDER_CHEST, InventoryUtil.WHOLE_INVENTORY);
        if (slot >= InventoryUtil.HOTBAR_SIZE) {
            return moveToHotbar(slot);
        }
        slots.select(slot);
        BlockUtil.placeAny(grindPos, rotatesToPlace.getAsBoolean(), true);
        return true;
    }

    private void endGrind() {
        stage = Stage.NONE;
        grindPos = null;
        BlockMiner.release();
    }

    private boolean buildBlockade() {
        BlockPos feet = MC.player.blockPosition();
        for (int up = 0; up < 2; up++) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos pos = feet.above(up).relative(side);
                if (pos.equals(grindPos) || !BlockUtil.isReplaceable(pos)) {
                    continue;
                }
                int slot = BlockUtil.findBlockSlot();
                if (slot == -1) {
                    return false;
                }
                slots.select(slot);
                return BlockUtil.placeAny(pos, rotatesToPlace.getAsBoolean(), true);
            }
        }
        return false;
    }

    // Only a pickaxe without Silk Touch breaks a chest into obsidian.
    private boolean holdPlainPickaxe(BlockState state) {
        int slot = ItemUtil.bestToolSlot(state, 1,
            stack -> ItemUtil.enchantLevel(Enchantments.SILK_TOUCH, stack) == 0, InventoryUtil.HOTBAR_SIZE);
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        return true;
    }

    private int enderChests() {
        return InventoryUtil.count(Items.ENDER_CHEST, InventoryUtil.WHOLE_INVENTORY);
    }

    private int obsidian() {
        return InventoryUtil.count(Items.OBSIDIAN, InventoryUtil.WHOLE_INVENTORY);
    }

    private boolean emptyShulker(AbstractContainerMenu menu) {
        if (sinceInventory < inventoryDelay.getInt()) {
            sinceInventory++;
            return true;
        }
        int containerSlots = menu.slots.size() - InventoryUtil.WHOLE_INVENTORY;
        if (containerSlots > 0 && InventoryUtil.emptySlots() > emptySlots.getInt()) {
            for (int i = 0; i < containerSlots; i++) {
                Slot slot = menu.slots.get(i);
                if (!slot.hasItem() || !neededItem(slot.getItem())) {
                    continue;
                }
                MenuClicks.quickMove(menu, i);
                sinceInventory = 0;
                return true;
            }
        }
        return false;
    }

    private boolean hasBlocksSomewhere() {
        return InventoryUtil.findSlot(stack -> stack.getItem() instanceof BlockItem item
            && allowed.test(item.getBlock()), InventoryUtil.WHOLE_INVENTORY) != -1;
    }

    private boolean neededItem(ItemStack stack) {
        return (stack.getItem() instanceof BlockItem item && allowed.test(item.getBlock()))
            || stack.is(ItemTags.PICKAXES) || stack.has(DataComponents.FOOD);
    }

    private boolean usefulShulker(ItemStack stack) {
        return Restock.holds(stack, this::neededItem);
    }

    private boolean dropEmptyShulker() {
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = MC.player.getInventory().getItem(i);
            if (ItemUtil.isShulkerBox(stack.getItem()) && !Restock.holds(stack, any -> true)) {
                throwBehind(i);
                return true;
            }
        }
        return false;
    }

    private boolean restockHotbar() {
        if (BlockUtil.findBlockSlot(allowed) == -1) {
            int from = InventoryUtil.findSlot(stack -> stack.getItem() instanceof BlockItem item
                && allowed.test(item.getBlock()), InventoryUtil.WHOLE_INVENTORY);
            if (from >= InventoryUtil.HOTBAR_SIZE) {
                return moveToHotbar(from);
            }
        }
        if (InventoryUtil.hotbarSlot(stack -> stack.is(ItemTags.PICKAXES)) == -1) {
            int from = InventoryUtil.findSlot(stack -> stack.is(ItemTags.PICKAXES),
                InventoryUtil.WHOLE_INVENTORY);
            if (from >= InventoryUtil.HOTBAR_SIZE) {
                return moveToHotbar(from);
            }
        }
        return false;
    }

    private boolean moveToHotbar(int from) {
        InventoryUtil.swap(InventoryUtil.networkSlot(from), InventoryUtil.networkSlot(spareHotbarSlot()));
        sinceInventory = 0;
        return true;
    }

    // An empty slot first then a tool then trash then the smallest stack of blocks.
    private int spareHotbarSlot() {
        int smallest = -1;
        int smallestCount = Integer.MAX_VALUE;
        int tool = -1;
        int rubbish = -1;
        for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
            ItemStack stack = MC.player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                return i;
            }
            if (trash.contains(stack.getItem())) {
                rubbish = i;
            } else if (stack.isDamageableItem() && !stack.is(ItemTags.PICKAXES)) {
                tool = i;
            } else if (stack.getItem() instanceof BlockItem && stack.getCount() < smallestCount) {
                smallestCount = stack.getCount();
                smallest = i;
            }
        }
        if (tool != -1) {
            return tool;
        }
        if (rubbish != -1) {
            return rubbish;
        }
        return smallest == -1 ? InventoryUtil.HOTBAR_SIZE - 1 : smallest;
    }

    // The biggest trash block stack stays behind for plugging liquid and corners.
    private boolean dropTrash() {
        int keep = -1;
        int keepCount = 0;
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = MC.player.getInventory().getItem(i);
            if (stack.getItem() instanceof BlockItem && trash.contains(stack.getItem())
                && stack.getCount() > keepCount) {
                keepCount = stack.getCount();
                keep = i;
            }
        }
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            ItemStack stack = MC.player.getInventory().getItem(i);
            if (i == keep || stack.isEmpty() || !trash.contains(stack.getItem())) {
                continue;
            }
            throwBehind(i);
            return true;
        }
        return false;
    }

    // The server drops a thrown stack the way it last saw you face. Facing back and up keeps the
    // pile off the line ahead where the walk would pick it up again. The look goes out first.
    private void throwBehind(int inventoryIndex) {
        FaceMode.SPAM.faceExact(MC.player.getYRot() + 180, THROW_PITCH, RotationPriority.IDLE);
        InventoryUtil.throwStack(InventoryUtil.networkSlot(inventoryIndex));
        sinceInventory = 0;
    }
}
