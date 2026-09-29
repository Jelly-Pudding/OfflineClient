package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

// Lava a module pours and takes back up again. Only its own pours are ever scooped and a
// pour whose flow could run into the player before the scoop never happens. The scoops
// still owed when the module stops go on after it.
public final class LavaPours {

    private static final Minecraft MC = OfflineClient.MC;

    // A spot lava can go and the point the view turns to for the bucket.
    public record Pour(BlockPos spot, Vec3 aim) {
    }

    // Ticks a pour waits to be scooped once it is due before it is left where it is.
    private static final int GIVE_UP = 100;

    public static final String NO_BUCKET = "You have no lava bucket.";

    private final BoolSetting takeBack = new BoolSetting("Take back",
        "Scoops the lava back up with an empty bucket after a while.", true);
    private final NumberSetting takeBackAfter = new NumberSetting("Take back after",
        "How long the lava stays before it is scooped. Lava flows a block every 30 ticks or 10 in the Nether"
            + " and a shorter wait lets it go down closer to you.",
        40, 5, 200, 5, " ticks").min(1).under(takeBack);

    // Where lava went down and the tick it did. Only kept whilst it is to be taken back.
    private final Map<BlockPos, Integer> poured = new LinkedHashMap<>();

    // Borrows the empty buckets for the scoops that go on after the module stops.
    private final InventoryUtil.HotbarLoan afterStop = new InventoryUtil.HotbarLoan();

    public Setting<?>[] settings() {
        return new Setting<?>[] {takeBack, takeBackAfter};
    }

    // Shows both rows only whilst the parent holds the value.
    public <E extends Enum<E>> LavaPours under(EnumSetting<E> parent, E value) {
        takeBack.under(parent, value);
        return this;
    }

    // A pour into the spot that can never reach you. Null when no bucket can pour there or
    // the flow could run into you.
    public Pour plan(BlockPos spot) {
        if (!takesLava(spot)) {
            return null;
        }
        Vec3 aim = Buckets.pourAim(spot);
        if (aim == null) {
            return null;
        }
        return LavaReach.touches(spot, MC.player.getBoundingBox(), flowSteps()) ? null : new Pour(spot, aim);
    }

    // Empties a lava bucket from anywhere in the inventory. The spot is kept to scoop later.
    // True when the game took the use.
    public boolean pour(InventoryUtil.HotbarLoan loan, Pour pour) {
        if (!Buckets.use(loan, Items.LAVA_BUCKET, pour.aim())) {
            return false;
        }
        if (takeBack.isOn()) {
            poured.put(pour.spot(), MC.player.tickCount);
        }
        return true;
    }

    // Scoops the oldest pour that is due. True when a bucket was used this tick. A pour made
    // whilst Take back was on is still scooped once it is switched off.
    public boolean scoopDue(InventoryUtil.HotbarLoan loan) {
        int now = MC.player.tickCount;
        int wait = takeBackAfter.getInt();
        Iterator<Map.Entry<BlockPos, Integer>> it = poured.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            int age = now - entry.getValue();
            BlockPos pos = entry.getKey();
            // The clock starts again on a respawn and an age below nought is out of date.
            if (age < 0 || age > wait + GIVE_UP || !MC.level.getFluidState(pos).isSourceOfType(Fluids.LAVA)) {
                it.remove();
                continue;
            }
            if (age < wait) {
                continue;
            }
            Vec3 aim = Buckets.scoopAim(pos);
            if (aim != null && Buckets.fill(loan, aim)) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    // A module calls this as it starts. Its own ticks scoop whatever is still owed.
    public void start() {
        OfflineClient.INSTANCE.getEventBus().unregister(this);
    }

    // A module calls this as it stops. Each pour only passed the flow test because it gets
    // scooped. The scoops go on until none is owed.
    public void stop() {
        if (!poured.isEmpty()) {
            OfflineClient.INSTANCE.getEventBus().register(this);
        }
    }

    // Runs only between a stop and the last scoop owed.
    @Subscribe
    private void onTick(TickEvent event) {
        if (!MC.player.isSpectator() && UseBudget.remaining() > 0) {
            scoopDue(afterStop);
        }
        if (poured.isEmpty()) {
            OfflineClient.INSTANCE.getEventBus().unregister(this);
        }
    }

    // True whilst a lava bucket sits anywhere in the inventory.
    public static boolean carried() {
        return InventoryUtil.findSlot(Items.LAVA_BUCKET, InventoryUtil.WHOLE_INVENTORY) != -1;
    }

    // Lava taken back only flows for the steps that fit before the scoop. One step more
    // covers a scoop that lands late.
    private int flowSteps() {
        if (!takeBack.isOn()) {
            return Integer.MAX_VALUE;
        }
        return takeBackAfter.getInt() / Fluids.LAVA.getTickDelay(MC.level) + 1;
    }

    // A bucket fills air or any block that is not solid. Lava poured into water only hardens.
    public static boolean takesLava(BlockPos pos) {
        if (!MC.level.isInWorldBounds(pos)) {
            return false;
        }
        BlockState state = BlockUtil.state(pos);
        return state.getFluidState().isEmpty() && state.canBeReplaced(Fluids.LAVA);
    }
}
