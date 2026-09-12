package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.combat.AimAssist;
import com.jellypudding.offlineclient.modules.misc.AntiAfk;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.TickRate;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

// A bite is spotted from the bobber's synced flag and from the splash sound near it.
// The delays run at the server's tick rate. A lagging server still reels in on time.
public final class AutoFish extends Module {

    // Durability points left that count as about to break.
    private static final int NEARLY_BROKEN = 2;

    // A random shift is cut off at this many standard deviations.
    private static final double SPREAD_CUTOFF = 3;

    // How far the player may drift before a recorded spot is thrown away.
    private static final double SPOT_DRIFT = 0.4;

    // Two casts count as the same aim within this many degrees.
    private static final float SAME_AIM = 0.5f;

    public enum BiteMode {
        SOUND,
        ENTITY,
        BOTH
    }

    private final EnumSetting<BiteMode> biteMode = new EnumSetting<>("Bite mode",
        "Which signal is trusted to mean a fish is on the line.", BiteMode.BOTH)
        .describe(BiteMode.SOUND, "Only the splash sound. Safer against anti cheat.")
        .describe(BiteMode.ENTITY, "Only the bobber's own bite flag. More accurate.")
        .describe(BiteMode.BOTH, "Either signal reels the rod in.");
    private final NumberSetting validRange = new NumberSetting("Valid range",
        "How far from your bobber a splash may sound and still count as yours.",
        1.5, 0.25, 8, 0.25, " blocks").min(0.25)
        .under(biteMode, BiteMode.SOUND, BiteMode.BOTH);
    private final BoolSetting autoCast = new BoolSetting("Auto cast",
        "Casts the rod for you. Off only reels in and leaves the casting to you.", true);
    private final NumberSetting recastDelay = new NumberSetting("Recast delay",
        "Ticks to wait after reeling in before casting again.", 15, 1, 60, 1, " ticks")
        .under(autoCast);
    private final NumberSetting recastSpread = new NumberSetting("Recast spread",
        "The most ticks the recast delay shifts either way. The shift is random and usually small.",
        0, 0, 30, 1, " ticks").min(0)
        .under(autoCast);
    private final NumberSetting catchDelay = new NumberSetting("Catch delay",
        "Ticks to wait after a bite before reeling in.", 4, 0, 20, 1, " ticks");
    private final NumberSetting catchSpread = new NumberSetting("Catch spread",
        "The most ticks the catch delay shifts either way. Above 10 a quick bite can be missed.",
        0, 0, 10, 1, " ticks").min(0);
    private final NumberSetting retryDelay = new NumberSetting("Retry delay",
        "Ticks to wait after a cast or a reel in that the game refused.",
        15, 0, 100, 1, " ticks").min(0);
    private final NumberSetting patience = new NumberSetting("Patience",
        "Seconds to wait without a bite before reeling in and recasting.", 60, 10, 120, 1, "s");
    private final BoolSetting autoSwitch = new BoolSetting("Auto switch",
        "Move to the best fishing rod you own. The whole inventory and the offhand are searched.",
        true);
    private final BoolSetting antiBreak = new BoolSetting("Anti break",
        "Never use a rod that is about to break.", true);
    private final BoolSetting stopOutOfRods = new BoolSetting("Stop when out of rods",
        "Switch the module off once no usable rod is left.", false);
    private final BoolSetting stopWhenFull = new BoolSetting("Stop when full",
        "Switch the module off once your inventory has no free slot.", false);
    private final BoolSetting shallowWarning = new BoolSetting("Shallow water warning",
        "Warn in chat when the bobber is not in open water so no treasure can be caught.", true);
    private final BoolSetting mcmmoMode = new BoolSetting("mcMMO mode",
        "Cycle between two fishing spots so the mcMMO overfishing penalty never starts.", false);
    private final NumberSetting mcmmoRange = new NumberSetting("mcMMO range",
        "The MoveRange value of the plugin. The least distance between the two spots.",
        3, 1, 50, 1, " blocks").min(1)
        .under(mcmmoMode);
    private final BoolSetting mcmmoRangeBug = new BoolSetting("mcMMO range bug",
        "Round the range down to an even number the way the plugin's own check does.", true)
        .under(mcmmoMode);
    private final NumberSetting mcmmoLimit = new NumberSetting("mcMMO limit",
        "The OverFishLimit value of the plugin. Fish taken from one spot before switching.",
        10, 2, 1000, 1, "").min(2)
        .under(mcmmoMode);
    private final BoolSetting debugDraw = new BoolSetting("Debug draw",
        "Draw the valid range around the bobber and every splash you hear.", false);
    private final ColorSetting debugColor = new ColorSetting("Debug colour",
        "Colour of the debug drawing.", 0, 0.79f, 0.88f, false)
        .under(debugDraw);

    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private double castTimer;
    private boolean reeling;
    private double reelTimer;
    private int patienceTimer;
    // Written from the packet thread.
    private volatile boolean splashHeard;
    private volatile Vec3 lastSoundPos;
    // Holding the bobber itself would pin the world.
    private int reeledId = -1;
    private int caught;
    private boolean outOfRods;
    private boolean warnedShallow;

    // One cast of the rod. The bobber lands where the aim sent it.
    private record Spot(Vec3 playerPos, float yaw, float pitch, Vec3 bobberPos) {
    }

    private final List<Spot> spots = new ArrayList<>();
    private Spot lastSpot;
    private Spot nextSpot;
    private Vec3 castPos = Vec3.ZERO;
    private float castYaw;
    private float castPitch;
    private int caughtAtSpot;
    private boolean askedForSpot;
    private boolean toldToWait;
    private boolean toldReady;

    public AutoFish() {
        super("AutoFish", "Casts and reels the fishing rod for you.", Category.PLAYER);
        addSettings(biteMode, validRange, autoCast, recastDelay, recastSpread, catchDelay,
            catchSpread, retryDelay, patience, autoSwitch, antiBreak, stopOutOfRods,
            stopWhenFull, shallowWarning, mcmmoMode, mcmmoRange, mcmmoRangeBug, mcmmoLimit,
            debugDraw, debugColor);
        searchTags("fishing", "afk fish", "rod", "mcmmo");
    }

    @Override
    public String getSuffix() {
        return outOfRods ? "out of rods" : caught + " caught";
    }

    @Override
    protected void onDisable() {
        loan.giveBack();
        spots.clear();
    }

    @Override
    protected void onEnable() {
        castTimer = 0;
        reeling = false;
        patienceTimer = 0;
        splashHeard = false;
        lastSoundPos = null;
        reeledId = -1;
        caught = 0;
        outOfRods = false;
        warnedShallow = false;
        resetSpots();
        // Both of these fight the rod for the view and the use key.
        AntiAfk antiAfk = Modules.active(AntiAfk.class);
        if (antiAfk != null) {
            antiAfk.setEnabled(false);
        }
        AimAssist aimAssist = Modules.active(AimAssist.class);
        if (aimAssist != null) {
            aimAssist.setEnabled(false);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (castTimer > 0) {
            castTimer -= serverTick();
        }
        if (mc.gui.screen() != null) {
            return;
        }
        if (!pickRod()) {
            return;
        }
        ItemStack held = mc.player.getMainHandItem();
        if (!(held.getItem() instanceof FishingRodItem)) {
            return;
        }
        // Reeling in wears the rod down as much as casting does.
        if (antiBreak.isOn() && ItemUtil.nearlyBroken(held, NEARLY_BROKEN)) {
            return;
        }

        FishingHook bobber = mc.player.fishing;
        if (bobber == null || bobber.isRemoved()) {
            tryCast();
            return;
        }
        // The old bobber lingers for a tick or two after reeling.
        if (bobber.getId() == reeledId) {
            return;
        }
        tryReel(bobber);
    }

    // The patience clock is wound whilst no bobber is out. A cast made by
    // hand is timed the same as one made here.
    private void tryCast() {
        reeling = false;
        splashHeard = false;
        reeledId = -1;
        patienceTimer = patience.getInt() * 20;
        if (!autoCast.isOn() || castTimer > 0) {
            return;
        }
        if (!readyToCast()) {
            return;
        }
        castTimer = useRod() ? shifted(recastDelay.getInt(), recastSpread.getInt())
            : retryDelay.getInt();
    }

    private void tryReel(FishingHook bobber) {
        boolean bite = bitten(bobber);
        if (bite && !reeling) {
            reeling = true;
            reelTimer = shifted(catchDelay.getInt(), catchSpread.getInt());
            checkWater(bobber);
            recordSpot(bobber);
        }
        if (!reeling && patienceTimer > 0) {
            patienceTimer--;
            if (patienceTimer == 0) {
                reeling = true;
                reelTimer = 0;
            }
        }
        if (!reeling) {
            return;
        }
        if (reelTimer > 0) {
            reelTimer -= serverTick();
            return;
        }

        if (!useRod()) {
            reelTimer = retryDelay.getInt();
            return;
        }
        if (bite) {
            caught++;
        }
        reeledId = bobber.getId();
        reeling = false;
        splashHeard = false;
        castTimer = shifted(recastDelay.getInt(), recastSpread.getInt());
    }

    private boolean bitten(FishingHook bobber) {
        boolean entity = bobber.biting || bobber.getHookedIn() != null;
        return switch (biteMode.getValue()) {
            case SOUND -> splashHeard;
            case ENTITY -> entity;
            case BOTH -> entity || splashHeard;
        };
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (biteMode.is(BiteMode.ENTITY)) {
            return;
        }
        if (!(event.getPacket() instanceof ClientboundSoundPacket sound)) {
            return;
        }
        if (sound.getSound().value() != SoundEvents.FISHING_BOBBER_SPLASH) {
            return;
        }
        // The packet thread can drop the player mid handler.
        LocalPlayer player = mc.player;
        FishingHook bobber = player == null ? null : player.fishing;
        if (bobber == null || bobber.isRemoved()) {
            return;
        }
        lastSoundPos = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        // Someone else's bobber can splash nearby.
        double dx = Math.abs(sound.getX() - bobber.getX());
        double dz = Math.abs(sound.getZ() - bobber.getZ());
        if (Math.max(dx, dz) <= validRange.getValue()) {
            splashHeard = true;
        }
    }

    private boolean useRod() {
        MultiPlayerGameMode gameMode = mc.gameMode;
        if (gameMode == null) {
            return false;
        }
        if (!gameMode.useItem(mc.player, InteractionHand.MAIN_HAND).consumesAction()) {
            return false;
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    // How much of a server tick one client tick is worth.
    private static double serverTick() {
        return TickRate.INSTANCE.tps() / 20.0;
    }

    // The delay shifted by a random amount that is usually small and never
    // more than the spread either way.
    private static double shifted(int delay, int spread) {
        if (spread == 0) {
            return delay;
        }
        double normal = ThreadLocalRandom.current().nextGaussian();
        double share = Math.clamp(normal, -SPREAD_CUTOFF, SPREAD_CUTOFF) / SPREAD_CUTOFF;
        return Math.max(0, delay + Math.round(share * spread));
    }

    // False whilst the hand is busy or the rod moved this tick.
    private boolean pickRod() {
        if (Modules.eating()) {
            return false;
        }
        int best = -1;
        int bestScore = -1;
        for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            int score = rodScore(mc.player.getInventory().getItem(i));
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (rodScore(mc.player.getOffhandItem()) > bestScore) {
            best = InventoryUtil.OFFHAND_SLOT;
        }
        outOfRods = best == -1;
        if (outOfRods && stopOutOfRods.isOn()) {
            ChatUtil.message("AutoFish has run out of rods.");
            setEnabled(false);
            return false;
        }
        if (stopWhenFull.isOn() && mc.player.getInventory().getFreeSlot() == -1) {
            ChatUtil.message("AutoFish has stopped because your inventory is full.");
            setEnabled(false);
            return false;
        }
        if (!autoSwitch.isOn() || best == -1) {
            return true;
        }
        if (best == InventoryUtil.OFFHAND_SLOT) {
            return borrowOffhand();
        }
        int before = InventoryUtil.selectedSlot();
        return loan.select(best) && InventoryUtil.selectedSlot() == before;
    }

    // Minus one when the stack is no rod or no rod worth using.
    private int rodScore(ItemStack stack) {
        if (!(stack.getItem() instanceof FishingRodItem)) {
            return -1;
        }
        if (antiBreak.isOn() && ItemUtil.nearlyBroken(stack, NEARLY_BROKEN)) {
            return -1;
        }
        int keeps = ItemUtil.enchantLevel(Enchantments.VANISHING_CURSE, stack) == 0 ? 1 : 0;
        return ItemUtil.enchantLevel(Enchantments.LUCK_OF_THE_SEA, stack) * 9
            + ItemUtil.enchantLevel(Enchantments.LURE, stack) * 9
            + ItemUtil.enchantLevel(Enchantments.UNBREAKING, stack) * 2
            + ItemUtil.enchantLevel(Enchantments.MENDING, stack)
            + keeps;
    }

    // Swaps the offhand rod into the hotbar. Always waits a tick afterwards.
    private boolean borrowOffhand() {
        if (!InventoryUtil.canClick() || !InventoryUtil.carried().isEmpty()) {
            return false;
        }
        int hotbar = InventoryUtil.freeHotbarSlot(InventoryUtil.selectedSlot());
        mc.gameMode.handleContainerInput(0, InventoryUtil.OFFHAND_SLOT, hotbar,
            ContainerInput.SWAP, mc.player);
        mc.player.getInventory().setSelectedSlot(hotbar);
        return false;
    }

    private void checkWater(FishingHook bobber) {
        if (openWater(bobber)) {
            warnedShallow = false;
            return;
        }
        if (!shallowWarning.isOn() || warnedShallow) {
            return;
        }
        ChatUtil.message("You are fishing in shallow water.");
        ChatUtil.message("No treasure can be caught like this. Use OpenWaterESP to find deeper water.");
        warnedShallow = true;
    }

    // A bobber riding high sits in the air block above the water it floats on.
    private boolean openWater(FishingHook bobber) {
        BlockPos pos = bobber.blockPosition();
        if (!mc.level.getFluidState(pos).is(FluidTags.WATER)) {
            BlockPos below = pos.below();
            if (mc.level.getFluidState(below).is(FluidTags.WATER)) {
                pos = below;
            }
        }
        return bobber.calculateOpenWater(pos);
    }

    private void resetSpots() {
        spots.clear();
        lastSpot = null;
        nextSpot = null;
        castPos = Vec3.ZERO;
        castYaw = 0;
        castPitch = 0;
        caughtAtSpot = 0;
        askedForSpot = false;
        toldToWait = false;
        toldReady = false;
    }

    // Remembers where this cast is aimed and turns towards the next spot when
    // the current one has given up its share of the fish.
    private boolean readyToCast() {
        castPos = mc.player.position();
        castYaw = mc.player.getYRot();
        castPitch = mc.player.getXRot();
        if (!mcmmoMode.isOn()) {
            return true;
        }
        if (lastSpot == null) {
            if (!toldToWait) {
                ChatUtil.message("Starting mcMMO mode. Wait whilst the first spot is recorded.");
                toldToWait = true;
            }
            return true;
        }
        toldToWait = false;
        if (caughtAtSpot < mcmmoLimit.getInt() - 1) {
            return true;
        }
        if (nextSpot == null) {
            nextSpot = chooseNextSpot();
        }
        if (nextSpot == null) {
            if (!askedForSpot) {
                ChatUtil.message("mcMMO mode needs a second fishing spot.");
                ChatUtil.message("Aim so the bobber lands outside the box then cast by hand.");
                askedForSpot = true;
                toldReady = false;
            }
            return false;
        }
        askedForSpot = false;
        return turnToNextSpot();
    }

    // False whilst the view is still swinging round or the spot has gone stale.
    private boolean turnToNextSpot() {
        if (mc.player.position().distanceToSqr(nextSpot.playerPos()) > SPOT_DRIFT * SPOT_DRIFT) {
            spots.remove(nextSpot);
            nextSpot = null;
            return false;
        }
        RotationManager.requestExact(nextSpot.yaw(), nextSpot.pitch(), RotationPriority.IDLE);
        if (!RotationManager.sentIsFacing(nextSpot.yaw(), nextSpot.pitch(), SAME_AIM)) {
            return false;
        }
        castYaw = nextSpot.yaw();
        castPitch = nextSpot.pitch();
        lastSpot = nextSpot;
        nextSpot = null;
        caughtAtSpot = 0;
        if (!toldReady) {
            ChatUtil.message("Both spots are set. AutoFish will switch between them.");
            toldReady = true;
        }
        return true;
    }

    private void recordSpot(FishingHook bobber) {
        if (!mcmmoMode.isOn()) {
            return;
        }
        boolean sameAim = lastSpot != null && sameAim(lastSpot);
        boolean sameWater = lastSpot != null && inRange(lastSpot.bobberPos(), bobber.position());
        caughtAtSpot = sameWater ? caughtAtSpot + 1 : 1;
        if (!sameAim) {
            lastSpot = new Spot(castPos, castYaw, castPitch, bobber.position());
            spots.add(lastSpot);
            return;
        }
        if (!sameWater) {
            // The same aim landed somewhere new. The old note was wrong.
            Spot fixed = new Spot(lastSpot.playerPos(), lastSpot.yaw(), lastSpot.pitch(),
                bobber.position());
            spots.remove(lastSpot);
            spots.add(fixed);
            lastSpot = fixed;
        }
    }

    private boolean sameAim(Spot spot) {
        return Mth.degreesDifferenceAbs(spot.yaw(), castYaw) <= SAME_AIM
            && Math.abs(spot.pitch() - castPitch) <= SAME_AIM
            && spot.playerPos().distanceToSqr(castPos) <= SPOT_DRIFT * SPOT_DRIFT;
    }

    // The nearest aim that lands the bobber clear of the spot in use.
    private Spot chooseNextSpot() {
        Vec3 here = mc.player.position();
        return spots.stream()
            .filter(spot -> spot != lastSpot)
            .filter(spot -> spot.playerPos().distanceToSqr(here) <= SPOT_DRIFT * SPOT_DRIFT)
            .filter(spot -> !inRange(spot.bobberPos(), lastSpot.bobberPos()))
            .min(Comparator.comparingDouble(spot -> Mth.degreesDifferenceAbs(spot.yaw(),
                lastSpot.yaw()) + Math.abs(spot.pitch() - lastSpot.pitch())))
            .orElse(null);
    }

    private boolean inRange(Vec3 first, Vec3 second) {
        if (Math.abs(first.y - second.y) > 2) {
            return false;
        }
        double dx = Math.abs(first.x - second.x);
        double dz = Math.abs(first.z - second.z);
        return Math.max(dx, dz) <= spotRange();
    }

    // The plugin measures the range in whole even blocks whilst the bug stands.
    private int spotRange() {
        return mcmmoRangeBug.isOn() ? mcmmoRange.getInt() / 2 * 2 : mcmmoRange.getInt();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        if (debugDraw.isOn()) {
            drawDebug(event.getBatch(), event.getPartialTicks());
        }
        if (mcmmoMode.isOn() && lastSpot != null && (nextSpot == null || debugDraw.isOn())) {
            drawSpotRange(event.getBatch());
        }
    }

    private void drawDebug(DrawBatch batch, float partialTicks) {
        int colour = debugColor.getColor();
        FishingHook bobber = mc.player.fishing;
        if (bobber != null && !bobber.isRemoved()) {
            double range = validRange.getValue();
            Vec3 pos = EntityUtil.lerpedBox(bobber, partialTicks).getCenter();
            batch.outlineBox(new AABB(-range, -0.0625, -range, range, 0.0625, range).move(pos),
                colour, false);
        }
        Vec3 sound = lastSoundPos;
        if (sound != null) {
            batch.line(sound.add(-0.125, 0, -0.125), sound.add(0.125, 0, 0.125), colour, false);
            batch.line(sound.add(0.125, 0, -0.125), sound.add(-0.125, 0, 0.125), colour, false);
        }
        for (Spot spot : spots) {
            batch.outlineBox(new AABB(spot.bobberPos(), spot.bobberPos()).inflate(0.2),
                colour, false);
            batch.line(spot.playerPos().add(0, mc.player.getEyeHeight(), 0), spot.bobberPos(),
                colour, false);
        }
    }

    private void drawSpotRange(DrawBatch batch) {
        int colour = debugColor.getColor();
        int range = spotRange();
        AABB box = new AABB(lastSpot.bobberPos(), lastSpot.bobberPos()).inflate(range, 1, range);
        batch.solidBox(box, ColorUtil.withAlpha(colour, 64), false);
        batch.outlineBox(box, colour, false);
    }
}
