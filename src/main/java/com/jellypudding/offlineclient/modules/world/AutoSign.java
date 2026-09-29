package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.LiveText;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.UseBudget;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.HangingSignItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.HangingSignBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

// Writes your text on signs without the edit screen showing. Both sides and hanging signs can
// have text of their own. It can also write the signs around you and sign each block you place.
public final class AutoSign extends Module {

    public enum Source { TYPED, FIRST_SIGN }

    public enum Back { BLANK, SAME, OWN }

    // What a sign gets once its writing is done. Each takes one click with the item.
    private enum Finish { DYE, GLOW, WAX }

    // Where a sign is in its writing. Each time the server opens the editor it buys one side.
    private enum Step { WRITE, CLICK, OPENING, FINISH }

    // Ticks to wait for the server to open a sign after a click before giving up on it.
    private static final int OPEN_TIMEOUT = 40;
    // Ticks a sign is left alone after AutoSign worked on it. An editor it opens meanwhile is dropped.
    private static final int RETRY_TICKS = 100;
    // Signs the server opened that can wait their turn behind the one being written.
    private static final int MAX_WAITING = 16;

    private static final List<SignTextSlot> SIDES = List.of(SignTextSlot.FRONT, SignTextSlot.BACK);

    private final EnumSetting<Source> source = new EnumSetting<>("Source",
        "Where the text comes from.", Source.TYPED)
        .describe(Source.TYPED, "The text typed below.")
        .describe(Source.FIRST_SIGN, "Copies whatever you write on the next sign by hand.");
    private final TextSetting front = new TextSetting("Text",
        "What the front says. Type \\n to start a new line. You can use " + LiveText.WORDS + ".",
        "{username}\\nwas here")
        .under(source, Source.TYPED);
    private final BoolSetting ownHanging = new BoolSetting("Own hanging text",
        "Gives hanging signs their own text.", false)
        .under(source, Source.TYPED);
    private final TextSetting hangingFront = new TextSetting("Hanging text",
        "What the front of a hanging sign says. Type \\n to start a new line.", "")
        .under(ownHanging);
    private final EnumSetting<Back> back = new EnumSetting<>("Back",
        "What the back of the sign says.", Back.BLANK)
        .describe(Back.BLANK, "Leaves the back empty.")
        .describe(Back.SAME, "Writes the front text on the back as well.")
        .describe(Back.OWN, "Writes its own text on the back.");
    private final TextSetting backText = new TextSetting("Back text",
        "What the back says. Type \\n to start a new line.", "")
        .under(back, Back.OWN);
    private final TextSetting hangingBack = new TextSetting("Hanging back text",
        "What the back of a hanging sign says. Type \\n to start a new line.", "")
        .under(back, () -> back.is(Back.OWN) && source.is(Source.TYPED) && ownHanging.isOn());
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait before each side is written. Servers dislike an instant reply.", 5, 0, 40, 1, " ticks");
    private final EnumSetting<FaceMode> faceTarget = FaceMode.setting(FaceMode.SERVER);
    private final BoolSetting aura = new BoolSetting("Sign aura",
        "Writes your text on the signs around you.", false);
    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes a sign may be.", 4.5, 1, 6, 0.5, " blocks")
        .under(aura);
    private final BoolSetting overwrite = new BoolSetting("Overwrite",
        "Also rewrites signs that already have writing. Off only fills blank ones.", false)
        .under(aura);
    private final BoolSetting placeSigns = new BoolSetting("Place signs",
        "Puts a sign on each block you place by hand. Standing signs go first and hanging ones after.",
        false);
    private final RegistryListSetting<Item> skipSigns = new RegistryListSetting<>("Skip signs",
        "Sign types it never places.", BuiltInRegistries.ITEM, List.of())
        .only(AutoSign::isSign)
        .under(placeSigns);
    private final RegistryListSetting<Item> dyes = new RegistryListSetting<>("Dye with",
        "Dyes the writing on the side facing you with the first of these you carry. Empty keeps it black.",
        BuiltInRegistries.ITEM, List.of())
        .only(item -> item instanceof DyeItem);
    private final BoolSetting glow = new BoolSetting("Glowing text",
        "Makes the writing on the side facing you glow with a glow ink sac you carry.", false);
    private final BoolSetting wax = new BoolSetting("Wax",
        "Seals each written sign with honeycomb you carry. Nobody can change it after that.", false);

    private record Opened(BlockPos pos, SignTextSlot slot) {
    }

    // A block you placed by hand. It gets a sign once the tick count reaches ready.
    private record Base(BlockPos clicked, Direction face, int ready) {
    }

    // One sign being written. The sides still to write and what it gets after.
    private static final class Job {

        private final BlockPos pos;
        private final Deque<SignTextSlot> sides;
        private final Deque<Finish> finishes = new ArrayDeque<>();
        private Step step;
        private int waited;

        private Job(BlockPos pos, List<SignTextSlot> sides, Step step) {
            this.pos = pos;
            this.sides = new ArrayDeque<>(sides);
            this.step = step;
        }
    }

    // Filled on the network thread and emptied on the game thread.
    private final Queue<Opened> opened = new ConcurrentLinkedQueue<>();
    private final Map<BlockPos, SignTextSlot> waiting = new BoundedMap<>(MAX_WAITING);
    private final Cooldowns<BlockPos> recent = new Cooldowns<>();
    private final SlotSwap slots = new SlotSwap();
    private final HotbarLoan loan = new HotbarLoan();
    private final WorldWatch world = new WorldWatch();

    // Read on the network thread.
    private volatile String[] learned;
    private Job job;
    private Base base;
    private int ticks;
    private int pause;
    private int written;
    // True whilst AutoSign sends its own packets. Its own writing and clicks are never learned from.
    private boolean sending;
    private final ChatWarning noSign = new ChatWarning();
    private final ChatWarning noHand = new ChatWarning();

    public AutoSign() {
        super("AutoSign", "Writes your text on signs without the edit screen showing.", Category.WORLD);
        addSettings(source, front, ownHanging, hangingFront, back, backText, hangingBack, delay, faceTarget,
            aura, range, overwrite, placeSigns, skipSigns, dyes, glow, wax);
        searchTags("sign", "text", "dye", "glow", "wax");
    }

    @Override
    public String getSuffix() {
        if (source.is(Source.FIRST_SIGN) && learned == null) {
            return "waiting for a sign";
        }
        return count(written, "signed");
    }

    @Override
    protected void onEnable() {
        learned = null;
        written = 0;
        world.accept();
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
        slots.restoreIfMine();
    }

    private void reset() {
        opened.clear();
        waiting.clear();
        recent.clear();
        job = null;
        base = null;
        pause = 0;
        noSign.clear();
        noHand.clear();
    }

    // Safe on any thread. Only the settings and the learned lines are read.
    private boolean hasText() {
        return source.is(Source.TYPED) || learned != null;
    }

    // Fired on the network thread. The editor never shows for a sign AutoSign writes.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundOpenSignEditorPacket packet && hasText()) {
            opened.add(new Opened(packet.pos(), packet.slot()));
            event.cancel();
        }
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (sending || event.isCancelled() || !inGame()) {
            return;
        }
        if (event.getPacket() instanceof ServerboundSignUpdatePacket packet) {
            learn(packet);
        } else if (event.getPacket() instanceof ServerboundUseItemOnPacket packet && placeSigns.isOn()
            && InputUtil.physicallyHeld(mc.options.keyUse)) {
            BlockHitResult hit = packet.hitResult();
            base = new Base(hit.getBlockPos(), hit.getDirection(), ticks + Math.max(1, delay.getInt()));
        }
    }

    // The lines you send by hand become the text for every sign after them. The side you did
    // not write gets its text straight after.
    private void learn(ServerboundSignUpdatePacket packet) {
        if (!source.is(Source.FIRST_SIGN)) {
            return;
        }
        boolean first = learned == null;
        learned = packet.lines().toArray(new String[0]);
        if (!first) {
            return;
        }
        ChatUtil.message("§bAutoSign §7copied that sign.");
        SignBlockEntity sign = signAt(packet.pos());
        SignTextSlot other = packet.slot() == SignTextSlot.FRONT ? SignTextSlot.BACK : SignTextSlot.FRONT;
        if (job == null && sign != null && linesFor(sign, other) != null) {
            job = new Job(sign.getBlockPos(), List.of(other), Step.CLICK);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (world.changed() || mc.player.isDeadOrDying()) {
            reset();
            return;
        }
        ticks++;
        recent.tick();
        // A switch to copying waits for the next sign you write.
        if (!source.is(Source.FIRST_SIGN)) {
            learned = null;
        }
        takeOpened();
        if (pause > 0) {
            pause--;
        } else if (job != null) {
            advance();
        } else if (!waiting.isEmpty()) {
            startWaiting();
        } else if (base != null && ticks >= base.ready()) {
            if (canClick()) {
                signOn(base);
                base = null;
            }
        } else if (aura.isOn() && mc.player.mayBuild() && canClick()) {
            auraClick();
        }
    }

    // Clicks wait whilst a screen is open or the server's use budget is spent.
    private boolean canClick() {
        return mc.gui.screen() == null && UseBudget.remaining() > 0;
    }

    private void takeOpened() {
        for (Opened open = opened.poll(); open != null; open = opened.poll()) {
            if (job != null && job.step == Step.OPENING && job.pos.equals(open.pos())) {
                job.step = Step.WRITE;
                pause = delay.getInt();
            } else {
                waiting.put(open.pos(), open.slot());
            }
        }
    }

    // An editor the server opened for a sign you placed or clicked yourself.
    private void startWaiting() {
        Iterator<Map.Entry<BlockPos, SignTextSlot>> first = waiting.entrySet().iterator();
        Map.Entry<BlockPos, SignTextSlot> next = first.next();
        first.remove();
        SignBlockEntity sign = signAt(next.getKey());
        if (sign == null) {
            return;
        }
        List<SignTextSlot> sides = sidesToWrite(sign);
        if (!sides.isEmpty()) {
            job = new Job(sign.getBlockPos(), sides, Step.WRITE);
            pause = delay.getInt();
        } else if (!recent.contains(sign.getBlockPos())) {
            // There is nothing to write. The editor opens as it would without AutoSign.
            mc.player.openTextEdit(sign, next.getValue());
        }
    }

    private void advance() {
        switch (job.step) {
            case WRITE -> writeNextSide();
            case CLICK -> reopen();
            case OPENING -> {
                if (++job.waited > OPEN_TIMEOUT) {
                    end();
                }
            }
            case FINISH -> finishNext();
        }
    }

    private void writeNextSide() {
        SignBlockEntity sign = signAt(job.pos);
        if (sign == null) {
            end();
            return;
        }
        SignTextSlot side = job.sides.poll();
        send(new ServerboundSignUpdatePacket(job.pos, linesFor(sign, side), side));
        if (!job.sides.isEmpty()) {
            job.step = Step.CLICK;
            return;
        }
        job.step = Step.FINISH;
        if (dyes.size() > 0) {
            job.finishes.add(Finish.DYE);
        }
        if (glow.isOn()) {
            job.finishes.add(Finish.GLOW);
        }
        if (wax.isOn()) {
            job.finishes.add(Finish.WAX);
        }
    }

    // One opening buys one side. The sign is clicked again for the next one.
    private void reopen() {
        if (!canClick()) {
            return;
        }
        if (clickSign(job.pos)) {
            job.step = Step.OPENING;
            job.waited = 0;
        } else {
            end();
        }
    }

    private void finishNext() {
        Finish finish = job.finishes.peek();
        if (finish == null) {
            written++;
            end();
            return;
        }
        if (!canClick()) {
            return;
        }
        job.finishes.poll();
        SignBlockEntity sign = signAt(job.pos);
        if (sign != null) {
            apply(finish, sign);
        }
    }

    private void end() {
        recent.put(job.pos, RETRY_TICKS);
        job = null;
        pause = delay.getInt();
    }

    // Dye and glow only take on writing and a miss would open the editor again. Each is
    // skipped when the side facing you has no words or already carries it.
    private void apply(Finish finish, SignBlockEntity sign) {
        SignTextSlot facing = sign.getSlotPlayerIsFacing(mc.player);
        SignText shown = sign.getText(facing);
        List<String> wanted = linesFor(sign, facing);
        boolean worded = wanted != null ? wanted.stream().anyMatch(line -> !line.isBlank())
            : shown.hasMessage(mc.player.isTextFilteringEnabled());
        Item item = switch (finish) {
            case DYE -> worded ? carriedDye(shown.getColor()) : null;
            case GLOW -> worded && !shown.hasGlowingText() ? Items.GLOW_INK_SAC : null;
            case WAX -> sign.isWaxed() ? null : Items.HONEYCOMB;
        };
        if (item != null) {
            useOn(sign.getBlockPos(), item);
        }
    }

    // The first dye on the list you carry anywhere. Null when you carry none or the writing
    // already has its colour.
    private Item carriedDye(DyeColor current) {
        for (Item dye : dyes.resolved()) {
            if (mc.player.getOffhandItem().is(dye)
                || InventoryUtil.findSlot(dye, InventoryUtil.WHOLE_INVENTORY) != -1) {
                return dye.components().get(DataComponents.DYE) == current ? null : dye;
            }
        }
        return null;
    }

    // Clicks the sign with the item. The offhand is used where it holds one and a stack
    // anywhere else is borrowed into the hotbar for the click.
    private void useOn(BlockPos pos, Item item) {
        if (mc.player.getOffhandItem().is(item)) {
            click(pos, InteractionHand.OFF_HAND);
        } else if (loan.hold(item, InventoryUtil.WHOLE_INVENTORY)) {
            click(pos, InteractionHand.MAIN_HAND);
            loan.giveBack();
        }
    }

    // Opens a sign with a hand that has no use of its own. A block or dye in hand would be
    // used on the sign instead whenever the server turns the edit down.
    private boolean clickSign(BlockPos pos) {
        int slot = InventoryUtil.hotbarSlot(AutoSign::harmless);
        if (slot == -1) {
            noHand.say("AutoSign needs an empty hotbar slot or a plain item such as a stick to open signs.");
            return false;
        }
        noHand.clear();
        slots.select(slot);
        boolean clicked = click(pos, InteractionHand.MAIN_HAND);
        slots.restoreIfMine();
        return clicked;
    }

    // Empty or a plain item such as a stick or a sword. Axes and hoes and shovels change blocks.
    private static boolean harmless(ItemStack stack) {
        return stack.isEmpty()
            || stack.getItem().getClass() == Item.class && !stack.has(DataComponents.BLOCK_TRANSFORMER);
    }

    private boolean click(BlockPos pos, InteractionHand hand) {
        Direction side = sideToClick(pos);
        faceTarget.getValue().face(BlockUtil.hitPoint(pos, side), RotationPriority.PLACE);
        sending = true;
        try {
            return BlockUtil.interact(pos, side, hand);
        } finally {
            sending = false;
        }
    }

    // The face nearest the eyes. Never the underside where a held hanging sign would hang another.
    private Direction sideToClick(BlockPos pos) {
        Direction side = BlockUtil.facingSide(pos);
        return side != Direction.DOWN ? side : sideFacingYou(pos);
    }

    private Direction sideFacingYou(BlockPos pos) {
        Vec3 toEyes = mc.player.getEyePosition().subtract(Vec3.atCenterOf(pos));
        return Direction.getApproximateNearest(toEyes.x, 0, toEyes.z);
    }

    private void auraClick() {
        BlockPos pos = BlockUtil.nearestWithin(range.getValue(), this::auraWants);
        if (pos == null) {
            return;
        }
        recent.put(pos, RETRY_TICKS);
        List<SignTextSlot> sides = sidesToWrite(signAt(pos));
        if (clickSign(pos)) {
            job = new Job(pos, sides, Step.OPENING);
        }
    }

    // An unwaxed sign in reach that the server lets you edit and that lacks your text.
    private boolean auraWants(BlockPos pos) {
        if (recent.contains(pos) || !(mc.level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            return false;
        }
        boolean filtering = mc.player.isTextFilteringEnabled();
        if (sign.isWaxed() || runsClicks(sign, filtering)
            || !sign.getText(sign.getSlotPlayerIsFacing(mc.player)).hasEditableText(filtering)) {
            return false;
        }
        if (!overwrite.isOn() && SIDES.stream().anyMatch(side -> sign.getText(side).hasMessage(filtering))) {
            return false;
        }
        return BlockUtil.serverReaches(pos) && !sidesToWrite(sign).isEmpty();
    }

    // A click on a sign runs any command in its text. The aura keeps away from those.
    private static boolean runsClicks(SignBlockEntity sign, boolean filtering) {
        for (SignTextSlot side : SIDES) {
            for (Component line : sign.getText(side).getMessages(filtering)) {
                if (line.getStyle().getClickEvent() != null) {
                    return true;
                }
            }
        }
        return false;
    }

    // A sign on the block you placed. When the click only used a block such as a chest the
    // sign goes on that block instead.
    private void signOn(Base placed) {
        BlockPos beside = placed.clicked().relative(placed.face());
        BlockPos block = BlockUtil.isReplaceable(beside) ? placed.clicked() : beside;
        BlockState state = BlockUtil.state(block);
        if (state.canBeReplaced() || state.getBlock() instanceof SignBlock || !mc.player.mayBuild()
            || !BlockUtil.serverReaches(block)) {
            return;
        }
        int slot = signSlot(false);
        boolean hanging = slot == -1;
        if (hanging) {
            slot = signSlot(true);
        }
        if (slot == -1) {
            noSign.say("AutoSign has no sign in the hotbar.");
            return;
        }
        noSign.clear();
        Direction face = signFace(block, hanging);
        if (face == null) {
            return;
        }
        BlockPos target = block.relative(face);
        slots.select(slot);
        faceTarget.getValue().face(BlockUtil.hitPoint(block, face), RotationPriority.PLACE);
        sending = true;
        try {
            // A chest or a door would open instead. Sneaking places the sign against it.
            if (BlockUtil.opensOnClick(state)) {
                InputUtil.whileSneaking(() -> BlockUtil.place(target, face.getOpposite(), false, true));
            } else {
                BlockUtil.place(target, face.getOpposite(), false, true);
            }
        } finally {
            sending = false;
        }
        slots.restoreIfMine();
    }

    // The face of the block the sign goes on. A standing sign sits on top and a hanging one
    // underneath and either falls back to the side facing you. Null when none is free.
    private Direction signFace(BlockPos block, boolean hanging) {
        Direction face = hanging ? Direction.DOWN : Direction.UP;
        if (BlockUtil.blockFits(block.relative(face))) {
            return face;
        }
        Direction side = sideFacingYou(block);
        return BlockUtil.blockFits(block.relative(side)) ? side : null;
    }

    // A hotbar slot with a sign of the kind asked for that is not skipped. Minus one when none.
    private int signSlot(boolean hanging) {
        return InventoryUtil.hotbarSlot(stack -> isSign(stack.getItem())
            && (stack.getItem() instanceof HangingSignItem) == hanging && !skipSigns.contains(stack.getItem()));
    }

    // Standing and wall signs and both kinds of hanging sign place a sign block.
    private static boolean isSign(Item item) {
        return item instanceof BlockItem block && block.getBlock() instanceof SignBlock;
    }

    // The sides whose wanted lines differ from what the sign shows.
    private List<SignTextSlot> sidesToWrite(SignBlockEntity sign) {
        List<SignTextSlot> sides = new ArrayList<>();
        for (SignTextSlot side : SIDES) {
            List<String> wanted = linesFor(sign, side);
            if (wanted != null && !shows(sign.getText(side), wanted)) {
                sides.add(side);
            }
        }
        return sides;
    }

    private boolean shows(SignText text, List<String> lines) {
        List<Component> messages = text.getMessages(mc.player.isTextFilteringEnabled());
        for (int i = 0; i < lines.size(); i++) {
            if (!messages.get(i).getString().equals(lines.get(i))) {
                return false;
            }
        }
        return true;
    }

    // The four lines one side should show cut to what fits on the sign like the edit screen
    // cuts them. Null when that side is left as it is.
    private List<String> linesFor(SignBlockEntity sign, SignTextSlot side) {
        List<String> wanted = wantedLines(sign instanceof HangingSignBlockEntity, side);
        if (wanted == null) {
            return null;
        }
        List<String> lines = new ArrayList<>(SignText.LINES);
        for (int i = 0; i < SignText.LINES; i++) {
            String line = i < wanted.size() ? wanted.get(i) : "";
            lines.add(mc.font.plainSubstrByWidth(line, sign.getMaxTextLineWidth()));
        }
        return lines;
    }

    // The lines of one side with the live words filled in. A back that repeats the front reads it.
    private List<String> wantedLines(boolean hanging, SignTextSlot side) {
        boolean ownHangingText = hanging && source.is(Source.TYPED) && ownHanging.isOn();
        if (side == SignTextSlot.BACK && !back.is(Back.SAME)) {
            return back.is(Back.BLANK) ? null : LiveText.lines((ownHangingText ? hangingBack : backText).getValue());
        }
        if (source.is(Source.FIRST_SIGN)) {
            String[] copied = learned;
            return copied == null ? null : Arrays.stream(copied).map(LiveText::fill).toList();
        }
        return LiveText.lines((ownHangingText ? hangingFront : front).getValue());
    }

    private SignBlockEntity signAt(BlockPos pos) {
        return mc.level.getBlockEntity(pos) instanceof SignBlockEntity sign ? sign : null;
    }

    private void send(ServerboundSignUpdatePacket packet) {
        sending = true;
        try {
            mc.player.connection.send(packet);
        } finally {
            sending = false;
        }
    }
}
