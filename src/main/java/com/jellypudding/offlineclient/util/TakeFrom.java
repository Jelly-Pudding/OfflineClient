package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.setting.EnumSetting;

// Where a module may pick up the item it uses.
public enum TakeFrom {
    HANDS, HOTBAR, INVENTORY;

    // How many inventory slots from the first a search may look through.
    public int limit() {
        return switch (this) {
            case HANDS -> 0;
            case HOTBAR -> InventoryUtil.HOTBAR_SIZE;
            case INVENTORY -> InventoryUtil.WHOLE_INVENTORY;
        };
    }

    public static EnumSetting<TakeFrom> setting(String noun, TakeFrom defaultValue) {
        return new EnumSetting<>("Take from", "Where " + noun + " may be taken from.", defaultValue)
            .describe(HANDS, "Only uses " + noun + " already in your hands.")
            .describe(HOTBAR, "Takes " + noun + " from the hotbar only.")
            .describe(INVENTORY, "Borrows " + noun + " from anywhere in the inventory.");
    }
}
