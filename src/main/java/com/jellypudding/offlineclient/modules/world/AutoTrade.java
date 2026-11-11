package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.StationModule;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// Trades with an open villager again and again. Each round picks a wanted trade which moves
// its payment across and then shift clicks the result. That trades until the payment runs dry.
// With Work the hall it opens every trader in reach in turn and leaves each one be for a while
// once nothing more can be traded with it.
public final class AutoTrade extends StationModule<MerchantMenu> {

    // Ticks between two rounds of clicks.
    private static final int PACE = 4;

    // Ticks a clicked trader gets to open its screen. One that is asleep or trading with
    // somebody else never does.
    private static final int OPEN_WAIT = 30;

    // Ticks between closing one trader and clicking the next.
    private static final int CLOSE_GAP = 4;

    // Far more traders than stand in reach of one spot. It only bounds the memory.
    private static final int MOST_TRADERS = 1024;

    private final RegistryListSetting<Item> buy = new RegistryListSetting<>("Buy",
        "Items to buy. Every trade that gives one of them is bought until it locks or you run out of payment.",
        BuiltInRegistries.ITEM, List.of());
    private final RegistryListSetting<Item> sell = new RegistryListSetting<>("Sell",
        "Items to sell for emeralds. Leave it empty to only buy.", BuiltInRegistries.ITEM, List.of());
    private final NumberSetting freeSlots = new NumberSetting("Free slots",
        "Nothing is bought whilst fewer inventory slots than this are empty. Nought buys until the last one fills.",
        0, 0, 36, 1).max(InventoryUtil.WHOLE_INVENTORY);
    private final BoolSetting hall = new BoolSetting("Work the hall",
        "Opens every villager and wandering trader in reach in turn and makes the chosen trades with each.",
        false);
    private final NumberSetting range = new NumberSetting("Range",
        "How far away a trader may stand.", 4, 1, 6, 0.5, " blocks")
        .under(hall);
    private final NumberSetting comeBack = new NumberSetting("Come back after",
        "Minutes a trader is left alone once nothing more can be traded with it. Villagers restock"
            + " at their workstation twice a day.", 5, 1, 30, 1, " min")
        .under(hall);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns towards each trader on the server side.", true)
        .under(hall);

    // Trades the inventory could not pay for since the last trade that went through.
    private final Set<Integer> unpaid = new HashSet<>();
    // When each trader the hall round finished with is due again.
    private final Map<UUID, Long> dueAt = new BoundedMap<>(MOST_TRADERS);
    // What the trades of this run brought in.
    private Tally gained = new Tally();
    // The trader clicked and waited on and the one whose screen that click opened.
    private AbstractVillager clicked;
    private AbstractVillager visiting;
    private int clickedAt;
    private int resumeAt;
    // True once the hall round is through with a trader you opened yourself. Its screen stays.
    private boolean leftOpen;
    private int traded;

    public AutoTrade() {
        super("AutoTrade", "Buys and sells the trades you pick whilst a trading screen is open.",
            MerchantMenu.class, PACE);
        addSettings(buy, sell, freeSlots, hall, range, comeBack, rotate);
        searchTags("villager", "merchant", "wandering trader", "emerald", "trading hall");
    }

    @Override
    public String getSuffix() {
        return count(traded, "traded");
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        traded = 0;
        gained = new Tally();
        resumeAt = 0;
        clicked = null;
        dueAt.clear();
    }

    @Override
    protected void onDisable() {
        clicked = null;
        visiting = null;
        if (traded > 0) {
            tell("made §f" + Tally.counted(traded, "trade") + " §7for §f"
                + gained.words() + "§7.");
        }
    }

    @Override
    protected void closed() {
        unpaid.clear();
        visiting = null;
        leftOpen = false;
    }

    @Override
    protected void work(MerchantMenu menu) {
        // AutoLibrarian opens librarians only to read their offers and one trade would lock them in.
        if (leftOpen || Modules.enabled(AutoLibrarian.class)) {
            return;
        }
        if (clicked != null) {
            visiting = clicked;
            clicked = null;
        }
        MerchantOffers offers = menu.getOffers();
        // Offers arrive a moment after the screen opens.
        if (offers.isEmpty()) {
            if (hall.isOn() && stalled()) {
                finish("This trader has nothing to trade.");
            }
            return;
        }
        int index = nextTrade(offers);
        if (index == -1) {
            finish(whyNothing(offers));
            return;
        }
        MerchantOffer offer = offers.get(index);
        int usesBefore = offer.getUses();
        if (!MenuClicks.selectTrade(menu, index)) {
            unpaid.add(index);
            return;
        }
        MenuClicks.quickMove(menu, MenuClicks.TRADE_RESULT_SLOT);
        int made = offer.getUses() - usesBefore;
        traded += made;
        if (made > 0) {
            // What came in may pay for a trade that could not be paid for before.
            unpaid.clear();
            ItemStack result = offer.getResult();
            gained.add(result.getItemName().getString().toLowerCase(Locale.ROOT), made * result.getCount());
        }
        // The chosen trade still showing means the inventory is full. A payment too small for the
        // chosen trade may still show another trade it covers.
        if (ItemStack.isSameItem(menu.getSlot(MenuClicks.TRADE_RESULT_SLOT).getItem(), offer.getResult())) {
            disable("No room in the inventory for what you traded for.");
        }
    }

    // Nothing more can be traded here. The hall round closes the screen and moves on. With a
    // trader you opened yourself the module stops and says why.
    private void finish(String why) {
        if (!hall.isOn()) {
            disable(why);
            return;
        }
        // Which trader a screen you opened yourself belongs to is not known. Closing it would
        // only have the round click the same trader again.
        if (visiting == null) {
            leftOpen = true;
            return;
        }
        rest(visiting);
        mc.player.closeContainer();
        resumeAt = ticks() + CLOSE_GAP;
    }

    private void rest(AbstractVillager trader) {
        dueAt.put(trader.getUUID(), System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(comeBack.getInt()));
    }

    // Clicks the nearest trader that is due. One whose screen never opens is left alone for a
    // while like one that is finished.
    @Override
    protected void away() {
        if (!hall.isOn() || ticks() < resumeAt || !handsFree(false) || Modules.enabled(AutoLibrarian.class)) {
            return;
        }
        if (clicked != null) {
            if (ticks() - clickedAt < OPEN_WAIT) {
                return;
            }
            rest(clicked);
            clicked = null;
        }
        if (mc.rightClickDelay > 0) {
            return;
        }
        Entity next = EntityUtil.nearest(range.getValue(), this::due);
        if (next == null) {
            return;
        }
        clicked = (AbstractVillager) next;
        clickedAt = ticks();
        EntityUtil.interact(next, InteractionHand.MAIN_HAND, rotate.isOn());
        mc.rightClickDelay = InputUtil.USE_DELAY;
    }

    // A grown trader that is awake and has a trade to offer and has not been finished with of
    // late. A jobless villager and a nitwit never trade.
    private boolean due(Entity entity) {
        if (!(entity instanceof AbstractVillager trader) || !trader.isAlive() || trader.isBaby()
            || trader.isSleeping()) {
            return false;
        }
        if (trader instanceof Villager villager
            && (villager.getVillagerData().profession().is(VillagerProfession.NONE)
            || villager.getVillagerData().profession().is(VillagerProfession.NITWIT))) {
            return false;
        }
        Long due = dueAt.get(trader.getUUID());
        return due == null || System.currentTimeMillis() >= due;
    }

    private int nextTrade(MerchantOffers offers) {
        boolean cramped = cramped();
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            if (wanted(offer) && !offer.isOutOfStock() && !unpaid.contains(i) && !(cramped && buys(offer))) {
                return i;
            }
        }
        return -1;
    }

    // True whilst fewer slots are empty than Free slots asks for. Selling still goes on.
    private boolean cramped() {
        int needed = freeSlots.getInt();
        return needed > 0 && InventoryUtil.emptySlots() < needed;
    }

    private static boolean buys(MerchantOffer offer) {
        return !offer.getResult().is(Items.EMERALD);
    }

    private boolean wanted(MerchantOffer offer) {
        if (buys(offer)) {
            return buy.contains(offer.getResult().getItem());
        }
        return sell.contains(offer.getCostA().getItem());
    }

    private String whyNothing(MerchantOffers offers) {
        boolean any = false;
        boolean open = false;
        boolean held = false;
        boolean cramped = cramped();
        for (MerchantOffer offer : offers) {
            if (wanted(offer)) {
                any = true;
                open |= !offer.isOutOfStock();
                held |= !offer.isOutOfStock() && cramped && buys(offer);
            }
        }
        if (!any) {
            return "This trader has none of the chosen trades.";
        }
        if (held) {
            return "Fewer than " + freeSlots.getInt() + " inventory slots are empty. Nothing more is bought.";
        }
        return open ? "You cannot pay for the chosen trades that are left."
            : "Every chosen trade is locked until the trader restocks.";
    }
}
