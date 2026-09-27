package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RankSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

// Sets lit TNT on enemies or on a block you click. Lit TNT drops to the ground before
// it goes off and TNT set over a head lands at the feet below.
public final class TntAura extends Module {

    public enum Target { ENEMIES, CLICK }

    public enum Lighter {
        FLINT_AND_STEEL(Items.FLINT_AND_STEEL),
        FIRE_CHARGE(Items.FIRE_CHARGE),
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

    private final EnumSetting<Target> target = new EnumSetting<>("Target",
        "Where the TNT goes.", Target.ENEMIES)
        .describe(Target.ENEMIES, "On enemies in range. Above their head first and beside their feet otherwise.")
        .describe(Target.CLICK, "On the block you right click whilst you hold TNT.");
    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 5, 1, 8, 0.5, " blocks")
        .under(target, Target.ENEMIES);
    private final EnumSetting<TargetPriority> priority = TargetPriority.setting("Blows up",
        TargetPriority.NEAREST).under(target, Target.ENEMIES);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between one TNT and the next.", 20, 0, 80, 1, " ticks").min(0)
        .under(target, Target.ENEMIES);
    private final RankSetting<Lighter> lighters = new RankSetting<>("Light with",
        "What lights the TNT. The first one on the list that is in your hotbar is used.",
        Lighter.class, Lighter::icon);
    private final NumberSetting maxSelfDamage = new NumberSetting("Max self damage",
        "Never light TNT whose blast would hurt you by more than this where you stand.", 6, 0, 20, 0.5)
        .min(0);
    private final BoolSetting antiSuicide = new BoolSetting("Anti suicide",
        "Never light TNT whose blast could kill you where you stand.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards each block.", true);

    private final SlotSwap slots = new SlotSwap();
    private final Cooldowns<BlockPos> justLit = new Cooldowns<>();
    private int timer;
    private String targetName;

    public TntAura() {
        super("TntAura", "Places lit TNT on enemies or where you click.", Category.COMBAT);
        addSettings(target, targetRange, priority, delay, lighters, maxSelfDamage, antiSuicide, rotate);
        searchTags("tnt", "auto tnt", "explode", "bomb");
    }

    @Override
    public String getSuffix() {
        return targetName;
    }

    @Override
    protected void onEnable() {
        timer = 0;
        targetName = null;
        slots.forget();
        justLit.clear();
    }

    @Override
    protected void onDisable() {
        slots.restoreIfMine();
        targetName = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            targetName = null;
            return;
        }
        justLit.tick();
        if (mc.player.isSpectator() || !target.is(Target.ENEMIES)) {
            targetName = null;
            return;
        }
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

    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (!inGame() || !target.is(Target.CLICK) || !mc.player.getMainHandItem().is(Items.TNT)) {
            return;
        }
        BlockHitResult hit = BlockUtil.aimedBlock();
        if (hit == null) {
            return;
        }
        // Without this delay vanilla clicks again in the same tick and on every tick the button is held.
        mc.rightClickDelay = InputUtil.USE_DELAY;
        event.cancel();
        BlockPos spot = BlockUtil.placeSpot(hit);
        if (justLit.contains(spot)) {
            return;
        }
        if (!fits(spot)) {
            ChatUtil.error("There is no room for TNT there.");
        } else if (!safe(spot)) {
            ChatUtil.error("That blast would hurt you too much.");
        } else if (firstLighter(spot) == null) {
            ChatUtil.error("Nothing in your hotbar can light TNT.");
        } else {
            blowUp(spot);
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

    // Places the TNT unless it is already there and lights it straight after.
    private boolean blowUp(BlockPos spot) {
        Lighter lighter = firstLighter(spot);
        if (lighter == null) {
            return false;
        }
        if (!BlockUtil.state(spot).is(Blocks.TNT)) {
            int tnt = InventoryUtil.hotbarSlot(stack -> stack.is(Items.TNT));
            if (tnt == -1) {
                return false;
            }
            slots.select(tnt);
            if (!BlockUtil.placeAny(spot, rotate.isOn(), true)) {
                slots.restore();
                return false;
            }
        }
        boolean lit = light(lighter, spot);
        slots.restore();
        if (lit) {
            justLit.put(spot, LIT_TICKS);
        }
        return lit;
    }

    // The first lighter on the list that is in the hotbar and has somewhere to go.
    private Lighter firstLighter(BlockPos spot) {
        for (Lighter lighter : lighters.ranked()) {
            if (InventoryUtil.hotbarSlot(stack -> stack.is(lighter.item)) == -1) {
                continue;
            }
            if (lighter == Lighter.REDSTONE_BLOCK && powerSpot(spot) == null) {
                continue;
            }
            if (lighter == Lighter.REDSTONE_TORCH && torchSpot(spot) == null) {
                continue;
            }
            return lighter;
        }
        return null;
    }

    private boolean light(Lighter lighter, BlockPos spot) {
        slots.select(InventoryUtil.hotbarSlot(stack -> stack.is(lighter.item)));
        return switch (lighter) {
            case FLINT_AND_STEEL, FIRE_CHARGE -> strike(spot);
            case REDSTONE_BLOCK -> {
                BlockPos power = powerSpot(spot);
                yield power != null && BlockUtil.placeAny(power, rotate.isOn(), true);
            }
            case REDSTONE_TORCH -> {
                BlockPos torch = torchSpot(spot);
                yield torch != null && BlockUtil.place(torch, Direction.DOWN, rotate.isOn(), true);
            }
        };
    }

    // A click on the TNT itself with flint and steel or a fire charge primes it.
    // BlockUtil.interact lets go of the sneak for the click and the server primes the TNT
    // rather than setting fire beside it.
    private boolean strike(BlockPos spot) {
        Direction side = BlockUtil.facingSide(spot);
        if (rotate.isOn()) {
            BlockUtil.faceVector(BlockUtil.hitPoint(spot, side));
        }
        return BlockUtil.interact(spot, side);
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
