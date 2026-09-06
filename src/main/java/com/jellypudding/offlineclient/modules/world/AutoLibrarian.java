package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
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
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Rerolls a fresh librarian's book trade by breaking and replacing its
// lectern until it offers a book from the wanted list.
public final class AutoLibrarian extends Module {

    private static final int VILLAGER_COLOR = 0xFF30E030;
    private static final int LECTERN_COLOR = 0xFF40C0FF;
    private static final int SPENT_COLOR = 0xFFE03030;

    // Trade slots of the merchant screen.
    private static final int RESULT_SLOT = 2;

    private enum Stage { FIND_VILLAGER, FIND_LECTERN, OPEN_TRADE, READ_TRADE, BREAK_LECTERN, PLACE_LECTERN }

    public enum Update { OFF, REMOVE, PRICE }

    // One entry of the wanted list along with the text it was written as.
    private record Wish(String token, Holder<Enchantment> enchantment, int level, int price) {
    }

    private final TextSetting wanted = new TextSetting("Wanted books",
        "Enchantments separated by spaces. A colon and a number sets the lowest level and a"
            + " second one sets the most emeralds. Example: mending unbreaking:3:20",
        "mending unbreaking:3 sharpness:5 protection:4 efficiency:5 fortune:3 looting:3 silk_touch");
    private final NumberSetting maxPrice = new NumberSetting("Max price",
        "The most emeralds a book may cost when its own entry names no price.",
        64, 1, 64, 1, " emeralds").max(64);
    private final EnumSetting<Update> updateBooks = new EnumSetting<>("Update books",
        "What to do with a book on the list once a villager has learnt it.", Update.OFF)
        .describe(Update.OFF, "Leave the list alone.")
        .describe(Update.REMOVE, "Strike it off so the next villager learns something else.")
        .describe(Update.PRICE, "Lower its price so the next villager has to beat this one.");
    private final BoolSetting lockIn = new BoolSetting("Lock in",
        "Buys the book once and the villager keeps the trade. Needs emeralds and paper or a book.", false);
    private final NumberSetting range = new NumberSetting("Range",
        "How far the villager and its lectern may be.", 5, 1, 6, 0.1).max(6);
    private final NumberSetting repairMode = new NumberSetting("Repair mode",
        "Stops using a tool once this many uses are left. Nought never stops.",
        1, 0, 100, 1, " uses").min(0);
    private final EnumSetting<FaceMode> faceTarget = FaceMode.setting(FaceMode.SERVER);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);

    private Stage stage;
    private Villager villager;
    private BlockPos lectern;
    private final Set<Integer> spent = new HashSet<>();
    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private int cooldown;
    private boolean sneaking;

    public AutoLibrarian() {
        super("AutoLibrarian", "Rerolls a librarian until it sells a book you want.", Category.WORLD);
        addSettings(wanted, maxPrice, updateBooks, lockIn, range, repairMode, faceTarget, swing);
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
        loan.giveBack();
        stopSneaking();
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
        if (stage != Stage.PLACE_LECTERN) {
            stopSneaking();
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
        Vec3 centre = villager.getBoundingBox().getCenter();
        faceTarget.getValue().face(centre, RotationPriority.PLACE);
        EntityHitResult hit = new EntityHitResult(villager, centre);
        if (mc.gameMode.interact(mc.player, villager, hit, InteractionHand.MAIN_HAND).consumesAction()) {
            swing.getValue().swing(InteractionHand.MAIN_HAND);
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
                Wish match = wishFor(offer);
                if (match != null) {
                    accept(menu, index, book, match, offer.getCostA().getCount());
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

    // The wanted entry the offer satisfies or null when none does.
    private Wish wishFor(MerchantOffer offer) {
        int price = offer.getCostA().getCount();
        ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(offer.getResult());
        for (Wish wish : wishes()) {
            if (price > wish.price()) {
                continue;
            }
            if (enchantments.getLevel(wish.enchantment()) >= wish.level()) {
                return wish;
            }
        }
        return null;
    }

    // The wanted list parsed against the enchantment registry of this world.
    private List<Wish> wishes() {
        List<Wish> result = new ArrayList<>();
        var registry = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        for (String token : wanted.getValue().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            List<String> parts = new ArrayList<>(List.of(token.split(":")));
            // A trailing number is the level and two of them are the level then the price.
            int price = maxPrice.getInt();
            int level = 1;
            if (parts.size() > 1 && isNumber(parts.get(parts.size() - 1))) {
                int last = Integer.parseInt(parts.remove(parts.size() - 1));
                if (parts.size() > 1 && isNumber(parts.get(parts.size() - 1))) {
                    price = last;
                    level = Integer.parseInt(parts.remove(parts.size() - 1));
                } else {
                    level = last;
                }
            }
            Identifier id = Identifier.tryParse(String.join(":", parts));
            if (id == null) {
                continue;
            }
            int wantedLevel = level;
            int wantedPrice = price;
            registry.get(ResourceKey.create(Registries.ENCHANTMENT, id))
                .ifPresent(holder -> result.add(new Wish(token, holder, wantedLevel, wantedPrice)));
        }
        return result;
    }

    private static boolean isNumber(String text) {
        return !text.isEmpty() && text.chars().allMatch(Character::isDigit);
    }

    private void accept(MerchantMenu menu, int index, ItemStack book, Wish wish, int price) {
        ChatUtil.message("§bAutoLibrarian §7found §f" + book.getHoverName().getString() + "§7.");
        if (lockIn.isOn()) {
            // Buying once fixes the offers for good.
            menu.setSelectionHint(index);
            menu.tryMoveItems(index);
            mc.player.connection.send(new ServerboundSelectTradePacket(index));
            mc.gameMode.handleContainerInput(menu.containerId, RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, mc.player);
        }
        updateWanted(wish, price);
        closeTrade();
        setEnabled(false);
    }

    // Strikes the book off the list or asks the next villager for a better price.
    private void updateWanted(Wish wish, int price) {
        if (updateBooks.is(Update.OFF)) {
            return;
        }
        String replacement = "";
        if (updateBooks.is(Update.PRICE) && price > 1) {
            replacement = wish.enchantment().getRegisteredName() + ":" + wish.level()
                + ":" + (price - 1);
        }
        List<String> kept = new ArrayList<>();
        boolean done = false;
        for (String token : wanted.getValue().split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            if (!done && token.equalsIgnoreCase(wish.token())) {
                done = true;
                if (!replacement.isEmpty()) {
                    kept.add(replacement);
                }
                continue;
            }
            kept.add(token);
        }
        wanted.setValue(String.join(" ", kept));
        ChatUtil.message("§bAutoLibrarian §7wanted books updated.");
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
            stack -> !ItemUtil.nearlyBroken(stack, repairMode.getInt()), InventoryUtil.HOTBAR_SIZE);
        if (tool != -1) {
            loan.select(tool);
        }
        Direction side = BlockUtil.facingSide(lectern);
        faceTarget.getValue().face(BlockUtil.hitPoint(lectern, side), RotationPriority.MINE);
        BlockMiner.mine(lectern, false, swing.getValue());
    }

    private void placeLectern() {
        if (mc.gui.screen() != null) {
            return;
        }
        if (BlockUtil.state(lectern).is(Blocks.LECTERN)) {
            stopSneaking();
            loan.giveBack();
            // The villager needs a moment to take the new job site up.
            cooldown = 20;
            stage = Stage.OPEN_TRADE;
            return;
        }
        if (!BlockUtil.isReplaceable(lectern)) {
            stage = Stage.BREAK_LECTERN;
            return;
        }
        int slot = InventoryUtil.findSlot(Items.LECTERN, InventoryUtil.WHOLE_INVENTORY);
        if (slot == -1) {
            ChatUtil.error("No lectern left to put back.");
            setEnabled(false);
            return;
        }
        Direction support = BlockUtil.findPlaceSupport(lectern);
        if (support == null) {
            return;
        }
        if (!loan.select(slot)) {
            return;
        }
        // Sneaking stops the click opening a chest or a trapdoor underneath.
        InputUtil.hold(mc.options.keyShift);
        sneaking = true;
        if (!mc.player.isShiftKeyDown()) {
            return;
        }
        BlockPos against = lectern.relative(support);
        faceTarget.getValue().face(BlockUtil.hitPoint(against, support.getOpposite()),
            RotationPriority.PLACE);
        if (BlockUtil.place(lectern, support, false, false)) {
            swing.getValue().swing(InteractionHand.MAIN_HAND);
        }
    }

    private void stopSneaking() {
        if (sneaking) {
            InputUtil.release(mc.options.keyShift);
            sneaking = false;
        }
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
