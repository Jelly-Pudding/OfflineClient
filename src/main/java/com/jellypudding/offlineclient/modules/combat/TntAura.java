package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RankSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.Ignition;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.TargetPriority;
import com.jellypudding.offlineclient.util.UseBudget;
import com.jellypudding.offlineclient.util.UseClick;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

// Sets lit TNT on enemies and lights the TNT you place. Lit TNT drops to the ground before
// it goes off and TNT set over a head lands at the feet below.
public final class TntAura extends Module {

    // Fire comes from flint and steel or a fire charge in the order Light with sets.
    public enum Lighter {
        FIRE(Items.FLINT_AND_STEEL),
        REDSTONE_BLOCK(Items.REDSTONE_BLOCK),
        REDSTONE_TORCH(Items.REDSTONE_TORCH);

        private final Item item;

        Lighter(Item item) {
            this.item = item;
        }

        public ItemStack icon() {
            return new ItemStack(item);
        }
    }

    // Lit TNT is 0.98 tall and goes off a sixteenth of the way up.
    private static final double BLAST_HEIGHT = 0.98 * 0.0625;

    // How far the guess follows lit TNT down before it stops looking for ground.
    private static final int MAX_FALL = 32;

    // Ticks a TNT just lit is left alone. It stays on screen until the server says it is gone.
    private static final int LIT_TICKS = 20;

    // Ticks a placed TNT may wait past its time for you to come back in reach before it is left unlit.
    private static final int GIVE_UP = 100;

    // Placed TNT still to be lit. The oldest drop off past this.
    private static final int MAX_WAITING = 64;

    private final BoolSetting enemies = new BoolSetting("Enemies",
        "Drops lit TNT on enemies in range. Above their head first and beside their feet otherwise.", true);
    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 5, 1, 8, 0.5, " blocks")
        .under(enemies);
    private final EnumSetting<TargetPriority> priority = TargetPriority.setting("Blows up",
        TargetPriority.NEAREST).under(enemies);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between one TNT and the next.", 20, 0, 80, 1, " ticks").min(0)
        .under(enemies);
    private final NumberSetting maxSelfDamage = new NumberSetting("Max self damage",
        "Never drop TNT whose blast would hurt you by more than this where you stand.", 6, 0, 20, 0.5)
        .min(0).under(enemies);
    private final BoolSetting antiSuicide = new BoolSetting("Anti suicide",
        "Never drop TNT whose blast could kill you where you stand.", true)
        .under(enemies);
    private final BoolSetting lightPlaced = new BoolSetting("Light placed",
        "Lights each TNT you place from either hand or through another module such as TrailMaker."
            + " The fuse is yours to walk away from.", false);
    private final NumberSetting lightAfter = new NumberSetting("Light after",
        "Ticks a placed TNT waits before it is lit. One out of reach by then is lit once you come back.",
        0, 0, 40, 1, " ticks").min(0).under(lightPlaced);
    private final RankSetting<Lighter> lighters = new RankSetting<>("Set off with",
        "What sets the TNT off. The first one on the list that is at hand and fits is used."
            + " Fire takes its lighter from Light with.",
        Lighter.class, Lighter::icon);
    private final Ignition ignition = new Ignition();
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards each block.", true);

    private final SlotSwap slots = new SlotSwap();
    private final Cooldowns<BlockPos> justLit = new Cooldowns<>();
    private final ChatWarning warning = new ChatWarning();

    // Placed TNT and the tick it is due to be lit.
    private final Map<BlockPos, Integer> waiting = new BoundedMap<>(MAX_WAITING);

    // Set whilst this module clicks. Its own clicks are never taken for a placement.
    private boolean clicking;

    private int timer;
    private String targetName;

    public TntAura() {
        super("TntAura", "Places lit TNT on enemies and lights the TNT you place.", Category.COMBAT);
        addSettings(enemies, targetRange, priority, delay, maxSelfDamage, antiSuicide, lightPlaced, lightAfter,
            lighters);
        addSettings(ignition.settings());
        addSettings(rotate);
        searchTags("tnt", "auto tnt", "explode", "bomb", "ignite");
    }

    @Override
    public String getSuffix() {
        return targetName != null ? targetName : count(waiting.size(), "waiting");
    }

    @Override
    protected void onEnable() {
        timer = 0;
        targetName = null;
        slots.forget();
        justLit.clear();
        waiting.clear();
        warning.clear();
    }

    @Override
    protected void onDisable() {
        slots.restoreIfMine();
        targetName = null;
        waiting.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        targetName = null;
        if (!inGame()) {
            return;
        }
        // The clock starts again on a respawn and every waiting TNT is out of date.
        if (justLit.tick()) {
            waiting.clear();
        }
        if (mc.player.isSpectator()) {
            return;
        }
        if (!lightPlaced.isOn()) {
            waiting.clear();
        } else {
            lightWaiting();
        }
        if (enemies.isOn()) {
            attack();
        }
    }

    // Each TNT a use places joins the wait whatever placed it. The client puts the block in its
    // own world before the packet leaves and it is already there to see.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (clicking || !lightPlaced.isOn() || !(event.getPacket() instanceof ServerboundUseItemOnPacket packet)
            || !inGame() || !placesTnt(packet)) {
            return;
        }
        BlockHitResult hit = packet.hitResult();
        for (BlockPos spot : List.of(hit.getBlockPos(), hit.getBlockPos().relative(hit.getDirection()))) {
            if (BlockUtil.state(spot).is(Blocks.TNT) && !justLit.contains(spot)) {
                waiting.putIfAbsent(spot, mc.player.tickCount + lightAfter.getInt());
            }
        }
    }

    // A use with TNT in hand. A player's click is judged by what the hand held as it began
    // because placing the last TNT leaves the hand empty. Lighting clicks never count.
    private static boolean placesTnt(ServerboundUseItemOnPacket packet) {
        Item before = UseClick.heldAtClick(packet.hand());
        if (before != null) {
            return before == Items.TNT;
        }
        ItemStack now = mc.player.getItemInHand(packet.hand());
        return now.is(Items.TNT) || now.isEmpty();
    }

    private void lightWaiting() {
        int now = mc.player.tickCount;
        Iterator<Map.Entry<BlockPos, Integer>> it = waiting.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            BlockPos spot = entry.getKey();
            int late = now - entry.getValue();
            if (late < 0) {
                continue;
            }
            if (late > GIVE_UP || !BlockUtil.state(spot).is(Blocks.TNT) || justLit.contains(spot)) {
                it.remove();
                continue;
            }
            // The reach is checked again as the wait ends. The TNT waits for you to come back.
            if (!BlockUtil.inReach(spot)) {
                continue;
            }
            if (firstLighter(spot) == null) {
                warning.say("Nothing in your offhand or hotbar can light TNT.");
                continue;
            }
            if (!blowUp(spot)) {
                return;
            }
            it.remove();
        }
    }

    private void attack() {
        if (timer > 0) {
            timer--;
            return;
        }
        Player enemy = (Player) EntityUtil.best(targetRange.getValue(), priority.getValue(),
            entity -> EntityUtil.isEnemy(entity) && spotFor(entity) != null);
        targetName = EntityUtil.nameOf(enemy);
        if (enemy == null) {
            return;
        }
        BlockPos spot = spotFor(enemy);
        if (spot != null && blowUp(spot)) {
            timer = delay.getInt();
        }
    }

    // Over the head the lit TNT falls to the feet. Beside the feet it goes off there.
    private BlockPos spotFor(Entity enemy) {
        BlockPos feet = enemy.blockPosition();
        List<BlockPos> spots = List.of(feet.above(2),
            feet.north(), feet.south(), feet.west(), feet.east());
        for (BlockPos spot : spots) {
            if (!justLit.contains(spot) && fits(spot) && safe(spot)) {
                return spot;
            }
        }
        return null;
    }

    // Unlit TNT already sitting there only needs lighting.
    private boolean fits(BlockPos spot) {
        return BlockUtil.inReach(spot) && (BlockUtil.state(spot).is(Blocks.TNT) || BlockUtil.blockFits(spot));
    }

    private boolean safe(BlockPos spot) {
        return ExplosionUtil.selfSafe(blastPoint(spot), ExplosionUtil.TNT_POWER, maxSelfDamage.getFloat(),
            antiSuicide.isOn());
    }

    // Lit TNT never floats. It drops onto the first block with a top and blows there.
    private Vec3 blastPoint(BlockPos spot) {
        BlockPos cell = spot;
        for (int fallen = 0; fallen < MAX_FALL; fallen++) {
            BlockPos below = cell.below();
            VoxelShape floor = BlockUtil.state(below).getCollisionShape(mc.level, below);
            if (!floor.isEmpty()) {
                return new Vec3(spot.getX() + 0.5, below.getY() + floor.max(Direction.Axis.Y) + BLAST_HEIGHT,
                    spot.getZ() + 0.5);
            }
            cell = below;
        }
        return Vec3.atBottomCenterOf(cell);
    }

    // Places the TNT unless it is already there and lights it straight after. Each click is a
    // use packet and all of them have to fit under the server's limit.
    private boolean blowUp(BlockPos spot) {
        Lighter lighter = firstLighter(spot);
        boolean placing = !BlockUtil.state(spot).is(Blocks.TNT);
        int clicks = placing ? 2 : 1;
        if (lighter == null || UseBudget.remaining() < clicks) {
            return false;
        }
        clicking = true;
        boolean lit;
        try {
            lit = placeAndLight(lighter, spot, placing);
        } finally {
            clicking = false;
            slots.restore();
        }
        if (lit) {
            justLit.put(spot, LIT_TICKS);
            warning.clear();
        }
        return lit;
    }

    private boolean placeAndLight(Lighter lighter, BlockPos spot, boolean placing) {
        if (placing) {
            int tnt = InventoryUtil.hotbarSlot(stack -> stack.is(Items.TNT));
            if (tnt == -1) {
                return false;
            }
            slots.select(tnt);
            if (!BlockUtil.placeAny(spot, rotate.isOn(), true)) {
                return false;
            }
        }
        return light(lighter, spot);
    }

    // The first lighter on the list that is at hand and has somewhere to go.
    private Lighter firstLighter(BlockPos spot) {
        for (Lighter lighter : lighters.ranked()) {
            boolean ready = switch (lighter) {
                case FIRE -> ignition.atHand();
                case REDSTONE_BLOCK -> inHotbar(lighter) && powerSpot(spot) != null;
                case REDSTONE_TORCH -> inHotbar(lighter) && torchSpot(spot) != null;
            };
            if (ready) {
                return lighter;
            }
        }
        return null;
    }

    private static boolean inHotbar(Lighter lighter) {
        return InventoryUtil.hotbarSlot(stack -> stack.is(lighter.item)) != -1;
    }

    private boolean light(Lighter lighter, BlockPos spot) {
        if (lighter == Lighter.FIRE) {
            return strike(spot);
        }
        slots.select(InventoryUtil.hotbarSlot(stack -> stack.is(lighter.item)));
        if (lighter == Lighter.REDSTONE_BLOCK) {
            BlockPos power = powerSpot(spot);
            return power != null && BlockUtil.placeAny(power, rotate.isOn(), true);
        }
        BlockPos torch = torchSpot(spot);
        return torch != null && BlockUtil.place(torch, Direction.DOWN, rotate.isOn(), true);
    }

    // A click on the TNT itself with flint and steel or a fire charge primes it. One in the
    // offhand needs no swap. BlockUtil.interact lets go of the sneak for the click and the
    // server primes the TNT rather than setting fire beside it.
    private boolean strike(BlockPos spot) {
        Direction side = BlockUtil.facingSide(spot);
        if (rotate.isOn()) {
            BlockUtil.faceVector(BlockUtil.hitPoint(spot, side));
        }
        return ignition.use(slots, hand -> BlockUtil.interact(spot, side, hand));
    }

    // A redstone block powers TNT from any side.
    private BlockPos powerSpot(BlockPos spot) {
        for (Direction side : Direction.values()) {
            BlockPos next = spot.relative(side);
            if (BlockUtil.blockFits(next) && BlockUtil.inReach(next)) {
                return next;
            }
        }
        return null;
    }

    // A redstone torch stands on the ground beside the TNT. One on top never powers it.
    private BlockPos torchSpot(BlockPos spot) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos next = spot.relative(side);
            if (BlockUtil.torchStands(next) && BlockUtil.inReach(next)) {
                return next;
            }
        }
        return null;
    }
}
