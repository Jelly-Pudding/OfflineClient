package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.StationModule;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Trades with an open villager again and again. Each round picks a wanted trade which moves
// its payment across and then shift clicks the result. That trades until the payment runs dry.
public final class AutoTrade extends StationModule<MerchantMenu> {

    // Ticks between two rounds of clicks.
    private static final int PACE = 4;

    private final RegistryListSetting<Item> buy = new RegistryListSetting<>("Buy",
        "Items to buy. Every trade that gives one of them is bought until it locks or you run out of payment.",
        BuiltInRegistries.ITEM, List.of());
    private final RegistryListSetting<Item> sell = new RegistryListSetting<>("Sell",
        "Items to sell for emeralds. Leave it empty to only buy.", BuiltInRegistries.ITEM, List.of());

    // Trades the inventory could not pay for since the last trade that went through.
    private final Set<Integer> unpaid = new HashSet<>();
    private int traded;

    public AutoTrade() {
        super("AutoTrade", "Buys and sells the trades you pick whilst a trading screen is open.",
            MerchantMenu.class, PACE);
        addSettings(buy, sell);
        searchTags("villager", "merchant", "wandering trader", "emerald");
    }

    @Override
    public String getSuffix() {
        return count(traded, "traded");
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        traded = 0;
    }

    @Override
    protected void closed() {
        unpaid.clear();
    }

    @Override
    protected void work(MerchantMenu menu) {
        // AutoLibrarian opens librarians only to read their offers and one trade would lock them in.
        if (Modules.enabled(AutoLibrarian.class)) {
            return;
        }
        MerchantOffers offers = menu.getOffers();
        // Offers arrive a moment after the screen opens.
        if (offers.isEmpty()) {
            return;
        }
        int index = nextTrade(offers);
        if (index == -1) {
            disable(whyNothing(offers));
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
        }
        // The chosen trade still showing means the inventory is full. A payment too small for the
        // chosen trade may still show another trade it covers.
        if (ItemStack.isSameItem(menu.getSlot(MenuClicks.TRADE_RESULT_SLOT).getItem(), offer.getResult())) {
            disable("No room in the inventory for what you traded for.");
        }
    }

    private int nextTrade(MerchantOffers offers) {
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            if (wanted(offer) && !offer.isOutOfStock() && !unpaid.contains(i)) {
                return i;
            }
        }
        return -1;
    }

    private boolean wanted(MerchantOffer offer) {
        if (offer.getResult().is(Items.EMERALD)) {
            return sell.contains(offer.getCostA().getItem());
        }
        return buy.contains(offer.getResult().getItem());
    }

    private String whyNothing(MerchantOffers offers) {
        boolean any = false;
        boolean open = false;
        for (MerchantOffer offer : offers) {
            if (wanted(offer)) {
                any = true;
                open |= !offer.isOutOfStock();
            }
        }
        if (!any) {
            return "This trader has none of the chosen trades.";
        }
        return open ? "You cannot pay for the chosen trades that are left."
            : "Every chosen trade is locked until the trader restocks.";
    }
}
