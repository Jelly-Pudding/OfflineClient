package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RankSetting;
import com.jellypudding.offlineclient.util.BlastLimits;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.CoverMiner;
import com.jellypudding.offlineclient.util.CrystalGuard;
import com.jellypudding.offlineclient.util.Crystals;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.Holes;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

// Strips the blast proof cover from an enemy. With a crystal of its own the block comes out
// from under or beside the crystal and the hit follows the packet that breaks it. The server
// reads the two in order and the blast finds the gap before anyone can fill it.
public final class AutoCity extends Module {

    public enum Plan { BURROW, SIDE, HEAD, TRAP }

    // Ticks a crystal that was placed gets to show up before another goes down.
    private static final int PLACE_WAIT = 6;

    // The crystal goes down once the block is this many ticks from breaking. Long enough for
    // the server to answer and short enough that nobody gets to hit it first.
    private static final int CRYSTAL_LEAD = 10;

    // Tries at a crystal for one block before it is mined without one.
    private static final int MAX_CRYSTALS = 3;

    // Ticks the job may stop fitting before it is given up. A jump or a blast lifts the
    // target out of its block for less than that.
    private static final int STRAY_TICKS = 10;

    private static final int NEVER = Integer.MIN_VALUE / 2;

    // The block being mined for the plan that picked it and the enemy it was picked for.
    private record Job(Plan plan, BlockPos block, Player target) {
    }

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 6, 1, 10, 0.5, " blocks");
    private final EnumSetting<TargetPriority> priority = TargetPriority.setting("Cities",
        TargetPriority.NEAREST);
    private final RankSetting<Plan> plans = new RankSetting<>("Plans",
        "Which block of the cover of an enemy to mine. Burrow is the block they stand in and Side one beside their feet"
            + " and Head the one over their head and Trap one beside their head. The first on the list that"
            + " is there to mine is taken.",
        Plan.class, null, plan -> plan == Plan.BURROW || plan == Plan.SIDE);
    private final NumberSetting breakRange = new NumberSetting("Break range",
        "How far you can reach to mine and to hit the crystal.", 4.5, 1, 6, 0.1, " blocks");
    private final NumberSetting placeRange = new NumberSetting("Place range",
        "How far you can reach to place the crystal and the support block.", 4.5, 1, 6, 0.1, " blocks")
        .visibleWhen(() -> places());
    private final EnumSetting<SwingMode> swing = SwingMode.settingWithUses(SwingMode.BOTH);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns towards the block on the server side.", true);
    private final CoverMiner miner = new CoverMiner(rotate::isOn, swing::getValue);
    private final BoolSetting crystalFirst = new BoolSetting("Place crystal first",
        "Puts a crystal beside or on the block before it breaks and hits it the moment the block goes.", false);
    private final BlastLimits limits = new BlastLimits(8, 20).under(crystalFirst);
    private final BoolSetting breakAgain = new BoolSetting("Break again",
        "Stays on the spot and breaks the block with one packet each time the enemy puts it back.", false);
    private final BoolSetting support = new BoolSetting("Support",
        "Puts obsidian under a side block that has nothing below it to give a crystal a base.", true)
        .visibleWhen(() -> minesSides());
    private final BoolSetting pauseOnUse = new BoolSetting("Pause on use",
        "Holds off whilst you use an item. A hotbar swap ends anything used from the main hand.", true);
    private final BoolSetting toggleOff = new BoolSetting("Toggle off when done",
        "Turns off once the block is gone.", true)
        .visibleWhen(() -> !breakAgain.isOn());
    private final BoolSetting chatInfo = new BoolSetting("Chat info",
        "Says in chat that the block is down as the module turns off.", true)
        .under(toggleOff);
    private final BoolSetting render = new BoolSetting("Show target",
        "Outlines the block being mined and the spot of the crystal.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 0f).under(render);

    // Holds the crystals and the support block for one click each.
    private final SlotSwap hands = new SlotSwap();
    private final CrystalGuard crystals = new CrystalGuard();
    private Job job;
    // Ticks in a row the job has not fit its target or a plan higher up has had a block.
    private int strayed;
    // Where the crystal for the job stands or goes. Null whilst none is used.
    private BlockPos crystalSpot;
    // True from the moment the crystal is due until it goes off with the block.
    private boolean crystalDue;
    private int placedAt = NEVER;
    private int crystalTries;
    private String targetName;
    private String status;

    public AutoCity() {
        super("AutoCity", "Mines the block guarding an enemy in a hole.", Category.COMBAT);
        addSettings(targetRange, priority, plans, breakRange, placeRange);
        addSettings(miner.settings());
        addSettings(swing, rotate, crystalFirst);
        addSettings(limits.settings());
        addSettings(breakAgain, support, pauseOnUse, toggleOff, chatInfo, render);
        addSettings(style.settings());
        searchTags("city", "surround", "obsidian", "burrow", "trap", "cev", "crystal");
    }

    @Override
    public String getSuffix() {
        return suffix(targetName, status);
    }

    private boolean minesSides() {
        return plans.ranked().contains(Plan.SIDE);
    }

    // True whilst anything of the settings can make the module place a block or a crystal.
    private boolean places() {
        return crystalFirst.isOn() || (support.isOn() && minesSides());
    }

    // True whilst the crystal on this spot is the one the break waits for. CrystalAura leaves
    // it standing.
    public boolean holds(BlockPos spot) {
        return job != null && crystalDue && spot.equals(crystalSpot);
    }

    // True whilst the tool has to stay in hand. CrystalAura then hands the slot back every tick.
    public boolean keepsTool() {
        return job != null && miner.keepsTool();
    }

    @Override
    protected void onEnable() {
        forget();
        hands.forget();
        crystals.forget();
        targetName = null;
    }

    @Override
    protected void onDisable() {
        letGo();
        targetName = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        status = null;
        if (mc.player.isSpectator()) {
            return;
        }
        crystals.tick();
        Player target = target();
        targetName = EntityUtil.displayNameOf(target);
        if (target == null) {
            letGo();
            return;
        }
        chooseJob(target);
        if (job == null) {
            return;
        }
        if (standsAside()) {
            miner.rest();
            status = "(paused)";
            return;
        }
        if (miner.waiting()) {
            status = "(waiting to mine)";
            return;
        }
        if (BlockUtil.state(job.block()).isAir()) {
            blockGone();
        } else {
            mine(target);
        }
    }

    // The enemy of the job in hand for as long as they stay in range. Otherwise the one the
    // priority picks.
    private Player target() {
        Player held = job == null ? null : job.target();
        if (held != null && held.level() == mc.level && EntityUtil.isEnemy(held)
            && mc.player.distanceTo(held) <= targetRange.getValue()) {
            return held;
        }
        return EntityUtil.bestEnemy(targetRange.getValue(), priority.getValue());
    }

    // The job in hand is kept whilst it fits its target and no plan higher up the list has a
    // block to mine. Anything else has to last a few ticks before the job follows it.
    private void chooseJob(Player target) {
        Job best = bestJob(target);
        if (job == null || job.target() != target || !usable(job)) {
            replace(best);
        } else if (fits(job) && !outranks(best)) {
            strayed = 0;
        } else if (++strayed > STRAY_TICKS) {
            replace(best);
        }
    }

    private void replace(Job next) {
        if (!sameJob(next)) {
            letGo();
            job = next;
        }
        strayed = 0;
    }

    private boolean sameJob(Job other) {
        return job != null && job.equals(other);
    }

    private boolean outranks(Job other) {
        return other != null && rankOf(other.plan()) < rankOf(job.plan());
    }

    private Job bestJob(Player target) {
        for (Plan plan : plans.ranked()) {
            BlockPos block = blockFor(plan, target);
            if (block != null) {
                return new Job(plan, block.immutable(), target);
            }
        }
        return null;
    }

    private int rankOf(Plan plan) {
        return plans.ranked().indexOf(plan);
    }

    private BlockPos blockFor(Plan plan, Player target) {
        BlockPos head = Holes.headBlock(target);
        return switch (plan) {
            case BURROW -> Holes.burrowed(target) && mineable(target.blockPosition())
                ? target.blockPosition() : null;
            case SIDE -> Holes.cityBlock(target, breakRange.getValue());
            case HEAD -> mineable(head.above()) ? head.above() : null;
            case TRAP -> Holes.cityBlock(head, breakRange.getValue());
        };
    }

    // Cover worth the time is blast proof. Anything weaker goes with the first crystal.
    private boolean mineable(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        return !state.isAir() && BlockUtil.isBreakable(pos) && BlockUtil.isBlastProof(state)
            && BlockUtil.distanceTo(pos) <= breakRange.getValue();
    }

    // A job whose plan has left the list or whose block is out of reach goes at once.
    private boolean usable(Job held) {
        return plans.ranked().contains(held.plan()) && BlockUtil.distanceTo(held.block()) <= breakRange.getValue();
    }

    // True whilst the block still sits where its plan looks. A spot kept clear under Break
    // again is empty and only its place counts.
    private static boolean fits(Job held) {
        Player target = held.target();
        BlockPos block = held.block();
        BlockPos head = Holes.headBlock(target);
        return switch (held.plan()) {
            case BURROW -> block.equals(target.blockPosition());
            case SIDE -> beside(block, target.blockPosition());
            case HEAD -> block.equals(head.above());
            case TRAP -> beside(block, head);
        };
    }

    private static boolean beside(BlockPos block, BlockPos centre) {
        return block.getY() == centre.getY() && block.distManhattan(centre) == 1;
    }

    // A feeder or the packer has the hand and a swap would break what it is doing.
    private boolean standsAside() {
        return Hands.slotsTaken() || (pauseOnUse.isOn() && mc.player.isUsingItem());
    }

    private void mine(Player target) {
        BlockPos block = job.block();
        // The stop that should break the block is on its way and the hand is not needed.
        if (miner.settling()) {
            miner.rest();
            return;
        }
        // A crystal needs a base under the gap the block leaves behind.
        if (job.plan() == Plan.SIDE && support.isOn() && placeSupport(block.below())) {
            return;
        }
        EndCrystal crystal = keepCrystal(target, block);
        // Without a spot for a crystal the block is simply mined.
        boolean mayFinish = crystalSpot == null || crystal != null;
        miner.drive(block, mayFinish, () -> pop(crystal));
    }

    // Looks after the crystal of the job. The spot is picked at once and the block then
    // waits for the crystal. The crystal itself only goes down near the end. Null whilst
    // none stands on the spot.
    private EndCrystal keepCrystal(Player target, BlockPos block) {
        EndCrystal standing = crystalSpot == null ? null : crystalAt(crystalSpot);
        if (standing == null && !awaitingCrystal()) {
            if (!crystalFirst.isOn() || crystalTries >= MAX_CRYSTALS || !Crystals.hasCrystals()) {
                dropCrystal();
                return null;
            }
            crystalSpot = bestSpot(target, block);
            if (crystalSpot == null) {
                crystalDue = false;
                return null;
            }
            standing = crystalAt(crystalSpot);
        }
        crystalDue = miner.ticksLeft(block) <= CRYSTAL_LEAD;
        if (standing == null && crystalDue && !awaitingCrystal()) {
            placeCrystal(crystalSpot);
        }
        return standing;
    }

    private boolean awaitingCrystal() {
        int since = mc.player.tickCount - placedAt;
        return crystalSpot != null && since >= 0 && since < PLACE_WAIT;
    }

    // The spot on top of the block or beside it where a blast would hurt the target most
    // once the block is gone.
    private BlockPos bestSpot(Player target, BlockPos block) {
        BlockPos best = null;
        float bestDamage = 0;
        for (Direction way : Direction.values()) {
            if (way == Direction.DOWN) {
                continue;
            }
            BlockPos spot = block.relative(way);
            if (!crystalFits(spot)) {
                continue;
            }
            Vec3 blast = Vec3.atBottomCenterOf(spot);
            float damage = ExplosionUtil.blastDamage(target, blast, ExplosionUtil.CRYSTAL_POWER, Vec3.ZERO, block);
            if (damage > bestDamage && limits.allows(blast, ExplosionUtil.CRYSTAL_POWER, block)) {
                bestDamage = damage;
                best = spot;
            }
        }
        return best;
    }

    // A crystal stands on obsidian or bedrock in an empty block no entity touches. One of
    // ours already there is as good as placed.
    private boolean crystalFits(BlockPos spot) {
        if (!Crystals.crystalBase(BlockUtil.state(spot.below())) || !BlockUtil.state(spot).isAir()) {
            return false;
        }
        Vec3 eye = mc.player.getEyePosition();
        if (eye.distanceTo(Crystals.crystalClick(spot.below()).getLocation()) > placeRange.getValue()
            || eye.distanceTo(Vec3.atBottomCenterOf(spot)) > breakRange.getValue()) {
            return false;
        }
        return crystalAt(spot) != null
            || mc.level.getEntities((Entity) null, Crystals.crystalSpace(spot)).isEmpty();
    }

    private static EndCrystal crystalAt(BlockPos spot) {
        for (Entity entity : mc.level.getEntities((Entity) null, Crystals.crystalSpace(spot),
            entity -> entity instanceof EndCrystal)) {
            if (!entity.isRemoved() && entity.blockPosition().equals(spot)) {
                return (EndCrystal) entity;
            }
        }
        return null;
    }

    private void placeCrystal(BlockPos spot) {
        if (rotate.isOn()) {
            BlockUtil.faceVector(Crystals.crystalClick(spot.below()).getLocation());
        }
        crystalTries++;
        if (Crystals.placeCrystal(hands, spot.below(), swing.getValue())) {
            placedAt = mc.player.tickCount;
        }
        hands.restore();
    }

    // Sets the crystal off unless its blast would now break a limit. It then stays standing.
    private void pop(EndCrystal crystal) {
        if (crystal != null && limits.allows(crystal.position(), ExplosionUtil.CRYSTAL_POWER, job.block())) {
            crystals.hit(crystal, rotate.isOn(), swing.getValue());
        }
    }

    // The block is down. A crystal still on its spot goes off and the job is done unless
    // the spot is to be kept clear.
    private void blockGone() {
        EndCrystal crystal = crystalSpot == null ? null : crystalAt(crystalSpot);
        pop(crystal);
        // A crystal placed a moment ago has yet to show up and goes off once it does.
        boolean awaited = crystal == null && awaitingCrystal();
        if (!awaited) {
            dropCrystal();
            crystalTries = 0;
        }
        if (breakAgain.isOn()) {
            miner.guard(job.block());
            status = "(keeping clear)";
            return;
        }
        if (awaited) {
            return;
        }
        letGo();
        if (toggleOff.isOn()) {
            if (chatInfo.isOn()) {
                tell("took the block down.");
            }
            setEnabled(false);
        }
    }

    // Fills the hole under the block about to fall. True when obsidian went down this tick
    // and mining should wait.
    private boolean placeSupport(BlockPos below) {
        if (!BlockUtil.isReplaceable(below) || BlockUtil.intersectsPlayer(below)
            || BlockUtil.distanceTo(below) > placeRange.getValue()) {
            return false;
        }
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.OBSIDIAN));
        return BlockUtil.placeFrom(hands, slot, below, rotate.isOn());
    }

    private void letGo() {
        miner.stop();
        hands.restore();
        forget();
    }

    private void dropCrystal() {
        crystalSpot = null;
        crystalDue = false;
    }

    private void forget() {
        job = null;
        strayed = 0;
        dropCrystal();
        placedAt = NEVER;
        crystalTries = 0;
        status = null;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        Job shown = job;
        if (!render.isOn() || shown == null || !inGame()) {
            return;
        }
        if (!BlockUtil.state(shown.block()).isAir()) {
            style.draw(event.getBatch(), shown.block(), false);
        }
        BlockPos spot = crystalSpot;
        if (spot != null) {
            event.getBatch().outlineBlock(spot, style.lineColor(), false);
        }
    }
}
