package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.ListMode;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// Behaviour lives in LivingEntityMixin. Only the local player reads the changed friction.
public final class Slippy extends Module {

    private final NumberSetting friction = new NumberSetting("Friction",
        "The friction every block gets. Plain ground is 0.6 and ice is 0.98. Above one you keep speeding up.",
        1, 0.01, 1.1, 0.01, "").min(0.01);
    private final NumberSetting topSpeed = new NumberSetting("Top speed",
        "The fastest a friction above one lets you slide.", 20, 5, 100, 1, " bps").min(1)
        .under(friction, () -> friction.getValue() > 1);
    private final EnumSetting<ListMode> listMode = ListMode.setting("List mode", ListMode.BLACKLIST,
        "Only the listed blocks are slippery.", "Every block is slippery except the listed ones.");
    private final RegistryListSetting<Block> ignoredBlocks = new RegistryListSetting<>("Ignored blocks",
        "Blocks that keep their normal friction.", BuiltInRegistries.BLOCK, List.of())
        .under(listMode, ListMode.BLACKLIST);
    private final RegistryListSetting<Block> slipperyBlocks = new RegistryListSetting<>("Slippery blocks",
        "Blocks that get the friction above.", BuiltInRegistries.BLOCK, List.of())
        .under(listMode, ListMode.WHITELIST);

    public Slippy() {
        super("Slippy", "Makes the ground as slippery as ice or worse.", Category.MOVEMENT);
        addSettings(friction, topSpeed, listMode, ignoredBlocks, slipperyBlocks);
        searchTags("ice", "friction", "slide", "slippery");
    }

    @Override
    public String getSuffix() {
        return friction.getValueString();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || !mc.player.onGround() || friction.getValue() <= 1) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        double top = topSpeed.getValue() / SharedConstants.TICKS_PER_SECOND;
        double pace = velocity.horizontalDistance();
        if (pace > top) {
            mc.player.setDeltaMovement(velocity.multiply(top / pace, 1, top / pace));
        }
    }

    // The friction the block under the player should report.
    public float groundFriction(Block block, float vanilla) {
        if (!isEnabled()) {
            return vanilla;
        }
        boolean listed = listMode.is(ListMode.WHITELIST)
            ? slipperyBlocks.contains(block) : ignoredBlocks.contains(block);
        return listMode.getValue().admits(listed) ? friction.getFloat() : vanilla;
    }
}
