package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.IntSupplier;

// Whilst its owner is on the picker says what to click next and shows the box that click would
// close and hands each clicked block over.
public final class CornerPicker {

    // A corner typed in a command goes in the same way as a clicked one.
    public interface Taker {
        void markCorner(BlockPos pos);
    }

    public static final int COLOR = 0xFFFFD040;

    // The most blocks any box may hold. A module that walks every block of a larger one would
    // stall the game.
    public static final int MOST_BLOCKS = 1 << 20;

    private static final int REFUSED_COLOR = 0xFFFF6060;

    private static final double MARK_INSET = 0.3;
    private static final double BOX_MARGIN = 0.005;

    private static final String FIRST = "the first corner";
    private static final String OPPOSITE = "the opposite corner";

    private final Module owner;
    private final Taker taker;
    private final BoxStyle style;
    private final IntSupplier limit;
    // What the owner does to the box in the words that end the box you want.
    private final String purpose;
    // What one more click marks once the box is closed. Null for an owner that wants the box alone.
    private String lastClick;

    private BlockPos first;
    private BlockPos second;
    private boolean guided;

    public <M extends Module & Taker> CornerPicker(M owner, BoxStyle style, IntSupplier limit, String purpose) {
        this.owner = owner;
        this.taker = owner;
        this.style = style;
        this.limit = limit;
        this.purpose = purpose;
    }

    public CornerPicker thenClick(String what) {
        lastClick = what;
        return this;
    }

    public static NumberSetting maxBlocks() {
        return new NumberSetting("Max blocks", "The most blocks one box may hold.", 4096, 64, 16384, 64).min(1);
    }

    // The owner opens the picker as it is switched on and closes it as it is switched off.
    public void open() {
        clear();
        guided = false;
        OfflineClient.INSTANCE.getEventBus().register(this);
    }

    public void close() {
        OfflineClient.INSTANCE.getEventBus().unregister(this);
        clear();
    }

    public void mark(BlockPos pos) {
        if (first == null) {
            first = pos;
        } else {
            second = pos;
        }
    }

    public void clear() {
        first = null;
        second = null;
    }

    public boolean done() {
        return second != null;
    }

    public BlockPos first() {
        return first;
    }

    public BlockPos second() {
        return second;
    }

    private String wanted() {
        if (first == null) {
            return FIRST;
        }
        return second == null ? OPPOSITE : lastClick;
    }

    public String waitingFor() {
        String wanted = wanted();
        return wanted == null ? null : "pick " + wanted;
    }

    // Blocks inside the box with both edges counted.
    public long volume() {
        return volume(first, second);
    }

    private static long volume(BlockPos from, BlockPos to) {
        return (long) (Math.abs(from.getX() - to.getX()) + 1)
            * (Math.abs(from.getY() - to.getY()) + 1)
            * (Math.abs(from.getZ() - to.getZ()) + 1);
    }

    // A larger box is turned down in chat and its second corner is taken back.
    public boolean withinLimit() {
        long volume = volume();
        if (volume <= limit.getAsInt()) {
            return true;
        }
        ChatUtil.error(refusal(volume) + ". Pick a smaller one.");
        second = null;
        return false;
    }

    private String refusal(long volume) {
        return "That box holds " + volume + " blocks and the limit is " + limit.getAsInt();
    }

    public boolean contains(BlockPos pos) {
        return BoundingBox.fromCorners(first, second).isInside(pos);
    }

    private static String key() {
        KeyMapping use = OfflineClient.MC.options.keyUse;
        return use.isUnbound() ? "the use key you have yet to bind in the controls"
            : use.getTranslatedKeyMessage().getString();
    }

    public static String press(String what) {
        return "press §f" + key() + "§7 on " + what;
    }

    // Said once each time the owner is switched on with no box. A box typed along with the switch
    // never hears it.
    @Subscribe
    private void onTick(TickEvent event) {
        if (guided) {
            return;
        }
        guided = true;
        if (first == null) {
            String clicks = press("one corner of the box you want " + purpose) + " and then on " + OPPOSITE
                + (lastClick == null ? "" : " and then on " + lastClick);
            String prefix = OfflineClient.INSTANCE.getCommandManager().getPrefix();
            ChatUtil.tell(owner.getName(), clicks + ". Type §f" + prefix
                + "corners§7 to set the box by numbers instead.");
        }
    }

    private BlockPos aimed() {
        if (wanted() == null || OfflineClient.MC.player == null || OfflineClient.MC.gui.screen() != null) {
            return null;
        }
        BlockHitResult hit = BlockUtil.aimedBlock();
        return hit == null ? null : hit.getBlockPos().immutable();
    }

    // The press is the picker's own and never reaches the game. A block pick that a setting asked
    // for takes its click first.
    @Subscribe(priority = Subscribe.LAST)
    private void onKeyPress(KeyPressEvent event) {
        if (event.getAction() != InputConstants.PRESS || !event.isFor(OfflineClient.MC.options.keyUse)) {
            return;
        }
        BlockPos aimed = aimed();
        if (aimed != null) {
            event.cancel();
            taker.markCorner(aimed);
        }
    }

    private record Prompt(String words, int color) {
    }

    private Prompt prompt() {
        String wanted = wanted();
        if (wanted == null || OfflineClient.MC.gui.screen() != null) {
            return null;
        }
        BlockPos closing = first != null && second == null ? aimed() : null;
        if (closing == null) {
            return new Prompt("Press " + key() + " on " + wanted, COLOR);
        }
        long volume = volume(first, closing);
        if (volume > limit.getAsInt()) {
            return new Prompt(refusal(volume), REFUSED_COLOR);
        }
        return new Prompt("Press " + key() + " to close a box of " + Tally.counted((int) volume, "block"), COLOR);
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        Prompt prompt = prompt();
        if (prompt != null) {
            RenderUtil.underCrosshair(event.getContext(), prompt.words(), prompt.color());
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        BlockPos aimed = aimed();
        if (first != null) {
            BlockPos far = second != null ? second : aimed != null ? aimed : first;
            style.draw(batch, AABB.encapsulatingFullBlocks(first, far).inflate(BOX_MARGIN), true);
            mark(batch, first);
        }
        if (second != null) {
            mark(batch, second);
        }
        if (aimed != null) {
            batch.outlineBlock(aimed, COLOR, true);
        }
    }

    private static void mark(DrawBatch batch, BlockPos corner) {
        batch.outlineBox(new AABB(corner).deflate(MARK_INSET), COLOR, true);
    }
}
