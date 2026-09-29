package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.FindSource;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.gui.FindsScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.FindLines;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.AlertSound;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.LoadedChunks;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.ServerWatch;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

// Paper sends the sherds of a pot and nothing of what it holds. Contents still slip out.
// The insert sound rises in pitch as the pot fills and reaches everyone within sixteen
// blocks and the wobble of a pot taking an item reaches sixty four. A trial chamber builds
// its pots on copper of its own with at most one sherd. Every other pot was made by a player.
public final class PotFinder extends Module implements FindSource {

    // What the server let slip about one pot. The share is how full the pot is out of one and
    // stays below nought until an insert is heard.
    private static final class Contents {

        // Someone was seen or heard putting an item in.
        private boolean filled;
        // Something is inside.
        private boolean holds;
        private float share = -1;
        // Null until the server sends it or a probe matches.
        private Item item;
        private int count;
        // The stack the server sent last. The same stack read again says nothing new.
        private ItemStack lastSent = ItemStack.EMPTY;
        // Probe items the pot turned away or that got no answer.
        private final Set<Item> refused = new HashSet<>();

        // Someone put an item in. The wobble carries no fill and reaches further than the sound.
        private void sawInsert() {
            filled = true;
            holds = true;
        }

        // The insert sound gives the fill after the item went in.
        private void heardInsert(float fill) {
            sawInsert();
            share = fill;
            if (item != null) {
                count = countAt(item, fill);
            }
        }

        // A vanilla server sends the stack with the chunk and keeps its copy there as it was
        // whilst items go in. A fill heard since stays until another stack comes.
        private void sent(ItemStack stack) {
            if (ItemStack.matches(stack, lastSent)) {
                return;
            }
            lastSent = stack.copy();
            holds = true;
            item = stack.getItem();
            count = stack.getCount();
            share = (float) count / item.getDefaultMaxStackSize();
        }

        // A lit comparator only says the pot is not empty.
        private void lit() {
            holds = true;
        }

        private void matched(Item probed) {
            item = probed;
            count = countAt(probed, share);
        }

        private void refuse(Item probed) {
            refused.add(probed);
        }

        private boolean tried(Item probed) {
            return refused.contains(probed);
        }

        private boolean holdsSomething() {
            return holds;
        }

        // Worth a probe whilst something is inside that no probe has named and room is left for one more.
        private boolean probeable() {
            return holds && item == null && share < 1;
        }

        // True when a player has had a hand in a trial chamber pot. Someone put an item in or
        // it holds what its table never gives or more of it.
        private boolean tampered(Map<Item, Integer> loot) {
            if (filled) {
                return true;
            }
            if (item == null) {
                return false;
            }
            Integer most = loot.get(item);
            return most == null || count > most;
        }

        // Such as holding 12 diamonds or about 40% full. Empty when nothing is known.
        private String words() {
            if (item != null) {
                return " holding " + Tally.counted(count, ChatUtil.words(item));
            }
            if (share >= 1) {
                return " full";
            }
            if (share >= 0) {
                return " about " + Math.round(share * PERCENT) + "% full";
            }
            return holds ? " holding something" : "";
        }

        private static int countAt(Item item, float share) {
            return Math.max(1, Math.round(share * item.getDefaultMaxStackSize()));
        }
    }

    // A probe the player can make now.
    private record Probe(BlockPos pot, Item item, int slot) {
    }

    // The kinds of find. A filled pot is known to hold something.
    private static final String PLAIN = "pot";
    private static final String FILLED = "filled pot";
    // What the words of a find start with.
    private static final String PLACED_POT = "placed pot";
    private static final String CHAMBER_POT = "trial chamber pot";

    // The loot a trial chamber pot starts with and the most of each it rolls. The corridor pots
    // share one table and the pot of the second intersection holds three string.
    private static final Map<Item, Integer> CORRIDOR_LOOT = Map.of(Items.EMERALD, 3, Items.ARROW, 8,
        Items.IRON_INGOT, 2, Items.TRIAL_KEY, 1, Items.MUSIC_DISC_CREATOR_MUSIC_BOX, 1, Items.DIAMOND, 2,
        Items.EMERALD_BLOCK, 1, Items.DIAMOND_BLOCK, 1);
    private static final Map<Item, Integer> INTERSECTION_LOOT = Map.of(Items.STRING, 3);
    // The corridor pots stand on this and show one of these sherds on the back or none.
    private static final Block CORRIDOR_FLOOR = Blocks.CUT_COPPER.waxed().oxidized();
    private static final Set<Item> CORRIDOR_SHERDS = Set.of(Items.GUSTER_POTTERY_SHERD, Items.FLOW_POTTERY_SHERD,
        Items.SCRAPE_POTTERY_SHERD);
    // The intersection pot stands on this with a flow sherd on the front.
    private static final Block INTERSECTION_FLOOR = Blocks.COPPER_BLOCK.waxed().oxidized();

    // The server raises the insert sound from this pitch by up to half again as the pot fills.
    private static final float INSERT_PITCH = 0.7f;
    private static final float INSERT_PITCH_RANGE = 0.5f;
    private static final float PERCENT = 100;

    // Twice a second is quick enough for a pot that was just placed.
    private static final int SCAN_TICKS = 10;
    // Far more pots than a session hears about in one dimension. It only bounds the memory.
    private static final int MAX_KNOWN = 4096;
    // How long a probe waits for the pot's reply. A second covers the round trip on a slow link.
    private static final int REPLY_TICKS = 20;
    // A short pause between two probes.
    private static final int PROBE_GAP = 5;

    private static final int QUIET_SECONDS = 10;

    // A pot is a column fourteen sixteenths wide.
    private static final double POT_INSET = 1.0 / 16;
    private static final double LABEL_LIFT = 0.3;

    private final BoolSetting probe = new BoolSetting("Probe",
        "Right clicks a pot in reach once it is known to hold something. Each probe item is tried in turn and"
            + " the one that matches goes into the pot and tells how many it holds. No other pot is clicked.",
        false);
    private final RegistryListSetting<Item> probeItems = new RegistryListSetting<>("Probe items",
        "The items tried on a pot. Renamed or enchanted stacks are never used. Click to pick them.",
        BuiltInRegistries.ITEM, List.of(Items.DIAMOND, Items.NETHERITE_INGOT, Items.EMERALD, Items.GOLD_INGOT,
            Items.IRON_INGOT, Items.ENDER_PEARL, Items.ENCHANTED_GOLDEN_APPLE))
        .under(probe);
    private final EnumSetting<FaceMode> face = FaceMode.setting(FaceMode.SERVER).under(probe);
    private final FindLog finds = new FindLog(this);
    // A base often holds a row of pots. The first message stands for the rest of the row.
    private final Notice notice = new Notice(this, Notice.Where.CHAT, QUIET_SECONDS);
    private final AlertSound alarm = new AlertSound("Rings when a new pot is found.", SoundEvents.BELL_BLOCK);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 14);
    private final ColorSetting holdingColor = new ColorSetting("Holding colour",
        "Colour of pots known to hold something.", 50, 1f, 1f, false);
    private final BoolSetting labels = new BoolSetting("Labels",
        "Writes what is known about each pot above it.", true);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the labels.", 1, 0.5, 3, 0.1).min(0.1).under(labels);
    private final FindLines marks = new FindLines(finds, false);

    // What each pot let slip in every dimension of the current server.
    private final Map<ResourceKey<Level>, Map<BlockPos, Contents>> known = new HashMap<>();
    // Pot sounds and wobbles from the network thread handled on the next tick.
    private final Queue<Packet<?>> heard = new ConcurrentLinkedQueue<>();
    private final ServerWatch server = new ServerWatch();
    private final WorldWatch world = new WorldWatch();
    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private int scanWait;
    private int probeWait;
    // The pot a probe waits on and the item it tried. Null whilst no probe is out.
    private BlockPos probed;
    private Item probeItem;
    private int replyWait;

    public PotFinder() {
        super("PotFinder", "Finds decorated pots that players placed and learns what they hold.", Category.HUNTING);
        addSettings(probe, probeItems, face);
        addSettings(notice.settings());
        addSettings(alarm.settings());
        addSettings(style.settings());
        addSettings(holdingColor, labels, scale);
        addSettings(marks.settings());
        addSettings(FindsScreen.settingsFor(finds));
        searchTags("pot", "decorated pot", "sherd", "trial chamber", "stash");
    }

    @Override
    public FindLog findLog() {
        return finds;
    }

    @Override
    public String getSuffix() {
        return count(finds.count());
    }

    @Override
    protected void onEnable() {
        heard.clear();
        endProbe();
        scanWait = 0;
        world.forget();
    }

    @Override
    protected void onDisable() {
        alarm.stop();
        heard.clear();
        known.clear();
        server.forget();
        endProbe();
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getPacket();
        if (packet instanceof ClientboundSoundPacket sound && (plays(sound, SoundEvents.DECORATED_POT_INSERT)
            || plays(sound, SoundEvents.DECORATED_POT_INSERT_FAIL))
            || packet instanceof ClientboundBlockEventPacket wobble && wobble.getBlock() == Blocks.DECORATED_POT) {
            heard.add(packet);
        }
    }

    // Matched by name. A sound sent without its registry entry is a copy of the game's own.
    private static boolean plays(ClientboundSoundPacket sound, SoundEvent event) {
        return sound.getSound().value().location().equals(event.location());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (world.changed()) {
            // A probe out in the last world gets no answer here.
            endProbe();
            scanWait = 0;
        }
        Packet<?> packet;
        while ((packet = heard.poll()) != null) {
            hear(packet);
        }
        if (--scanWait <= 0) {
            scanWait = SCAN_TICKS;
            scan();
        }
        if (probe.isOn()) {
            probeStep();
        } else {
            endProbe();
        }
    }

    private Map<BlockPos, Contents> knownHere() {
        if (server.changed()) {
            // Two servers share their dimension names.
            known.clear();
        }
        return known.computeIfAbsent(mc.level.dimension(), key -> new BoundedMap<>(MAX_KNOWN));
    }

    private Contents contentsAt(BlockPos pos) {
        return knownHere().computeIfAbsent(pos.immutable(), key -> new Contents());
    }

    private void hear(Packet<?> packet) {
        switch (packet) {
            case ClientboundSoundPacket sound -> heardSound(sound);
            // Only an item going into a pot wobbles it this way. A refused click wobbles it the other way.
            case ClientboundBlockEventPacket wobble when wobble.getB0() == DecoratedPotBlockEntity.EVENT_POT_WOBBLES
                && wobble.getB1() == DecoratedPotBlockEntity.WobbleStyle.POSITIVE.ordinal() ->
                contentsAt(wobble.getPos()).sawInsert();
            default -> {
            }
        }
    }

    // A refusal says nothing on its own because an empty hand is refused too. It only answers a probe.
    private void heardSound(ClientboundSoundPacket sound) {
        BlockPos pos = BlockPos.containing(sound.getX(), sound.getY(), sound.getZ());
        boolean inserted = plays(sound, SoundEvents.DECORATED_POT_INSERT);
        if (inserted) {
            contentsAt(pos).heardInsert(Math.clamp((sound.getPitch() - INSERT_PITCH) / INSERT_PITCH_RANGE, 0, 1));
        }
        if (!pos.equals(probed)) {
            return;
        }
        Contents contents = contentsAt(pos);
        if (inserted) {
            contents.matched(probeItem);
        } else {
            contents.refuse(probeItem);
        }
        endProbe();
    }

    private void scan() {
        Map<BlockPos, Contents> here = knownHere();
        Set<Long> scanned = new HashSet<>();
        Set<BlockPos> pots = new HashSet<>();
        LoadedChunks.forEach(chunk -> {
            scanned.add(ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z()));
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (blockEntity instanceof DecoratedPotBlockEntity pot) {
                    pots.add(pot.getBlockPos());
                    judge(pot, here);
                }
            }
        });
        forgetGone(scanned, pots, here);
    }

    private void judge(DecoratedPotBlockEntity pot, Map<BlockPos, Contents> here) {
        if (pot.getLootTable() != null) {
            // A vanilla server sends the loot table of a chamber pot nobody has touched yet.
            return;
        }
        BlockPos pos = pot.getBlockPos();
        Contents contents = read(pot, here);
        Map<Item, Integer> loot = chamberLoot(pot.getDecorations(), mc.level.getBlockState(pos.below()));
        if (loot != null && (contents == null || !contents.tampered(loot))) {
            return;
        }
        FindLog.Find old = finds.at(pos);
        if (old != null && contents == null) {
            // Nothing learnt this session. The log keeps what an earlier one found out.
            return;
        }
        String kind = contents != null && contents.holdsSomething() ? FILLED : PLAIN;
        String detail = (loot == null ? PLACED_POT : CHAMBER_POT) + (contents == null ? "" : contents.words());
        if (finds.add(pos, kind, detail)) {
            alarm.ring();
            notice.tell(kind, ChatUtil.withArticle(detail), pos);
        } else if (old != null && !old.detail().equals(detail)) {
            notice.tell(kind, ChatUtil.withArticle(detail), pos);
        }
    }

    // What the pot shows of its contents. A vanilla server sends what it holds and a comparator
    // reading it lights up whilst anything is inside. Null when nothing is known.
    private Contents read(DecoratedPotBlockEntity pot, Map<BlockPos, Contents> here) {
        BlockPos pos = pot.getBlockPos();
        ItemStack sent = pot.getTheItem();
        if (!sent.isEmpty()) {
            contentsAt(pos).sent(sent);
        } else if (comparatorLit(pos)) {
            contentsAt(pos).lit();
        }
        return here.get(pos);
    }

    // A comparator reads the block its back faces. It stays unlit whilst that pot is empty.
    private boolean comparatorLit(BlockPos pot) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockState state = mc.level.getBlockState(pot.relative(side));
            if (state.is(Blocks.COMPARATOR) && state.getValue(ComparatorBlock.FACING) == side.getOpposite()
                && state.getValue(ComparatorBlock.POWERED)) {
                return true;
            }
        }
        return false;
    }

    // The loot the trial chamber built the pot with or null for a pot a player made.
    private static Map<Item, Integer> chamberLoot(PotDecorations sherds, BlockState floor) {
        if (floor.is(CORRIDOR_FLOOR) && plain(sherds.left()) && plain(sherds.right()) && plain(sherds.front())
            && (plain(sherds.back()) || sherds.back().map(face -> CORRIDOR_SHERDS.contains(face.item().value()))
                .orElse(false))) {
            return CORRIDOR_LOOT;
        }
        if (floor.is(INTERSECTION_FLOOR) && plain(sherds.back()) && plain(sherds.left()) && plain(sherds.right())
            && sherds.front().map(face -> face.item().value() == Items.FLOW_POTTERY_SHERD).orElse(false)) {
            return INTERSECTION_LOOT;
        }
        return null;
    }

    // A face with no sherd shows a brick.
    private static boolean plain(Optional<ItemStackTemplate> face) {
        return face.map(template -> template.item().value() == Items.BRICK).orElse(true);
    }

    // A pot broken since it was found leaves the log. Only chunks the scan could read count. What
    // a pot let slip is kept only whilst its chunk stays loaded. A pot broken and set down empty
    // whilst you were away must not be probed on the strength of an older visit.
    private void forgetGone(Set<Long> scanned, Set<BlockPos> pots, Map<BlockPos, Contents> here) {
        for (FindLog.Find find : List.copyOf(finds.here())) {
            if (scanned.contains(ChunkPos.pack(find.pos())) && !pots.contains(find.pos())) {
                finds.remove(find);
            }
        }
        here.keySet().removeIf(pos -> !pots.contains(pos));
    }

    // Waits on the reply of the last probe or makes the next one.
    private void probeStep() {
        if (probed != null) {
            if (--replyWait <= 0) {
                // No answer came. A protected pot or a lost packet is not tried with that item again.
                contentsAt(probed).refuse(probeItem);
                endProbe();
            }
            return;
        }
        if (--probeWait > 0 || mc.gui.screen() != null || mc.player.isUsingItem()) {
            return;
        }
        Probe next = nextProbe();
        if (next == null) {
            return;
        }
        Direction side = BlockUtil.facingSide(next.pot());
        if (!face.getValue().face(BlockUtil.hitPoint(next.pot(), side), RotationPriority.PLACE)) {
            return;
        }
        // The click needs the item in the main hand. A refusal there answers straight away
        // where the off hand would let the item be used on the pot's face.
        if (loan.select(next.slot()) && plainStack(mc.player.getMainHandItem(), next.item())
            && BlockUtil.interact(next.pot(), side)) {
            probed = next.pot();
            probeItem = next.item();
            replyWait = REPLY_TICKS;
        }
        loan.giveBack();
    }

    // The nearest pot in reach known to hold something that a carried probe item has not been
    // tried on. A hopper or a hopper minecart under a pot can empty it without a sound and the
    // pot is left alone.
    private Probe nextProbe() {
        Probe best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, Contents> entry : knownHere().entrySet()) {
            BlockPos pos = entry.getKey();
            Contents contents = entry.getValue();
            if (!contents.probeable() || !BlockUtil.serverReaches(pos)
                || !(mc.level.getBlockEntity(pos) instanceof DecoratedPotBlockEntity) || drained(pos)) {
                continue;
            }
            double distance = mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
            Probe candidate = distance < bestDistance ? probeFor(pos, contents) : null;
            if (candidate != null) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private boolean drained(BlockPos pot) {
        return mc.level.getBlockState(pot.below()).is(Blocks.HOPPER)
            || !mc.level.getEntitiesOfClass(MinecartHopper.class, new AABB(pot.below())).isEmpty();
    }

    private Probe probeFor(BlockPos pos, Contents contents) {
        for (Item item : probeItems.resolved()) {
            if (contents.tried(item)) {
                continue;
            }
            int slot = InventoryUtil.findSlot(stack -> plainStack(stack, item), InventoryUtil.WHOLE_INVENTORY);
            if (slot != -1) {
                return new Probe(pos, item, slot);
            }
        }
        return null;
    }

    // The pot only takes an item that matches what it holds down to every component. A renamed
    // stack would be turned away whatever the pot holds.
    private static boolean plainStack(ItemStack stack, Item item) {
        return stack.is(item) && stack.getComponentsPatch().isEmpty();
    }

    private void endProbe() {
        probed = null;
        probeItem = null;
        probeWait = PROBE_GAP;
    }

    private static AABB potBox(BlockPos pos) {
        return new AABB(pos).deflate(POT_INSET, 0, POT_INSET);
    }

    private int colorOf(FindLog.Find find) {
        return FILLED.equals(find.kind()) ? holdingColor.getColor() : style.lineColor();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (FindLog.Find find : finds.here()) {
            if (!marks.inDrawRange(find)) {
                continue;
            }
            if (FILLED.equals(find.kind())) {
                style.draw(batch, potBox(find.pos()), holdingColor.getColor(), true);
            } else {
                style.draw(batch, potBox(find.pos()), true);
            }
        }
        marks.draw(batch, this::colorOf);
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!labels.isOn() || !inGame() || !WorldToScreen.update()) {
            return;
        }
        for (FindLog.Find find : finds.here()) {
            // A placed pot with nothing known about it needs no words beside its box.
            if (find.detail().equals(PLACED_POT) || !marks.inDrawRange(find)) {
                continue;
            }
            AABB box = potBox(find.pos());
            Vec3 screen = WorldToScreen.project(new Vec3(box.getCenter().x, box.maxY + LABEL_LIFT, box.getCenter().z));
            if (screen != null) {
                RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, scale.getFloat(),
                    List.of(find.detail()), List.of(colorOf(find)));
            }
        }
    }
}
