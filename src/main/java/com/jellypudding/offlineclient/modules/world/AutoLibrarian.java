package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rerolls a fresh librarian's book trade by breaking and replacing its
 * lectern until it offers a book from the wanted list.
 */
public final class AutoLibrarian extends Module {

    private static final int VILLAGER_COLOR = 0xFF30E030;
    private static final int LECTERN_COLOR = 0xFF40C0FF;
    private static final int SPENT_COLOR = 0xFFE03030;

    // Trade slots of the merchant screen.
    private static final int RESULT_SLOT = 2;

    private enum Stage { FIND_VILLAGER, FIND_LECTERN, OPEN_TRADE, READ_TRADE, BREAK_LECTERN, PLACE_LECTERN }

    private final TextSetting wanted = new TextSetting("Wanted books",
        "Enchantments separated by spaces. A colon and a number sets the lowest level. Example: mending unbreaking:3",
        "mending unbreaking:3 sharpness:5 protection:4 efficiency:5 fortune:3 looting:3 silk_touch");
    private final NumberSetting maxPrice = new NumberSetting("Max price",
        "The most emeralds a wanted book may cost.", 64, 1, 64, 1, " emeralds").max(64);
    private final BoolSetting lockIn = new BoolSetting("Lock in",
        "Buys the book once and the villager keeps the trade. Needs emeralds and paper or a book.", false);
    private final NumberSetting range = new NumberSetting("Range",
        "How far the villager and its lectern may be.", 5, 1, 6, 0.1).max(6);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn toward the villager and lectern on the server side.", true);

    private Stage stage;
    private Villager villager;
    private BlockPos lectern;
    private final Set<Integer> spent = new HashSet<>();
    private final SlotSwap slots = new SlotSwap();
    private int cooldown;

    public AutoLibrarian() {
        super("AutoLibrarian", "Rerolls a librarian until it sells a book you want.", Category.WORLD);
        addSettings(wanted, maxPrice, lockIn, range, rotate);
        searchTags("villager trainer", "enchanted book", "lectern");
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        return stage == null ? null : switch (stage) {
            case FIND_VILLAGER, FIND_LECTERN -> "searching";
            case OPEN_TRADE, READ_TRADE -> "checking";
            case BREAK_LECTERN, PLACE_LECTERN -> "rerolling";
        };
    }

    @Override
    protected void onEnable() {
        stage = Stage.FIND_VILLAGER;
        villager = null;
        lectern = null;
        spent.clear();
        cooldown = 0;
    }

    @Override
    protected void onDisable() {
        BlockMiner.release();
        slots.restoreIfMine();
        villager = null;
        lectern = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        switch (stage) {
            case FIND_VILLAGER -> findVillager();
            case FIND_LECTERN -> findLectern();
            case OPEN_TRADE -> openTrade();
            case READ_TRADE -> readTrade();
            case BREAK_LECTERN -> breakLectern();
            case PLACE_LECTERN -> placeLectern();
        }
    }

    // A level one librarian that has not traded yet. Its trades can still change.
    private void findVillager() {
        Villager best = (Villager) EntityUtil.nearest(range.getValue(), this::trainable);
        if (best == null) {
            ChatUtil.error("No fresh librarian in reach."
                + (spent.isEmpty() ? "" : " " + spent.size() + " nearby already traded."));
            setEnabled(false);
            return;
        }
        villager = best;
        stage = Stage.FIND_LECTERN;
    }

    // A living level one librarian that has not traded yet.
    private boolean trainable(Entity entity) {
        if (!(entity instanceof Villager candidate) || !candidate.isAlive() || spent.contains(candidate.getId())) {
            return false;
        }
        return candidate.getVillagerData().profession().is(VillagerProfession.LIBRARIAN)
            && candidate.getVillagerData().level() == 1;
    }

    private void findLectern() {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (!BlockUtil.state(pos).is(Blocks.LECTERN)) {
                continue;
            }
            double distance = villager.distanceToSqr(Vec3.atCenterOf(pos));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        if (best == null) {
            ChatUtil.error("No lectern in reach for that librarian.");
            setEnabled(false);
            return;
        }
        lectern = best;
        stage = Stage.OPEN_TRADE;
    }

    private void openTrade() {
        if (mc.gui.screen() instanceof MerchantScreen) {
            stage = Stage.READ_TRADE;
            return;
        }
        if (mc.gui.screen() != null || mc.rightClickDelay > 0) {
            return;
        }
        if (!villager.isAlive() || mc.player.distanceTo(villager) > range.getValue()) {
            ChatUtil.error("The librarian wandered out of reach. Trap it first.");
            setEnabled(false);
            return;
        }
        if (rotate.isOn()) {
            BlockUtil.faceVector(villager.getBoundingBox().getCenter());
        }
        EntityHitResult hit = new EntityHitResult(villager, villager.getBoundingBox().getCenter());
        if (mc.gameMode.interact(mc.player, villager, hit, InteractionHand.MAIN_HAND).consumesAction()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        mc.rightClickDelay = 4;
    }

    private void readTrade() {
        if (!(mc.gui.screen() instanceof MerchantScreen screen)) {
            stage = Stage.OPEN_TRADE;
            return;
        }
        MerchantMenu menu = screen.getMenu();
        // Offers arrive a moment after the screen opens.
        if (menu.getOffers().isEmpty()) {
            return;
        }
        if (menu.getTraderXp() > 0) {
            ChatUtil.message("§bAutoLibrarian §7that librarian has traded before. Looking for another.");
            spent.add(villager.getId());
            closeTrade();
            stage = Stage.FIND_VILLAGER;
            return;
        }
        int index = 0;
        for (MerchantOffer offer : menu.getOffers()) {
            ItemStack book = offer.getResult();
            if (book.is(Items.ENCHANTED_BOOK)) {
                if (isWanted(offer)) {
                    accept(menu, index, book);
                    return;
                }
                ChatUtil.message("§bAutoLibrarian §7offered §f" + book.getHoverName().getString()
                    + "§7 for §f" + offer.getCostA().getCount() + "§7. Rerolling.");
                break;
            }
            index++;
        }
        closeTrade();
        stage = Stage.BREAK_LECTERN;
    }

    private boolean isWanted(MerchantOffer offer) {
        if (offer.getCostA().getCount() > maxPrice.getInt()) {
            return false;
        }
        ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(offer.getResult());
        Map<Holder<Enchantment>, Integer> wishes = wishes();
        for (Holder<Enchantment> enchantment : enchantments.keySet()) {
            Integer level = wishes.get(enchantment);
            if (level != null && enchantments.getLevel(enchantment) >= level) {
                return true;
            }
        }
        return false;
    }

    // The wanted list parsed against the enchantment registry of this world.
    private Map<Holder<Enchantment>, Integer> wishes() {
        Map<Holder<Enchantment>, Integer> result = new HashMap<>();
        var registry = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        for (String entry : wanted.getValue().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (entry.isBlank()) {
                continue;
            }
            String[] parts = entry.split(":");
            int level = 1;
            if (parts.length > 1) {
                try {
                    level = Integer.parseInt(parts[parts.length - 1]);
                } catch (NumberFormatException ignored) {
                }
            }
            Identifier id = Identifier.tryParse(parts.length > 1 && !isNumber(parts[1])
                ? parts[0] + ":" + parts[1] : parts[0]);
            if (id == null) {
                continue;
            }
            ResourceKey<Enchantment> key = ResourceKey.create(Registries.ENCHANTMENT, id);
            int lowest = level;
            registry.get(key).ifPresent(holder -> result.put(holder, lowest));
        }
        return result;
    }

    private static boolean isNumber(String text) {
        return !text.isEmpty() && text.chars().allMatch(Character::isDigit);
    }

    private void accept(MerchantMenu menu, int index, ItemStack book) {
        ChatUtil.message("§bAutoLibrarian §7found §f" + book.getHoverName().getString() + "§7.");
        if (lockIn.isOn()) {
            // Buying once fixes the offers for good.
            menu.setSelectionHint(index);
            menu.tryMoveItems(index);
            mc.player.connection.send(new ServerboundSelectTradePacket(index));
            mc.gameMode.handleContainerInput(menu.containerId, RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, mc.player);
        }
        closeTrade();
        setEnabled(false);
    }

    private void closeTrade() {
        mc.player.closeContainer();
        cooldown = 4;
    }

    private void breakLectern() {
        if (mc.gui.screen() != null) {
            return;
        }
        if (!BlockUtil.state(lectern).is(Blocks.LECTERN)) {
            BlockMiner.release();
            stage = Stage.PLACE_LECTERN;
            return;
        }
        int tool = ItemUtil.bestToolSlot(BlockUtil.state(lectern), 1,
            stack -> !ItemUtil.nearlyBroken(stack), InventoryUtil.HOTBAR_SIZE);
        if (tool != -1) {
            slots.select(tool);
        }
        BlockMiner.mine(lectern, rotate.isOn());
    }

    private void placeLectern() {
        if (mc.gui.screen() != null) {
            return;
        }
        if (BlockUtil.state(lectern).is(Blocks.LECTERN)) {
            slots.restoreIfMine();
            // The villager needs a moment to take the new job site up.
            cooldown = 20;
            stage = Stage.OPEN_TRADE;
            return;
        }
        if (!BlockUtil.isReplaceable(lectern)) {
            stage = Stage.BREAK_LECTERN;
            return;
        }
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.LECTERN));
        if (slot == -1) {
            ChatUtil.error("No lectern in the hotbar to put back.");
            setEnabled(false);
            return;
        }
        Direction support = BlockUtil.findPlaceSupport(lectern);
        if (support == null) {
            return;
        }
        slots.select(slot);
        BlockUtil.place(lectern, support, rotate.isOn(), true);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (villager != null) {
            event.getBatch().outlineBox(villager.getBoundingBox(), VILLAGER_COLOR, false);
        }
        if (lectern != null) {
            event.getBatch().outlineBlock(lectern, LECTERN_COLOR, false);
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (spent.contains(entity.getId())) {
                event.getBatch().outlineBox(entity.getBoundingBox(), SPENT_COLOR, false);
            }
        }
    }
}
