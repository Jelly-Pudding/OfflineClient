package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// A fading box on each block a miner broke. It lights up once the block has gone from the world.
// Break packets leave a block standing until the server takes it away and one the server refused
// never lights up. A break made like a held click clears the block before the server answers.
public final class BreakTrail {

    private static final int MAX_BOXES = 256;

    // Ticks a block that was sent for may take to go before it is forgotten.
    private static final int BREAK_WAIT_TICKS = 40;

    private final BoolSetting show = new BoolSetting("Broken trail",
        "Draws a fading box on each block as it breaks.", true);
    private final NumberSetting time = new NumberSetting("Trail time",
        "How long each box takes to fade away.", 8, 1, 40, 1, " ticks").min(1).under(show);
    private final BoxStyle style = new BoxStyle("Trail", BoxStyle.Shape.BOTH, 0).under(show);

    // A block sent for and what stood there when the packets left. A powered part that only
    // flips its state is still standing.
    private static final class Entry {

        private final BlockPos pos;
        private final Block was;
        private int waitLeft = BREAK_WAIT_TICKS;
        private int fadeLeft;
        private int fadeTotal;

        private Entry(BlockPos pos, Block was) {
            this.pos = pos;
            this.was = was;
        }

        private boolean broken() {
            return fadeTotal > 0;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public Setting<?>[] settings() {
        List<Setting<?>> all = new ArrayList<>(List.of(show, time));
        all.addAll(List.of(style.settings()));
        return all.toArray(new Setting<?>[0]);
    }

    // Called as the break packets for a block go out or on each tick one is mined like a held click.
    public void add(BlockPos pos) {
        if (!show.isOn() || OfflineClient.MC.level == null || entries.size() >= MAX_BOXES) {
            return;
        }
        BlockPos fixed = pos.immutable();
        entries.removeIf(entry -> entry.pos.equals(fixed));
        entries.add(new Entry(fixed, OfflineClient.MC.level.getBlockState(fixed).getBlock()));
    }

    // Once a tick. A block that has gone since it was sent starts to fade.
    public void tick() {
        if (OfflineClient.MC.level == null) {
            entries.clear();
            return;
        }
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (entry.broken()) {
                if (--entry.fadeLeft <= 0) {
                    it.remove();
                }
            } else if (!OfflineClient.MC.level.getBlockState(entry.pos).is(entry.was)) {
                entry.fadeTotal = time.getInt();
                entry.fadeLeft = entry.fadeTotal;
            } else if (--entry.waitLeft <= 0) {
                it.remove();
            }
        }
    }

    public void draw(DrawBatch batch, float partialTicks) {
        if (!show.isOn()) {
            return;
        }
        for (Entry entry : entries) {
            if (entry.broken()) {
                float strength = (entry.fadeLeft - partialTicks) / entry.fadeTotal;
                style.drawFading(batch, DrawBatch.blockBox(entry.pos), Math.max(0, strength), false);
            }
        }
    }

    public void clear() {
        entries.clear();
    }
}
