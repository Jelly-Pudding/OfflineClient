package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.List;

// Behaviour lives in LivingEntityMixin. Only the local player reads the changed friction.
public final class Slippy extends Module {

    public enum ListMode { BLACKLIST, WHITELIST }

    private final NumberSetting friction = new NumberSetting("Friction",
        "The friction every block gets. Plain ground is 0.6 and ice is 0.98.", 1, 0.01, 1.1, 0.01, "")
        .min(0.01).max(1.1);
    private final EnumSetting<ListMode> listMode = new EnumSetting<>("List mode",
        "What the block list means.", ListMode.BLACKLIST)
        .describe(ListMode.BLACKLIST, "Every block is slippery except the listed ones.")
        .describe(ListMode.WHITELIST, "Only the listed blocks are slippery.");
    private final RegistryListSetting<Block> ignoredBlocks = new RegistryListSetting<>("Ignored blocks",
        "Blocks that keep their normal friction.", BuiltInRegistries.BLOCK, List.of())
        .under(listMode, ListMode.BLACKLIST);
    private final RegistryListSetting<Block> slipperyBlocks = new RegistryListSetting<>("Slippery blocks",
        "Blocks that get the friction above.", BuiltInRegistries.BLOCK, List.of())
        .under(listMode, ListMode.WHITELIST);

    public Slippy() {
        super("Slippy", "Makes the ground as slippery as ice or worse.", Category.MOVEMENT);
        addSettings(friction, listMode, ignoredBlocks, slipperyBlocks);
        searchTags("ice", "friction", "slide", "slippery");
    }

    @Override
    public String getSuffix() {
        return friction.getValueString();
    }

    // The friction the block under the player should report.
    public float groundFriction(Block block, float vanilla) {
        if (!isEnabled()) {
            return vanilla;
        }
        boolean listed = listMode.is(ListMode.WHITELIST)
            ? slipperyBlocks.contains(block) : ignoredBlocks.contains(block);
        boolean slippery = listed == listMode.is(ListMode.WHITELIST);
        return slippery ? friction.getFloat() : vanilla;
    }
}
