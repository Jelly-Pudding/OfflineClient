package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.CountRows;
import com.jellypudding.offlineclient.hud.CountRows.Counted;
import com.jellypudding.offlineclient.hud.RowCache;
import com.jellypudding.offlineclient.hud.RowsElement;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.LoadedChunks;
import net.minecraft.client.gui.Font;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// The two halves of a double chest count as one.
public final class ContainerCountElement extends RowsElement {

    private static final String TOTAL = "Total";

    // The count walks every loaded chunk. Containers come and go slowly.
    private static final long KEEP_MILLIS = TimeUnit.SECONDS.toMillis(1);

    private static final List<Counted> SAMPLE = List.of(new Counted("Double chest", 12),
        new Counted("Shulker box", 5), new Counted("Barrel", 2));

    private final RegistryListSetting<BlockEntityType<?>> kinds = new RegistryListSetting<>(
        "Container count kinds", "The containers that are counted. Click to pick them.",
        BuiltInRegistries.BLOCK_ENTITY_TYPE,
        List.of(BlockEntityTypes.CHEST, BlockEntityTypes.TRAPPED_CHEST, BlockEntityTypes.BARREL,
            BlockEntityTypes.SHULKER_BOX, BlockEntityTypes.ENDER_CHEST, BlockEntityTypes.HOPPER,
            BlockEntityTypes.DISPENSER, BlockEntityTypes.DROPPER));
    private final BoolSetting total = new BoolSetting("Container count total",
        "Writes how many there are of every kind together under the rows.", true);
    private final ColorSetting color = labelColor("Container count colour",
        "Colour of the kinds. The counts stay white.");
    private final RowCache<Counted> counts = new RowCache<>(this::build, KEEP_MILLIS);

    public ContainerCountElement() {
        super("Container count", "How many containers of each kind are loaded round you.", false, 100, 55);
        add(kinds, total, color);
    }

    private List<Counted> shown() {
        List<Counted> built = counts.get();
        List<Counted> rows = new ArrayList<>(built.isEmpty() && editing() ? SAMPLE : built);
        if (total.isOn() && !rows.isEmpty()) {
            rows.add(new Counted(TOTAL, rows.stream().mapToInt(Counted::count).sum()));
        }
        return rows;
    }

    private List<Counted> build() {
        if (OfflineClient.MC.level == null) {
            return List.of();
        }
        Map<String, Integer> found = new HashMap<>();
        LoadedChunks.forEachBlockEntity(container -> {
            String name = nameOf(container);
            if (name != null) {
                found.merge(name, 1, Integer::sum);
            }
        });
        return CountRows.ranked(found);
    }

    private String nameOf(BlockEntity container) {
        if (!kinds.contains(container.getType())) {
            return null;
        }
        String name = ChatUtil.words(container.getType());
        BlockState state = container.getBlockState();
        if (BlockUtil.otherChestHalf(container.getBlockPos(), state) == null) {
            return ChatUtil.capitalised(name);
        }
        return state.getValue(ChestBlock.TYPE) == ChestType.LEFT ? "Double " + name : null;
    }

    @Override
    protected List<TextRow> rows(Font font) {
        return CountRows.rows(font, shown(), color);
    }
}
