package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

// Lets the crosshair pass through entities.
public final class NoMiningTrace extends Module {

    private final RegistryListSetting<EntityType<?>> exempt = new RegistryListSetting<>("Exempt",
        "Entities the crosshair still stops on. Click to pick them.",
        BuiltInRegistries.ENTITY_TYPE, List.of());
    private final BoolSetting toolOnly = new BoolSetting("Tool only",
        "Only work whilst a pickaxe or an axe is in a hand.", true);
    private final BoolSetting skipPlayers = new BoolSetting("Keep players",
        "Never look through other players.", true);

    public NoMiningTrace() {
        super("NoMiningTrace", "Mines blocks through entities standing in the way.", Category.PLAYER);
        addSettings(exempt, toolOnly, skipPlayers);
        searchTags("mine through", "entity", "crosshair");
    }

    public boolean ignores(Entity entity) {
        if (!isEnabled() || entity == null || !inGame()) {
            return false;
        }
        if (skipPlayers.isOn() && entity instanceof Player) {
            return false;
        }
        if (exempt.contains(entity.getType())) {
            return false;
        }
        if (!toolOnly.isOn()) {
            return true;
        }
        return isTool(mc.player.getMainHandItem()) || isTool(mc.player.getOffhandItem());
    }

    private static boolean isTool(ItemStack stack) {
        return stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES);
    }
}
