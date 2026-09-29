package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Ignition;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

// Turns sulfur cubes into bombs and traps. Shears take the TNT out of a cube and a lighter
// primes it in the same tick before the cube notices the TNT is gone. The cube stays primed
// with its fuse run down to nought and goes off the moment TNT gets back inside.
public final class CubeRigger extends Module {

    // The server takes a click on an entity this far past the player's own reach.
    private static final double SERVER_SLACK = 3;

    // The biggest cube's box reaches under this far from its middle.
    private static final double SCAN_MARGIN = 2;

    // Ticks a clicked cube is left alone whilst the server answers.
    private static final int HANDLED_TICKS = 20;

    // Ticks a rigged cube has to show as primed before the rig counts as failed.
    private static final int CONFIRM_TICKS = 20;

    private static final String NO_SHEARS = "Shears have to be in your hotbar or offhand to rig a cube.";
    private static final String NO_FILL = "No block from Fill with is in your hotbar. Turn off Fill to rig without one.";
    private static final String NO_LIGHTER = "Nothing in your offhand or hotbar can light a cube.";

    private final BoolSetting rigOnClick = new BoolSetting("Rig on click",
        "Using flint and steel or a fire charge on a TNT cube shears it first. The cube stays primed"
            + " for good and nothing can hurt it.", true);
    private final BoolSetting fill = new BoolSetting("Fill",
        "Pushes a block into each rigged cube. The cube then never swallows the TNT it dropped and a magma"
            + " block burns whoever touches it. Only TNT from a dispenser sets a filled cube off.",
        true);
    private final RegistryListSetting<Item> fillWith = new RegistryListSetting<>("Fill with",
        "The blocks tried in order. Only blocks a cube can swallow are offered.", BuiltInRegistries.ITEM,
        List.of(Items.MAGMA_BLOCK, Items.SOUL_SAND, Items.SOUL_SOIL))
        .only(item -> BuiltInRegistries.ITEM.wrapAsHolder(item).is(ItemTags.SULFUR_CUBE_SWALLOWABLE))
        .under(fill);
    private final BoolSetting swapBack = new BoolSetting("Swap back",
        "Goes back to the slot you held once the clicks are done.", true);
    private final BoolSetting load = new BoolSetting("Load TNT",
        "Pushes TNT into empty cubes near you.", false);
    private final NumberSetting loadDelay = new NumberSetting("Load delay",
        "Ticks between one round of loading and the next.", 1, 0, 20, 1, " ticks").min(0).under(load);
    private final BoolSetting light = new BoolSetting("Light",
        "Lights TNT cubes near you. A lit cube goes off six seconds later.", false);
    private final NumberSetting lightDelay = new NumberSetting("Light delay",
        "Ticks between one round of lighting and the next.", 1, 0, 20, 1, " ticks").min(0).under(light);
    private final BoolSetting rigInstead = new BoolSetting("Rig instead",
        "Rigs the cubes rather than lighting them. It needs shears in your hotbar.", false).under(light);
    private final Ignition ignition = new Ignition().under(light);
    private final NumberSetting range = new NumberSetting("Range",
        "How far away the cubes that are loaded and lit can be. The server takes clicks up to three blocks"
            + " past your own reach.", 4.5, 1, 6, 0.1, " blocks").min(1)
        .visibleWhen(this::auraOn);
    private final NumberSetting perRound = new NumberSetting("Cubes per round",
        "How many cubes are loaded or lit in one round.", 1, 1, 8, 1).min(1)
        .visibleWhen(this::auraOn);
    private final BoolSetting spareNamed = new BoolSetting("Spare named",
        "Leaves cubes with a name tag or on a lead alone. Tag the cubes of your own farm.", true)
        .visibleWhen(this::auraOn);
    private final BoolSetting safeZone = new BoolSetting("Safe zone",
        "Leaves every cube near a point alone. Useful round your own cube farm.", false)
        .visibleWhen(this::auraOn);
    private final TextSetting safeCentre = new TextSetting("Safe centre",
        "The middle of the safe zone as three numbers for x and y and z.", "").under(safeZone);
    private final ActionSetting centreHere = new ActionSetting("Centre here",
        "Makes the block you stand in the middle of the safe zone.", this::markCentre).under(safeZone);
    private final NumberSetting safeRadius = new NumberSetting("Safe radius",
        "How far the safe zone reaches from its middle.", 64, 8, 512, 8, " blocks").min(1).under(safeZone);
    private final BoolSetting nextBucket = new BoolSetting("Next bucket",
        "After you let a cube out of a bucket the next cube bucket in your hotbar is picked.", true);
    private final NumberSetting bucketDelay = new NumberSetting("Bucket delay",
        "Ticks before the next cube bucket is picked.", 1, 0, 20, 1, " ticks").min(0).under(nextBucket);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns towards each cube on the server side.", true);
    private final BoolSetting render = new BoolSetting("Show rigged",
        "Outlines rigged cubes. They stay primed and nothing hurts them.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 0f).under(render);

    private final SlotSwap slots = new SlotSwap();
    private final Cooldowns<Integer> handled = new Cooldowns<>();
    private final ChatWarning warning = new ChatWarning();

    // Cubes rigged and the tick the clicks went out. Checked until they show as primed.
    private final Map<Integer, Integer> rigging = new HashMap<>();

    private int loadTimer;
    private int lightTimer;
    private int rigged;

    // The how to line shows on each enable until the first rig of the session.
    private boolean taught;
    private boolean toldNoFill;

    // What the main hand held last tick and in which slot. A cube bucket that turns into an
    // empty bucket in the same slot has just let a cube out.
    private Item lastHeld;
    private int lastSlot = -1;
    private int pickAt = -1;

    public CubeRigger() {
        super("CubeRigger", "Turns sulfur cubes into bombs and traps.", Category.COMBAT);
        addSettings(rigOnClick, fill, fillWith, swapBack, load, loadDelay, light, lightDelay, rigInstead);
        addSettings(ignition.settings());
        addSettings(range, perRound, spareNamed, safeZone, safeCentre, centreHere, safeRadius, nextBucket,
            bucketDelay, rotate, render);
        addSettings(style.settings());
        searchTags("sulfur cube", "tnt cube", "bomb", "trap", "mine");
    }

    @Override
    public String getSuffix() {
        return count(rigged, "rigged");
    }

    @Override
    protected void onEnable() {
        slots.forget();
        handled.clear();
        rigging.clear();
        warning.clear();
        loadTimer = 0;
        lightTimer = 0;
        rigged = 0;
        toldNoFill = false;
        lastHeld = null;
        lastSlot = -1;
        pickAt = -1;
        if (rigOnClick.isOn() && !taught) {
            ChatUtil.message("Use flint and steel or a fire charge on a TNT cube to rig it. Keep shears in your hotbar.");
        }
    }

    @Override
    protected void onDisable() {
        slots.restoreIfMine();
        rigging.clear();
    }

    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (event.isCancelled() || !rigOnClick.isOn() || !inGame()
            || !(mc.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof SulfurCube cube)
            || !explosive(cube)) {
            return;
        }
        InteractionHand hand = heldLighter();
        if (hand == null) {
            return;
        }
        // Without this delay vanilla clicks again in the same tick and on every tick the button is held.
        mc.rightClickDelay = InputUtil.USE_DELAY;
        event.cancel();
        int held = InventoryUtil.selectedSlot();
        String problem = rig(cube, () -> {
            if (hand == InteractionHand.MAIN_HAND) {
                slots.select(held);
            }
            return touch(cube, hand);
        });
        if (problem != null) {
            ChatUtil.error(problem);
        }
        finishClicks();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        // A respawn starts the clock again and every time counted on it is dropped.
        if (handled.tick()) {
            rigging.clear();
            pickAt = -1;
        }
        followBucket();
        confirmRigs();
        runAuras();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn() || !inGame()) {
            return;
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof SulfurCube cube && rigged(cube)) {
                style.draw(event.getBatch(), EntityUtil.lerpedBox(cube, event.getPartialTicks()), true);
            }
        }
    }

    // Shears the TNT out and pushes the fill in and lights the cube in one tick. The server
    // takes all three clicks before the cube ticks and notices its TNT is gone. Null once the
    // clicks are sent and the reason nothing was sent otherwise.
    private String rig(SulfurCube cube, BooleanSupplier lighter) {
        if (!carries(stack -> stack.is(Items.SHEARS))) {
            return NO_SHEARS;
        }
        Item filler = fill.isOn() ? filler() : null;
        if (fill.isOn() && filler == null) {
            return NO_FILL;
        }
        clickWith(cube, stack -> stack.is(Items.SHEARS));
        if (filler != null) {
            clickWith(cube, stack -> stack.is(filler));
        }
        lighter.getAsBoolean();
        handled.put(cube.getId(), HANDLED_TICKS);
        rigging.put(cube.getId(), mc.player.tickCount);
        taught = true;
        if (filler == null && !toldNoFill) {
            toldNoFill = true;
            ChatUtil.message("With no fill block the cube can swallow the TNT it dropped once five seconds pass"
                + " and it goes off at once.");
        }
        return null;
    }

    private void runAuras() {
        if (loadTimer > 0) {
            loadTimer--;
        }
        if (lightTimer > 0) {
            lightTimer--;
        }
        boolean loading = load.isOn() && loadTimer == 0;
        boolean lighting = light.isOn() && lightTimer == 0;
        if (!loading && !lighting) {
            return;
        }
        int done = 0;
        for (Entity entity : EntityUtil.ranked(range.getValue() + SCAN_MARGIN, TargetPriority.NEAREST,
            this::inPlay)) {
            if (done >= perRound.getInt()) {
                break;
            }
            SulfurCube cube = (SulfurCube) entity;
            if (loading && !cube.hasBodyItem() && loadTnt(cube)) {
                done++;
                loadTimer = loadDelay.getInt();
            } else if (lighting && explosive(cube) && lightUp(cube)) {
                done++;
                lightTimer = lightDelay.getInt();
            }
        }
        finishClicks();
    }

    private boolean loadTnt(SulfurCube cube) {
        if (!clickWith(cube, stack -> stack.is(Items.TNT))) {
            warning.say("Load TNT needs TNT in your hotbar or offhand.");
            return false;
        }
        handled.put(cube.getId(), HANDLED_TICKS);
        return true;
    }

    private boolean lightUp(SulfurCube cube) {
        if (!ignition.atHand()) {
            warning.say(NO_LIGHTER);
            return false;
        }
        BooleanSupplier strike = () -> ignition.use(slots, hand -> touch(cube, hand));
        if (!rigInstead.isOn()) {
            strike.getAsBoolean();
            handled.put(cube.getId(), HANDLED_TICKS);
            return true;
        }
        String problem = rig(cube, strike);
        if (problem != null) {
            warning.say(problem);
        }
        return problem == null;
    }

    // Clicks the cube with the first stack the test accepts. One in the offhand needs no swap.
    // False when neither the offhand nor the hotbar holds one.
    private boolean clickWith(SulfurCube cube, Predicate<ItemStack> item) {
        if (item.test(mc.player.getOffhandItem())) {
            return touch(cube, InteractionHand.OFF_HAND);
        }
        int slot = InventoryUtil.hotbarSlot(item);
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        return touch(cube, InteractionHand.MAIN_HAND);
    }

    // The packet goes out before the game guesses the outcome. The client never knows a cube
    // holds TNT and turns a lighter down even though the server takes it.
    private boolean touch(SulfurCube cube, InteractionHand hand) {
        EntityUtil.interact(cube, hand, rotate.isOn());
        return true;
    }

    private void finishClicks() {
        if (swapBack.isOn()) {
            slots.restore();
        } else {
            slots.forget();
        }
    }

    // A rig shows as primed once the server has taken it. One that never does is reported.
    private void confirmRigs() {
        int now = mc.player.tickCount;
        Iterator<Map.Entry<Integer, Integer>> it = rigging.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Integer> entry = it.next();
            Entity entity = mc.level.getEntity(entry.getKey());
            if (entity instanceof SulfurCube cube && cube.isPrimed()) {
                rigged++;
                it.remove();
            } else if (entity == null || now - entry.getValue() > CONFIRM_TICKS) {
                if (entity != null) {
                    warning.say("A rig did not take. The server may have TNT switched off.");
                }
                it.remove();
            }
        }
    }

    // A cube let out of the held bucket leaves an empty bucket in the same slot.
    private void followBucket() {
        int slot = InventoryUtil.selectedSlot();
        Item held = mc.player.getMainHandItem().getItem();
        if (nextBucket.isOn() && slot == lastSlot && lastHeld == Items.SULFUR_CUBE_BUCKET && held == Items.BUCKET) {
            pickAt = mc.player.tickCount + bucketDelay.getInt();
        }
        lastSlot = slot;
        lastHeld = held;
        if (pickAt < 0 || mc.player.tickCount < pickAt) {
            return;
        }
        pickAt = -1;
        int next = nextCubeBucket(slot);
        if (next != -1) {
            mc.player.getInventory().setSelectedSlot(next);
        }
    }

    // The first hotbar slot after the given one that holds a cube bucket. Minus one when none does.
    private static int nextCubeBucket(int from) {
        for (int step = 1; step < InventoryUtil.HOTBAR_SIZE; step++) {
            int slot = (from + step) % InventoryUtil.HOTBAR_SIZE;
            if (mc.player.getInventory().getItem(slot).is(Items.SULFUR_CUBE_BUCKET)) {
                return slot;
            }
        }
        return -1;
    }

    private boolean auraOn() {
        return load.isOn() || light.isOn();
    }

    // A grown cube that is not primed yet and is in reach and not spared.
    private boolean inPlay(Entity entity) {
        if (!(entity instanceof SulfurCube cube) || !cube.isAlive() || cube.isBaby() || cube.isPrimed()
            || handled.contains(cube.getId()) || spared(cube)) {
            return false;
        }
        double reach = Math.min(range.getValue(), EntityUtil.serverEntityReach() + SERVER_SLACK);
        return EntityUtil.reachDistance(mc.player, cube) <= reach;
    }

    private boolean spared(SulfurCube cube) {
        if (spareNamed.isOn() && (cube.hasCustomName() || cube.isLeashed())) {
            return true;
        }
        BlockPos centre = safeZone.isOn() ? BlockUtil.parse(safeCentre.getValue()) : null;
        return centre != null && cube.position().distanceTo(Vec3.atCenterOf(centre)) <= safeRadius.getValue();
    }

    private String markCentre() {
        if (!inGame()) {
            return "Join a world first";
        }
        String here = BlockUtil.text(mc.player.blockPosition());
        safeCentre.setValue(here);
        return "Set to " + here;
    }

    // A grown cube holding TNT that nobody has lit yet. Babies never take a lighter.
    private static boolean explosive(SulfurCube cube) {
        return !cube.isBaby() && !cube.isPrimed()
            && cube.getBodyArmorItem().is(ItemTags.SULFUR_CUBE_ARCHETYPE_EXPLOSIVE);
    }

    // Primed with no TNT inside. A cube lit the plain way still holds its TNT.
    private static boolean rigged(SulfurCube cube) {
        return cube.isPrimed() && !cube.getBodyArmorItem().is(ItemTags.SULFUR_CUBE_ARCHETYPE_EXPLOSIVE);
    }

    // The hand holding flint and steel or a fire charge. The main hand goes first as it does in the game.
    private static InteractionHand heldLighter() {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = mc.player.getItemInHand(hand);
            if (stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.FIRE_CHARGE)) {
                return hand;
            }
        }
        return null;
    }

    private static boolean carries(Predicate<ItemStack> item) {
        return item.test(mc.player.getOffhandItem()) || InventoryUtil.hotbarSlot(item) != -1;
    }

    // The first block on the fill list that a cube swallows and that is at hand.
    private Item filler() {
        for (Item item : fillWith.resolved()) {
            if (carries(stack -> stack.is(item) && stack.is(ItemTags.SULFUR_CUBE_SWALLOWABLE))) {
                return item;
            }
        }
        return null;
    }
}
