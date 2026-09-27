package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WeatheringCopper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

// Every click is made sneaking. The server then skips the door or chest or statue under
// the click and a shield in the other hand cannot hold the axe back.
public final class WaxAura extends Module {

    // A click the server turned down puts the block back. It is left alone this long.
    private static final int RETRY_TICKS = 20;

    // One copper block as it looked when it was clicked.
    private record Click(BlockPos pos, Block block) {
    }

    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes to reach.", 5, 1, 6, 0.1);
    private final BoolSetting scrape = new BoolSetting("Scrape first",
        "Scrapes oxidation off with an axe from the hotbar before waxing. Each click takes off one stage.",
        false);
    private final BoolSetting multi = new BoolSetting("Multi wax",
        "Works on every block in reach in one tick. Fast but obvious to an anti cheat.", false);
    private final BoolSetting lineOfSight = new BoolSetting("Line of sight",
        "Only waxes blocks you can see from where you stand.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards the block on the server side.", true);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);

    private final InventoryUtil.SlotSwap slots = new InventoryUtil.SlotSwap();
    private final Cooldowns<Click> tried = new Cooldowns<>();
    private int waxed;

    public WaxAura() {
        super("WaxAura", "Waxes the copper around you with honeycomb from the hotbar.", Category.WORLD);
        addSettings(range, scrape, multi, lineOfSight, rotate, swing);
        searchTags("honeycomb", "copper", "oxidation", "scrape");
    }

    @Override
    public String getSuffix() {
        return count(waxed, "waxed");
    }

    @Override
    protected void onEnable() {
        tried.clear();
        waxed = 0;
    }

    @Override
    protected void onDisable() {
        slots.restoreIfMine();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.gameMode == null || mc.player.isSpectator() || mc.gui.screen() != null) {
            return;
        }
        if (mc.gameMode.isDestroying() || mc.player.isHandsBusy() || mc.player.isUsingItem()) {
            return;
        }
        if (!multi.isOn() && mc.rightClickDelay > 0) {
            return;
        }
        tried.tick();
        List<BlockPos> toScrape = new ArrayList<>();
        List<BlockPos> toWax = new ArrayList<>();
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            Block block = BlockUtil.state(pos).getBlock();
            if (!HoneycombItem.WAXABLES.get().containsKey(block) || tried.contains(new Click(pos, block))) {
                continue;
            }
            if (lineOfSight.isOn() && !BlockUtil.canSee(pos)) {
                continue;
            }
            boolean oxidised = WeatheringCopper.getPrevious(block).isPresent();
            (scrape.isOn() && oxidised ? toScrape : toWax).add(pos);
        }
        // A block waits for its wax until the axe has nothing left to take off.
        if (!work(toScrape, WaxAura::isAxe)) {
            work(toWax, stack -> stack.is(Items.HONEYCOMB));
        }
    }

    // Axes about to break are left for the player to mend.
    private static boolean isAxe(ItemStack stack) {
        return stack.is(ItemTags.AXES) && !ItemUtil.nearlyBroken(stack);
    }

    // Clicks the nearest target or all of them with Multi wax. True when any click was taken.
    private boolean work(List<BlockPos> targets, Predicate<ItemStack> tool) {
        if (targets.isEmpty()) {
            return false;
        }
        int slot = InventoryUtil.hotbarSlot(tool);
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        boolean used = InputUtil.whileSneaking(() -> {
            boolean any = false;
            for (BlockPos pos : targets) {
                if (!tool.test(mc.player.getMainHandItem())) {
                    break;
                }
                Block before = BlockUtil.state(pos).getBlock();
                if (!BlockUtil.useOn(pos, rotate.isOn() && !any, false)) {
                    continue;
                }
                tried.put(new Click(pos, before), RETRY_TICKS);
                if (HoneycombItem.WAX_OFF_BY_BLOCK.get().containsKey(BlockUtil.state(pos).getBlock())) {
                    waxed++;
                }
                any = true;
                if (!multi.isOn()) {
                    break;
                }
            }
            return any;
        });
        if (used) {
            swing.getValue().swing(InteractionHand.MAIN_HAND);
            mc.rightClickDelay = InputUtil.USE_DELAY;
        }
        slots.restoreIfMine();
        return used;
    }
}
