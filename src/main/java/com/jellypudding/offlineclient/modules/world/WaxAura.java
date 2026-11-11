package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.BlockUseAura;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.LoadedChunks;
import com.jellypudding.offlineclient.util.SignWords;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

// Copper is clicked sneaking. The server then skips the door or chest or statue under the
// click and a shield in the other hand cannot hold the axe back. A sign is clicked standing
// because a sneaking click never reaches the sign itself.
public final class WaxAura extends BlockUseAura {

    private enum Job { SCRAPE, WAX, SIGN }

    // How often the signs around you are looked over for the outlines.
    private static final int SCAN_TICKS = 20;
    // The most signs outlined at once.
    private static final int MAX_OUTLINED = 512;

    private final BoolSetting copper = new BoolSetting("Copper",
        "Waxes the copper in reach.", true);
    private final BoolSetting scrape = new BoolSetting("Scrape first",
        "Scrapes oxidation off with an axe from the hotbar before waxing. Each click takes off one stage.",
        false).under(copper);
    private final BoolSetting signs = new BoolSetting("Signs",
        "Right clicks every written sign in reach with honeycomb to wax it but never one that runs a"
            + " command. A shop or warp sign of a plugin acts on that click.", false);
    private final BoolSetting showSigns = new BoolSetting("Show unwaxed signs",
        "Outlines the written signs around you that nobody has waxed yet and that run no command.", false);
    private final ColorSetting signColor = new ColorSetting("Sign colour",
        "Colour of the outline round an unwaxed sign.", 45, false).under(showSigns);

    private final InventoryUtil.SlotSwap slots = new InventoryUtil.SlotSwap();
    // Signs clicked with honeycomb that have not shown their wax yet.
    private final Set<BlockPos> clicked = new HashSet<>();
    private List<BlockPos> unwaxed = List.of();
    private int scanWait;
    private int waxed;

    public WaxAura() {
        super("WaxAura", "Waxes the copper and the signs around you with honeycomb from the hotbar.",
            "Multi wax");
        addSettings(range, copper, scrape, signs, multi, lineOfSight, face, swing, showSigns, signColor);
        searchTags("honeycomb", "copper", "oxidation", "scrape", "sign");
    }

    @Override
    public String getSuffix() {
        return count(waxed, "waxed");
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        clicked.clear();
        unwaxed = List.of();
        scanWait = 0;
        waxed = 0;
    }

    @Override
    protected void onDisable() {
        slots.restoreIfMine();
        unwaxed = List.of();
    }

    @Override
    protected void rest() {
        slots.restoreIfMine();
    }

    @Override
    protected void work() {
        Map<Job, List<BlockPos>> jobs = new EnumMap<>(Job.class);
        for (BlockPos pos : inReach(pos -> jobAt(pos) != null)) {
            jobs.computeIfAbsent(jobAt(pos), job -> new ArrayList<>()).add(pos);
        }
        // A block waits for its wax until the axe has nothing left to take off.
        if (!work(jobs.get(Job.SCRAPE), WaxAura::isAxe, Job.SCRAPE)
            && !work(jobs.get(Job.WAX), WaxAura::isWax, Job.WAX)) {
            work(jobs.get(Job.SIGN), WaxAura::isWax, Job.SIGN);
        }
    }

    // What there is to do to the block. Null when nothing.
    private Job jobAt(BlockPos pos) {
        Block block = BlockUtil.state(pos).getBlock();
        if (HoneycombItem.WAXABLES.get().containsKey(block)) {
            if (!copper.isOn()) {
                return null;
            }
            return scrape.isOn() && WeatheringCopper.getPrevious(block).isPresent() ? Job.SCRAPE : Job.WAX;
        }
        return signs.isOn() && mc.player.mayBuild() && waitsForWax(pos) ? Job.SIGN : null;
    }

    private static boolean waitsForWax(BlockPos pos) {
        return mc.level.getBlockEntity(pos) instanceof SignBlockEntity sign && waitsForWax(sign);
    }

    // The server runs a command in the text for the click that waxes the sign.
    private static boolean waitsForWax(SignBlockEntity sign) {
        return !sign.isWaxed() && SignWords.isWritten(sign) && !SignWords.runsClicks(sign);
    }

    private static boolean isWax(ItemStack stack) {
        return stack.is(Items.HONEYCOMB);
    }

    // Axes about to break are left for the player to mend.
    private static boolean isAxe(ItemStack stack) {
        return stack.is(ItemTags.AXES) && !ItemUtil.nearlyBroken(stack);
    }

    // Takes the tool from the hotbar and clicks the targets with it. True when any click was taken.
    private boolean work(List<BlockPos> targets, Predicate<ItemStack> tool, Job job) {
        if (targets == null) {
            return false;
        }
        int slot = InventoryUtil.hotbarSlot(tool);
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        Predicate<BlockPos> use = pos -> tool.test(mc.player.getMainHandItem()) && use(pos, job);
        boolean used = job == Job.SIGN
            ? InputUtil.whileStanding(() -> click(targets, use) > 0)
            : InputUtil.whileSneaking(() -> click(targets, use) > 0);
        slots.restoreIfMine();
        return used;
    }

    // The game shows waxed copper at once. A sign only counts once the server says it took the wax.
    private boolean use(BlockPos pos, Job job) {
        if (!BlockUtil.useOn(pos, false, false)) {
            return false;
        }
        if (job == Job.SIGN) {
            clicked.add(pos);
        } else if (HoneycombItem.WAX_OFF_BY_BLOCK.get().containsKey(BlockUtil.state(pos).getBlock())) {
            waxed++;
        }
        return true;
    }

    @Subscribe
    private void onSignTick(TickEvent event) {
        clicked.removeIf(pos -> {
            if (!(mc.level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
                return true;
            }
            if (sign.isWaxed()) {
                waxed++;
            }
            return sign.isWaxed();
        });
        if (!showSigns.isOn()) {
            unwaxed = List.of();
            scanWait = 0;
            return;
        }
        if (--scanWait > 0) {
            return;
        }
        scanWait = SCAN_TICKS;
        List<BlockPos> found = new ArrayList<>();
        LoadedChunks.forEachBlockEntity(blockEntity -> {
            if (found.size() < MAX_OUTLINED && blockEntity instanceof SignBlockEntity sign && waitsForWax(sign)) {
                found.add(sign.getBlockPos());
            }
        });
        unwaxed = found;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        int colour = signColor.getColor();
        for (BlockPos pos : unwaxed) {
            // A sign waxed since the last look loses its outline at once.
            if (waitsForWax(pos)) {
                event.getBatch().outlineBlock(pos, colour, true);
            }
        }
    }
}
