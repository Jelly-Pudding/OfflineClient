package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

// Item frames hung on containers read as labels of what belongs inside. Both halves of a
// double chest share their labels.
public final class FrameLabels {

    private static final Minecraft MC = OfflineClient.MC;

    private final Map<BlockPos, List<ItemStack>> shown = new HashMap<>();
    private boolean wholeKinds;

    // With whole kinds a framed piece of gear stands for every item of its kind.
    public void read(Predicate<BlockPos> container, boolean wholeKinds) {
        shown.clear();
        this.wholeKinds = wholeKinds;
        for (Entity entity : MC.level.entitiesForRendering()) {
            if (!(entity instanceof ItemFrame frame) || frame.getItem().isEmpty()) {
                continue;
            }
            BlockPos on = frame.getPos().relative(frame.getDirection().getOpposite());
            if (container.test(on)) {
                shown.computeIfAbsent(on, _ -> new ArrayList<>()).add(frame.getItem().copy());
            }
        }
    }

    // What a container takes. A labelled one takes what its frames show. An unlabelled one takes
    // what no frame shows when leftovers are wanted and nothing otherwise. A null position is a
    // cart or a boat that no frame can hang on.
    public Predicate<ItemStack> takes(BlockPos pos, boolean leftovers) {
        boolean kinds = wholeKinds;
        List<ItemStack> own = pos == null ? List.of() : labelsOf(pos);
        if (!own.isEmpty()) {
            return stack -> showsAny(own, stack, kinds);
        }
        if (!leftovers) {
            return _ -> false;
        }
        List<ItemStack> every = shown.values().stream().flatMap(List::stream).toList();
        return stack -> !showsAny(every, stack, kinds);
    }

    private List<ItemStack> labelsOf(BlockPos pos) {
        List<ItemStack> labels = new ArrayList<>(shown.getOrDefault(pos, List.of()));
        BlockPos other = BlockUtil.otherChestHalf(pos, BlockUtil.state(pos));
        if (other != null) {
            labels.addAll(shown.getOrDefault(other, List.of()));
        }
        return labels;
    }

    private static boolean showsAny(List<ItemStack> labels, ItemStack stack, boolean wholeKinds) {
        for (ItemStack label : labels) {
            if (wholeKinds ? GearKind.alike(label, stack) : stack.is(label.getItem())) {
                return true;
            }
        }
        return false;
    }
}
