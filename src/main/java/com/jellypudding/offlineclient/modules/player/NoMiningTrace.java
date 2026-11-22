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
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

// CrosshairClip asks about portals.
public final class NoMiningTrace extends Module {

    private final RegistryListSetting<EntityType<?>> exempt = new RegistryListSetting<>("Exempt",
        "Entities the crosshair still stops on. Click to pick them.",
        BuiltInRegistries.ENTITY_TYPE, List.of());
    private final BoolSetting toolOnly = new BoolSetting("Tool only",
        "Only looks through entities whilst a pickaxe or an axe is in a hand.", true);
    private final BoolSetting skipPlayers = new BoolSetting("Keep players",
        "Never looks through other players.", true);
    private final BoolSetting portals = new BoolSetting("Nether portals",
        "The crosshair passes through nether portal blocks to the blocks and entities behind them.", false);

    public NoMiningTrace() {
        super("NoMiningTrace", "Mines and clicks through the entities and portals in the way.", Category.PLAYER);
        addSettings(exempt, toolOnly, skipPlayers, portals);
        searchTags("mine through", "entity", "crosshair", "portal hitbox");
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

    public boolean looksThrough(BlockState state) {
        return isEnabled() && portals.isOn() && state.getBlock() instanceof NetherPortalBlock;
    }

    private static boolean isTool(ItemStack stack) {
        return stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES);
    }
}
