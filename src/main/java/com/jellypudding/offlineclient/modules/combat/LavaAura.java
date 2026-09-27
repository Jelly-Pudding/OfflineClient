package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.Buckets;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import com.jellypudding.offlineclient.util.LavaReach;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

// Pours lava on enemies and can scoop it back up. A pour whose flow could run into
// you before it is scooped never happens.
public final class LavaAura extends Module {

    public enum Spot { FEET, HEAD }

    private record Pour(BlockPos spot, Vec3 aim) {
    }

    // Ticks a pour waits to be scooped once it is due before it is left where it is.
    private static final int GIVE_UP = 100;

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 5, 1, 8, 0.5, " blocks");
    private final EnumSetting<TargetPriority> priority = TargetPriority.setting("Burns",
        TargetPriority.NEAREST);
    private final EnumSetting<Spot> spot = new EnumSetting<>("Spot",
        "Where the lava goes.", Spot.FEET)
        .describe(Spot.FEET, "Into the block they stand in.")
        .describe(Spot.HEAD, "Above their head when a block there gives the bucket something to pour against. At their feet otherwise.");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between pours.", 10, 0, 40, 1, " ticks").min(0);
    private final BoolSetting takeBack = new BoolSetting("Take back",
        "Scoops the lava back up with an empty bucket after a while.", true);
    private final NumberSetting takeBackAfter = new NumberSetting("Take back after",
        "How long the lava stays before it is scooped. Lava flows a block every 30 ticks or 10 in the Nether"
            + " and a shorter wait lets it go down closer to you.",
        40, 5, 200, 5, " ticks").min(1).under(takeBack);

    private final HotbarLoan loan = new HotbarLoan();

    // Where lava went down and the tick it did. Only kept whilst it is to be taken back.
    private final Map<BlockPos, Integer> poured = new LinkedHashMap<>();

    private int timer;
    private String targetName;

    public LavaAura() {
        super("LavaAura", "Pours lava on enemies near you.", Category.COMBAT);
        addSettings(targetRange, priority, spot, delay, takeBack, takeBackAfter);
        searchTags("lava", "bucket", "burn");
    }

    @Override
    public String getSuffix() {
        return targetName;
    }

    @Override
    protected void onEnable() {
        timer = 0;
        targetName = null;
        poured.clear();
        loan.forget();
    }

    @Override
    protected void onDisable() {
        loan.giveBack();
        poured.clear();
        targetName = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (!takeBack.isOn()) {
            poured.clear();
        } else if (scoopDue()) {
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        Player target = (Player) EntityUtil.best(targetRange.getValue(), priority.getValue(),
            entity -> EntityUtil.isEnemy(entity) && pourFor(entity) != null);
        targetName = EntityUtil.nameOf(target);
        if (target == null) {
            return;
        }
        Pour pour = pourFor(target);
        if (pour != null && Buckets.use(loan, Items.LAVA_BUCKET, pour.aim())) {
            poured.put(pour.spot(), mc.player.tickCount);
            timer = delay.getInt();
        }
    }

    // Null unless the lava can go down on this target without any risk of reaching you.
    private Pour pourFor(Entity target) {
        BlockPos feet = target.blockPosition();
        if (!mc.level.getFluidState(feet).isEmpty()) {
            return null;
        }
        // Lava over the head runs down onto them the first time it flows.
        if (spot.is(Spot.HEAD)) {
            Pour overHead = pourAt(feet.above(2));
            if (overHead != null) {
                return overHead;
            }
        }
        return pourAt(feet);
    }

    private Pour pourAt(BlockPos at) {
        if (!takesLava(at)) {
            return null;
        }
        Vec3 aim = Buckets.pourAim(at);
        if (aim == null) {
            return null;
        }
        return LavaReach.touches(at, mc.player.getBoundingBox(), flowSteps()) ? null : new Pour(at, aim);
    }

    // Lava taken back only flows for the steps that fit before the scoop. One step more
    // covers a scoop that lands late.
    private int flowSteps() {
        if (!takeBack.isOn()) {
            return Integer.MAX_VALUE;
        }
        return takeBackAfter.getInt() / Fluids.LAVA.getTickDelay(mc.level) + 1;
    }

    // A bucket fills air or any block that is not solid. Lava poured into water only hardens.
    private boolean takesLava(BlockPos pos) {
        if (!mc.level.isInWorldBounds(pos)) {
            return false;
        }
        BlockState state = BlockUtil.state(pos);
        return state.getFluidState().isEmpty() && state.canBeReplaced(Fluids.LAVA);
    }

    // Scoops the oldest pour that is due. True when a bucket was used this tick.
    private boolean scoopDue() {
        int now = mc.player.tickCount;
        Iterator<Map.Entry<BlockPos, Integer>> it = poured.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            int age = now - entry.getValue();
            BlockPos pos = entry.getKey();
            if (age < 0 || age > takeBackAfter.getInt() + GIVE_UP
                || !mc.level.getFluidState(pos).isSourceOfType(Fluids.LAVA)) {
                it.remove();
                continue;
            }
            if (age < takeBackAfter.getInt()) {
                continue;
            }
            Vec3 aim = Buckets.scoopAim(pos);
            if (aim != null && Buckets.use(loan, Items.BUCKET, aim)) {
                it.remove();
                return true;
            }
        }
        return false;
    }
}
