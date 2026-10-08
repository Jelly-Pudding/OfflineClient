package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Collections;
import java.util.List;
import java.util.Set;

// The server sends the find of a suspicious block with the first stroke of the brush. This
// names it in chat and can break the block out when the find is not wanted.
public final class BrushReader extends Module {

    // Blocks whose find was named are kept from being named again. The oldest are let go first.
    private static final int MAX_NAMED = 256;

    private final BoolSetting breakUnwanted = new BoolSetting("Break unwanted",
        "Breaks the block when its find is on the list below.", false);
    private final RegistryListSetting<Item> unwanted = new RegistryListSetting<>("Unwanted",
        "Finds not worth brushing out. Click to pick them.", BuiltInRegistries.ITEM, List.of())
        .under(breakUnwanted);

    private final Set<BlockPos> named = Collections.newSetFromMap(new BoundedMap<>(MAX_NAMED));
    private final WorldWatch world = new WorldWatch();
    // The block with an unwanted find that is being broken. Null whilst none is.
    private BlockPos breaking;

    public BrushReader() {
        super("BrushReader", "Names the find in suspicious sand or gravel as you brush it.", Category.WORLD);
        addSettings(breakUnwanted, unwanted);
        searchTags("suspicious sand", "suspicious gravel", "brush", "archaeology");
    }

    @Override
    protected void onDisable() {
        stopBreaking();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (world.changed()) {
            named.clear();
            breaking = null;
        }
        if (breaking != null) {
            if (!(mc.level.getBlockEntity(breaking) instanceof BrushableBlockEntity)
                || (!BlockMiner.minesOther(breaking) && !BlockMiner.mine(breaking, true))) {
                stopBreaking();
            }
            return;
        }
        BlockHitResult hit = BlockUtil.aimedBlock();
        if (hit == null || !(mc.level.getBlockEntity(hit.getBlockPos()) instanceof BrushableBlockEntity brushable)) {
            return;
        }
        ItemStack find = brushable.getItem();
        BlockPos pos = hit.getBlockPos().immutable();
        if (find.isEmpty() || !named.add(pos)) {
            return;
        }
        boolean breaks = breakUnwanted.isOn() && unwanted.contains(find.getItem());
        ChatUtil.message("§bBrushReader §7found §f" + find.getHoverName().getString() + "§7 in the "
            + ChatUtil.words(BlockUtil.state(pos).getBlock()) + (breaks ? " and breaks it out." : "."));
        if (breaks) {
            mc.gameMode.releaseUsingItem(mc.player);
            breaking = pos;
        }
    }

    private void stopBreaking() {
        if (breaking != null) {
            BlockMiner.release(breaking);
            breaking = null;
        }
    }
}
